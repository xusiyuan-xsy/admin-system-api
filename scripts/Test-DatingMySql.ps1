param(
    [string]$MySqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0',
    [switch]$IncludeAuthHttp,
    [switch]$IncludeWorkflowHttp,
    [switch]$VerifyAcceptanceRoles,
    [string]$RetainedWorkflowClassPath,
    [string]$ArtifactManifest
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$mysql = Join-Path $MySqlHome 'bin\mysql.exe'
$mysqld = Join-Path $MySqlHome 'bin\mysqld.exe'
foreach ($file in @($mysql, $mysqld)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing executable: $file" }
}
if (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 3307 is occupied. Refusing to reuse or stop an existing instance.'
}
$run = Join-Path ([IO.Path]::GetTempPath()) ('dating-mysql-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N'))
if ($run -match '[^\x00-\x7F]') { throw 'MySQL test artifacts require an ASCII temporary directory.' }
$data = Join-Path $run 'data'
New-Item -ItemType Directory -Path $data -Force | Out-Null
$oldUrl = $env:DATING_MYSQL_TEST_URL
$oldAuth = $env:DATING_AUTH_HTTP_TEST
$oldWorkflow = $env:DATING_WORKFLOW_HTTP_TEST
$oldData = $env:DATING_HTTP_MYSQL_DATADIR
$server = $null
$utf8 = New-Object System.Text.UTF8Encoding($false)
if ($ArtifactManifest) {
    if (Test-Path -LiteralPath $ArtifactManifest) { throw 'Artifact manifest must be a new file.' }
    [IO.File]::WriteAllText($ArtifactManifest, (@{ directory=$run; datadir=$data } | ConvertTo-Json), $utf8)
}

function Invoke-TestSql([string]$Sql) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $mysql
    $info.WorkingDirectory = $run
    $info.Arguments = '--no-defaults --protocol=TCP --host=127.0.0.1 --port=3307 --user=root --skip-password --default-character-set=utf8mb4 --batch --skip-column-names --connect-timeout=2'
    $info.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = Join-Path $run 'no-login.cnf'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $process = [System.Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $inputWriter = New-Object System.IO.StreamWriter($process.StandardInput.BaseStream, $utf8)
        $inputWriter.WriteLine($Sql)
        $inputWriter.Close()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill()
            throw 'Isolated MySQL command timed out.'
        }
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Isolated MySQL command failed: $errorText" }
        return $output.Trim()
    }
    finally { $process.Dispose() }
}

function Assert-TestInstance {
    $identity = (Invoke-TestSql 'SELECT @@port, @@datadir;') -split "`t"
    $actual = [IO.Path]::GetFullPath($identity[1]).TrimEnd('\', '/')
    if ($identity[0] -ne '3307' -or $actual -ne [IO.Path]::GetFullPath($data).TrimEnd('\', '/')) {
        throw 'MySQL identity mismatch. Refusing schema writes or shutdown.'
    }
}

# Extract only named CREATE blocks from these known repository fixtures, never their seeds or DROP statements.
function Get-BaseTable([string]$File, [string]$Table) {
    $sql = Get-Content -LiteralPath (Join-Path $repo $File) -Raw -Encoding UTF8
    $pattern = '(?ims)^create table ' + [regex]::Escape($Table) + '\s*\(.*?^\) engine=innodb[^\r\n]*;'
    $blocks = [regex]::Matches($sql, $pattern)
    if ($blocks.Count -ne 1) { throw "Expected one CREATE block for $Table in $File" }
    return $blocks[0].Value
}

try {
    Write-Host "Disposable test artifacts: $run"
    $baseArgs = "--no-defaults --basedir=`"$MySqlHome`" --datadir=`"$data`""
    $init = Start-Process -FilePath $mysqld -ArgumentList "$baseArgs --initialize-insecure --console" `
        -WorkingDirectory $run -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $run 'initialize.out.log') `
        -RedirectStandardError (Join-Path $run 'initialize.err.log')
    if ($init.ExitCode -ne 0) { throw 'Isolated MySQL initialization failed; inspect initialize.err.log.' }
    $server = Start-Process -FilePath $mysqld `
        -ArgumentList "$baseArgs --standalone --no-monitor --port=3307 --bind-address=127.0.0.1 --mysqlx=OFF --log-error=`"$run\mysql.err.log`"" `
        -WorkingDirectory $run -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'server.out.log') `
        -RedirectStandardError (Join-Path $run 'server.err.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw 'Isolated MySQL exited; inspect mysql.err.log and server.err.log.' }
        try {
            Assert-TestInstance
            $ready = $true
            break
        }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Isolated MySQL did not become ready.' }
    $base = @(
        'CREATE DATABASE dating_workflow_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
        'USE dating_workflow_test;',
        (Get-BaseTable 'sql/ry_20260321.sql' 'sys_user'),
        (Get-BaseTable 'sql/ry_20260321.sql' 'sys_menu'),
        (Get-BaseTable 'sql/ry_20260321.sql' 'sys_role_menu'),
        (Get-BaseTable 'sql/matchmaking_profile.sql' 'sys_paying_user'),
        'ALTER TABLE sys_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
        'ALTER TABLE sys_paying_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
        "INSERT INTO sys_user(user_id,user_name,nick_name,password) VALUES(1,'admin','Isolated test staff','TEST_DISABLED');",
        "INSERT INTO sys_menu(menu_id,menu_name,component,menu_type) VALUES(118,'Test archive','system/payingUsers/index','C');"
    ) -join "`n"
    Assert-TestInstance
    Invoke-TestSql $base | Out-Null
    if ($IncludeWorkflowHttp) {
        Assert-TestInstance
        Invoke-TestSql ("USE dating_workflow_test;`n" + (Get-BaseTable 'sql/matchmaking_profile.sql' 'sys_paying_requirement')) | Out-Null
        foreach ($table in @('sys_dept', 'sys_config', 'sys_logininfor', 'sys_oper_log')) {
            Assert-TestInstance
            Invoke-TestSql ("USE dating_workflow_test;`n" + (Get-BaseTable 'sql/ry_20260321.sql' $table)) | Out-Null
        }
    }
    if ($IncludeWorkflowHttp -or $VerifyAcceptanceRoles) {
        foreach ($table in @('sys_role', 'sys_user_role', 'sys_role_dept')) {
            Assert-TestInstance
            Invoke-TestSql ("USE dating_workflow_test;`n" + (Get-BaseTable 'sql/ry_20260321.sql' $table)) | Out-Null
        }
    }
    if ($VerifyAcceptanceRoles) {
        Assert-TestInstance
        Invoke-TestSql ("USE dating_workflow_test;`n" + (Get-BaseTable 'sql/ry_20260321.sql' 'sys_user_post')) | Out-Null
    }
    $migrations = @(
        '20261013_dating_prerequisites.sql',
        '20261013_dating_billing.sql',
        '20261013_dating_workflow.sql',
        '20261013_dating_success.sql',
        '20261013_dating_comments.sql',
        '20261014_dating_p0_permissions.sql',
        '20261016_dating_recovery.sql',
        '20261016_dating_retry_reason.sql',
        '20261016_dating_exception_followup.sql',
        '20261016_dating_followup_collation.sql'
    )
    $inventorySql = @'
USE dating_workflow_test;
SELECT table_name,engine,table_collation,table_comment FROM information_schema.tables
WHERE table_schema=DATABASE() ORDER BY table_name;
SELECT table_name,column_name,column_type,is_nullable,IFNULL(column_default,'NULL'),extra,column_comment,character_set_name,collation_name
FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position;
SELECT table_name,index_name,seq_in_index,column_name,non_unique
FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index;
SELECT table_name,constraint_name,constraint_type FROM information_schema.table_constraints
WHERE constraint_schema=DATABASE() ORDER BY table_name,constraint_name;
SELECT constraint_name,check_clause FROM information_schema.check_constraints
WHERE constraint_schema=DATABASE() ORDER BY constraint_name;
SELECT * FROM sys_menu ORDER BY menu_id;
SELECT * FROM sys_role_menu ORDER BY role_id,menu_id;
SELECT * FROM dating_service_option ORDER BY option_id;
'@
    $inventory = $null
    for ($pass = 1; $pass -le 2; $pass++) {
        foreach ($migration in $migrations) {
            Assert-TestInstance
            $sql = Get-Content -LiteralPath (Join-Path $repo "sql/upgrade/$migration") -Raw -Encoding UTF8
            if ($pass -eq 1 -and $migration -eq '20261016_dating_followup_collation.sql') {
                $reproduced = $false
                try {
                    Invoke-TestSql 'USE dating_workflow_test; SELECT COUNT(*) FROM dating_exception_followup f LEFT JOIN sys_user s ON s.user_name=f.handler;' | Out-Null
                } catch {
                    if ($_.Exception.Message -notmatch '1267.*Illegal mix of collations') { throw }
                    $reproduced = $true
                }
                if (!$reproduced) { throw 'Mixed-collation fixture did not reproduce the reported failure.' }
                Write-Host 'Reproduced MySQL 1267 on the empty follow-up join before the repair.'
            }
            Invoke-TestSql ("USE dating_workflow_test;`n" + $sql) | Out-Null
        }
        $current = Invoke-TestSql $inventorySql
        if ($pass -eq 1) { $inventory = $current }
        elseif ($inventory -cne $current) { throw 'Schema/menu/option inventory changed after migration replay.' }
    }
    Write-Host 'Ten migrations applied twice; column/collation/index/constraint/menu/option inventories match.'
    $tableCount = Invoke-TestSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='dating_workflow_test' AND LEFT(table_name,7)='dating_';"
    if ($tableCount -ne '21') { throw "Expected 21 dating tables, found $tableCount" }
    $missingComments = Invoke-TestSql @'
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema='dating_workflow_test'
AND table_name IN ('dating_recovery_run','dating_deduction_history','dating_exception_followup','dating_exception_followup_history') AND column_comment='';
'@
    if ($missingComments -ne '0') { throw 'Recovery columns must all have comments.' }
    if ($VerifyAcceptanceRoles) {
        Assert-TestInstance
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $repo 'ruoyi-admin/target/ruoyi-admin.jar'))
        try {
            foreach ($dependency in @('snakeyaml', 'fastjson2', 'mysql-connector-j', 'spring-security-crypto')) {
                $entries = @($zip.Entries | Where-Object { $_.FullName -match "^BOOT-INF/lib/$dependency-[0-9][^/]*\.jar$" })
                if ($entries.Count -ne 1) { throw "Expected one packaged dependency: $dependency" }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $run $entries[0].Name))
            }
        } finally { $zip.Dispose() }
        & javac -encoding UTF-8 -cp "$run\*" -d $run (Join-Path $PSScriptRoot 'DatingAcceptanceRoles.java') `
            (Join-Path $PSScriptRoot 'tests/DatingAcceptanceRolesTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Role test compilation failed.' }
        & java -cp "$run;$run\*" DatingAcceptanceRolesTest $data
        if ($LASTEXITCODE -ne 0) { throw 'Isolated acceptance role tests failed.' }
        & javac -encoding UTF-8 -cp "$run;$run\*" -d $run (Join-Path $PSScriptRoot 'DatingAcceptanceAccounts.java') `
            (Join-Path $PSScriptRoot 'tests/DatingAcceptanceAccountsTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Account test compilation failed.' }
        & java -cp "$run;$run\*" DatingAcceptanceAccountsTest $data
        if ($LASTEXITCODE -ne 0) { throw 'Isolated acceptance account tests failed.' }
    }
    $env:DATING_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3307/dating_workflow_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
    $env:DATING_AUTH_HTTP_TEST = if ($IncludeAuthHttp -or $IncludeWorkflowHttp) { 'true' } else { $null }
    $env:DATING_WORKFLOW_HTTP_TEST = if ($IncludeWorkflowHttp) { 'true' } else { $null }
    $env:DATING_HTTP_MYSQL_DATADIR = if ($IncludeWorkflowHttp) { $data } else { $null }
    Push-Location $repo
    try {
        & mvn -pl ruoyi-admin -am test --log-file (Join-Path $run 'maven.log') -q
        if ($LASTEXITCODE -ne 0) { throw "Maven tests failed; inspect $run\maven.log and surefire reports." }
    }
    finally { Pop-Location }
    $total = 0
    $skipped = 0
    $workflowRan = $false
    $authRan = $false
    $recoveryRan = $false
    foreach ($module in @('ruoyi-system', 'ruoyi-admin')) {
        $reports = Join-Path $run "$module-reports"
        New-Item -ItemType Directory -Path $reports | Out-Null
        foreach ($file in Get-ChildItem (Join-Path $repo "$module/target/surefire-reports/TEST-*.xml")) {
            Copy-Item -LiteralPath $file.FullName -Destination $reports
            [xml]$report = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            $suite = $report.testsuite
            $total += [int]$suite.tests
            $skipped += [int]$suite.skipped
            if ($suite.name -match 'DatingWorkflowHttpMySqlTest$') {
                $workflowRan = [int]$suite.tests -ge 8 -and [int]$suite.skipped -eq 0 -and
                    [int]$suite.failures -eq 0 -and [int]$suite.errors -eq 0
            }
            if ($suite.name -match 'DatingAuthenticationHttpTest$') {
                $authRan = [int]$suite.tests -ge 11 -and [int]$suite.skipped -eq 0 -and
                    [int]$suite.failures -eq 0 -and [int]$suite.errors -eq 0
            }
            if ($suite.name -match 'DatingRecoveryMySqlTest$') {
                $recoveryRan = [int]$suite.tests -ge 4 -and [int]$suite.skipped -eq 0 -and
                    [int]$suite.failures -eq 0 -and [int]$suite.errors -eq 0
            }
            if ($suite.name -match 'Dating(Billing|Workflow|Success|Recovery|ExceptionFollowup)MySqlTest$' -and
                ([int]$suite.tests -lt 1 -or [int]$suite.skipped -ne 0)) {
                throw "MySQL suite did not run: $($suite.name)"
            }
        }
    }
    if ($IncludeWorkflowHttp -and -not $workflowRan) { throw 'Full workflow HTTP suite did not run successfully.' }
    if (-not $recoveryRan) { throw 'MySQL recovery/crash suite did not run successfully.' }
    if (($IncludeAuthHttp -or $IncludeWorkflowHttp) -and -not $authRan) { throw 'Authentication HTTP suite did not run successfully.' }
    Write-Host "Tests: $total; passed: $($total - $skipped); skipped: $skipped. Reports copied to $run."
    if ($RetainedWorkflowClassPath) {
        Assert-TestInstance
        $probeOutput = Join-Path $run 'retained-workflow'
        $probe = Start-Process -FilePath (Get-Command java).Source -WindowStyle Hidden -PassThru -Wait `
            -ArgumentList "-cp `"$RetainedWorkflowClassPath`" DatingRetainedWorkflowProbe isolated `"$data`" `"$probeOutput`"" `
            -RedirectStandardOutput (Join-Path $run 'retained-probe.out.log') `
            -RedirectStandardError (Join-Path $run 'retained-probe.err.log')
        if ($probe.ExitCode -ne 0) {
            throw "Additive retained workflow probe failed in isolation; inspect $run\retained-probe.err.log. Business run must not proceed."
        }
        Get-Content -LiteralPath (Join-Path $run 'retained-probe.out.log') -Encoding UTF8 | Write-Host
    }
    # Compare every synthetic row before and after replay, ordered by each table's primary key.
    $keys = Invoke-TestSql @'
SELECT table_name,GROUP_CONCAT(column_name ORDER BY seq_in_index)
FROM information_schema.statistics
WHERE table_schema='dating_workflow_test' AND index_name='PRIMARY'
GROUP BY table_name ORDER BY table_name;
'@
    $dataSql = "USE dating_workflow_test;`n"
    foreach ($line in ($keys -split '\r?\n')) {
        $parts = $line -split "`t"
        if ($parts[0] -notmatch '^[a-z_]+$' -or $parts[1] -notmatch '^[a-z_,]+$') {
            throw 'Unexpected fixture identifier.'
        }
        $dataSql += "SELECT * FROM $($parts[0]) ORDER BY $($parts[1]);`n"
    }
    $beforeData = Invoke-TestSql $dataSql
    $beforeSchema = Invoke-TestSql $inventorySql
    # Recreate the legacy mismatch with populated follow-ups, preserving their comments.
    foreach ($table in @('dating_exception_followup', 'dating_exception_followup_history')) {
        Assert-TestInstance
        Invoke-TestSql @"
USE dating_workflow_test;
SET @fixture_comment=(SELECT column_comment FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='$table' AND column_name='handler');
SET @fixture_ddl=CONCAT('ALTER TABLE $table MODIFY COLUMN handler varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT ',QUOTE(@fixture_comment));
PREPARE fixture_stmt FROM @fixture_ddl;
EXECUTE fixture_stmt;
DEALLOCATE PREPARE fixture_stmt;
"@ | Out-Null
    }
    foreach ($migration in $migrations) {
        Assert-TestInstance
        $sql = Get-Content -LiteralPath (Join-Path $repo "sql/upgrade/$migration") -Raw -Encoding UTF8
        Invoke-TestSql ("USE dating_workflow_test;`n" + $sql) | Out-Null
    }
    if ($beforeData -cne (Invoke-TestSql $dataSql) -or $beforeSchema -cne (Invoke-TestSql $inventorySql)) {
        throw 'Populated migration replay changed fixture data or schema.'
    }
    Write-Host 'Populated migration replay passed: all synthetic rows and schema inventories unchanged.'
    if ($RetainedWorkflowClassPath) {
        Assert-TestInstance
        $dump = Join-Path $MySqlHome 'bin\mysqldump.exe'
        $oldLoginFile = $env:MYSQL_TEST_LOGIN_FILE
        try {
            $env:MYSQL_TEST_LOGIN_FILE = Join-Path $run 'no-login.cnf'
            $export = Start-Process -FilePath $dump -WindowStyle Hidden -PassThru -Wait `
                -ArgumentList "--no-defaults --protocol=TCP --host=127.0.0.1 --port=3307 --user=root --skip-password --default-character-set=utf8mb4 --single-transaction --quick --hex-blob --set-gtid-purged=OFF --no-tablespaces --result-file=`"$run\workflow-data.sql`" dating_workflow_test" `
                -RedirectStandardOutput (Join-Path $run 'export.out.log') `
                -RedirectStandardError (Join-Path $run 'export.err.log')
            if ($export.ExitCode -ne 0) { throw 'Isolated retained database export failed.' }
        }
        finally { $env:MYSQL_TEST_LOGIN_FILE = $oldLoginFile }
        Write-Host "Isolated schema and test rows exported: $run\workflow-data.sql"
    }
    Write-Host 'Regression passed. Test data/logs retained; the isolated instance will now stop.'
}
finally {
    $env:DATING_MYSQL_TEST_URL = $oldUrl
    $env:DATING_AUTH_HTTP_TEST = $oldAuth
    $env:DATING_WORKFLOW_HTTP_TEST = $oldWorkflow
    $env:DATING_HTTP_MYSQL_DATADIR = $oldData
    if ($null -ne $server -and -not $server.HasExited) {
        try {
            Assert-TestInstance
            Invoke-TestSql 'SHUTDOWN;' | Out-Null
            if (-not $server.WaitForExit(30000)) { throw 'Isolated MySQL shutdown timed out.' }
        }
        catch {
            # This process object belongs to this run, never an existing Windows MySQL service.
            if (-not $server.HasExited) { Stop-Process -InputObject $server }
            Write-Warning $_
        }
    }
}
