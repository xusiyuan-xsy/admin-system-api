param(
    [Parameter(Mandatory = $true)][int]$ExpectedProcessId,
    [Parameter(Mandatory = $true)][string]$PreviousDeployment,
    [ValidateSet('Recovery','RetryReason','Followup')][string]$Upgrade = 'Recovery',
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
$utf8 = New-Object Text.UTF8Encoding($false)
$target = 'ry-vue'
$bin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$config = Join-Path $repo 'ruoyi-admin/src/main/resources/application-druid.yml'
$migrations = @()
if ($Upgrade -eq 'Recovery') { $migrations += Join-Path $repo 'sql/upgrade/20261016_dating_recovery.sql' }
if ($Upgrade -eq 'Followup') {
    $migrations += Join-Path $repo 'sql/upgrade/20261016_dating_exception_followup.sql'
    $migrations += Join-Path $repo 'sql/upgrade/20261016_dating_followup_collation.sql'
} else {
    $migrations += Join-Path $repo 'sql/upgrade/20261016_dating_retry_reason.sql'
}
$jar = Join-Path $repo 'ruoyi-admin/target/ruoyi-admin.jar'
$prior = Get-Content -LiteralPath $PreviousDeployment -Raw -Encoding UTF8 | ConvertFrom-Json
$privateRoot = $prior.privateRoot
$old = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedProcessId"
if (!$old -or $old.Name -ne 'java.exe' -or $prior.processId -ne $ExpectedProcessId) {
    throw 'Previous deployment and expected live Java process must agree.'
}
$java = $old.ExecutablePath
$expectedCommand = "`"$java`" -jar `"$($prior.artifact)`" --server.address=127.0.0.1 --server.port=8082 --dating.success.enabled=false `"--dating.attachments.root=$privateRoot`""
if ($old.CommandLine.Trim() -cne $expectedCommand) { throw 'Unexpected existing backend command.' }
$owner = Invoke-CimMethod -InputObject $old -MethodName GetOwnerSid
if ($owner.ReturnValue -ne 0 -or $owner.Sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
    throw 'Backend must belong to the deployment user.'
}
if ((Get-FileHash -LiteralPath $prior.artifact).Hash -cne $prior.deployedSha256) {
    throw 'Previous deployed artifact hash changed.'
}
if (Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING_|DATING_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|JAVA_OPTS$)' }) {
    throw 'Environment overrides require explicit review before deployment.'
}
function Assert-OldProcess {
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedProcessId"
    $listeners = @(Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue)
    if (!$current -or $current.CreationDate -ne $old.CreationDate -or
        $current.CommandLine -cne $old.CommandLine -or $listeners.Count -ne 1 -or
        $listeners[0].OwningProcess -ne $ExpectedProcessId -or $listeners[0].LocalAddress -ne '127.0.0.1') {
        throw 'Original backend identity or listener changed.'
    }
}
Assert-OldProcess
$inventoryScript = Join-Path $PSScriptRoot 'Test-DatingPhotoInventory.ps1'
$directoryScript = Join-Path $PSScriptRoot 'Test-DatingPhotoDirectory.ps1'
$beforeInventory = (& $inventoryScript -PrivateRoot $privateRoot -Jar $jar) | ConvertFrom-Json
foreach ($field in @('readyDeductionTasks','errorDeductionTasks','enabledQuartzJobs','otherActiveTransactions')) {
    if ($beforeInventory.$field -ne 0) { throw "Maintenance requires an idle system: $field" }
}
& $directoryScript -PrivateRoot $privateRoot -PublicProfile $beforeInventory.publicProfile | Out-Null
Write-Output 'Preflight: inspected local backend, idle tasks, existing private storage.'
if (!$Apply) { return }

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$run = Join-Path $workspace "deployment-records\dating-$($Upgrade.ToLowerInvariant())-$stamp"
& $directoryScript -PrivateRoot $run -PublicProfile $beforeInventory.publicProfile -Initialize | Out-Null
Write-Host "Deployment artifacts: $run"
function Save-Json([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $run $Name), ($Value | ConvertTo-Json -Depth 12), $utf8)
}
Save-Json 'inventory-before.json' $beforeInventory
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-JarEntry([string]$Artifact, [string]$Entry) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Artifact)
    try {
        $entryFile = $zip.GetEntry($Entry)
        if (!$entryFile) { throw "Missing packaged resource: $Entry" }
        $reader = New-Object IO.StreamReader($entryFile.Open(), $utf8)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}
$report = [ordered]@{
    target='127.0.0.1:3306/ry-vue'; startedAt=(Get-Date).ToString('o')
    previousProcessId=$ExpectedProcessId; previousArtifact=$prior.artifact
    privateRoot=$privateRoot; successEnabled=$false; databaseMigrationExecuted=$false
    authenticatedBusinessAcceptance='Not performed'; completed=$false
}
$newProcess = $null
$stopped = $false
$cloneCreated = $false
$clone = "codex_dating_recovery_check_$stamp"

function Query([string]$Database, [string]$Sql) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $java
    $info.Arguments = "-cp `"$run;$run\*`" DatingLocalMySql `"$config`" `"$bin`" query $Database -"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $process = [Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $writer = New-Object IO.StreamWriter($process.StandardInput.BaseStream, $utf8)
        $writer.WriteLine($Sql)
        $writer.Close()
        if (!$process.WaitForExit(120000)) { throw 'Local database query timed out; inspect before retrying.' }
        $text = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            [IO.File]::AppendAllText((Join-Path $run 'database-errors.log'), $errorText, $utf8)
            throw 'Local database query failed; inspect protected database-errors.log.'
        }
        return $text.TrimEnd([char[]]"`r`n")
    } finally { $process.Dispose() }
}
function Json-Rows([string]$Database, [string]$Sql) {
    $text = Query $Database $Sql
    if ($text) { $text -split '\r?\n' | ForEach-Object { $_ | ConvertFrom-Json } }
}
# Import only the existing read-only comparison helpers, not an earlier upgrade entry point.
$tokens = $null
$parseErrors = $null
$helperFiles = @(Get-ChildItem -LiteralPath $workspace -Directory | ForEach-Object {
    Join-Path $_.FullName 'Invoke-DatingLocalUpgrade.ps1'
} | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
if ($helperFiles.Count -ne 1) { throw 'Expected one existing local upgrade helper script.' }
$helpers = [Management.Automation.Language.Parser]::ParseFile(
    $helperFiles[0], [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Cannot parse existing comparison helpers.' }
foreach ($name in @('Fingerprint','Assert-Preserved','Schema')) {
    $definition = @($helpers.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -eq $name
    })
    if ($definition.Count -ne 1) { throw "Missing comparison helper $name" }
    . ([scriptblock]::Create($definition[0].Extent.Text))
}
function Assert-IdleDatabase {
    if ([int](Query $target 'SELECT COUNT(*) FROM information_schema.innodb_trx;') -ne 0 -or
        [int](Query $target "SELECT COUNT(*) FROM information_schema.processlist WHERE DB=DATABASE() AND ID<>CONNECTION_ID() AND COMMAND<>'Sleep';") -ne 0) {
        throw 'Concurrent database activity detected; stop and review.'
    }
}
function Start-Backend([string]$Artifact, [string]$Prefix) {
    if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8082 is occupied; refusing to stop another process.'
    }
    Start-Process -FilePath $java -ArgumentList @('-jar', "`"$Artifact`"",
        '--server.address=127.0.0.1','--server.port=8082','--dating.success.enabled=false',
        "`"--dating.attachments.root=$privateRoot`"") -WorkingDirectory $repo -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput (Join-Path $run "$Prefix.out.log") `
        -RedirectStandardError (Join-Path $run "$Prefix.err.log")
}
function Wait-Backend($Process) {
    for ($i=0; $i -lt 90; $i++) {
        $Process.Refresh()
        if ($Process.HasExited) { throw 'Backend exited during startup.' }
        $listeners = @(Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue)
        if (@($listeners | Where-Object OwningProcess -ne $Process.Id).Count) {
            throw 'Another process acquired port 8082.'
        }
        try {
            $response = Invoke-RestMethod 'http://127.0.0.1:8082/captchaImage' -TimeoutSec 2
            if ($listeners.Count -eq 1 -and $listeners[0].LocalAddress -eq '127.0.0.1' -and $response.code -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw 'Backend readiness timeout.'
}
function Recovery-Schema([string]$Database) {
    Query $Database @'
SELECT JSON_ARRAY(table_name,engine,table_collation,table_comment)
FROM information_schema.tables WHERE table_schema=DATABASE()
AND table_name IN ('dating_recovery_run','dating_deduction_history','dating_exception_followup','dating_exception_followup_history') ORDER BY table_name;
SELECT JSON_ARRAY(table_name,column_name,column_type,is_nullable,column_default,extra,column_comment)
FROM information_schema.columns WHERE table_schema=DATABASE()
AND table_name IN ('dating_recovery_run','dating_deduction_history','dating_exception_followup','dating_exception_followup_history') ORDER BY table_name,ordinal_position;
SELECT JSON_ARRAY(table_name,index_name,seq_in_index,column_name,non_unique)
FROM information_schema.statistics WHERE table_schema=DATABASE()
AND table_name IN ('dating_recovery_run','dating_deduction_history','dating_exception_followup','dating_exception_followup_history') ORDER BY table_name,index_name,seq_in_index;
SELECT JSON_ARRAY(constraint_name,check_clause) FROM information_schema.check_constraints
WHERE constraint_schema=DATABASE() AND constraint_name IN ('ck_recovery_status','ck_deduction_history_action',
 'ck_exception_followup_status','ck_exception_followup_owner','ck_exception_followup_version','ck_exception_followup_action')
ORDER BY constraint_name;
'@
}
try {
    # Build before the maintenance outage; the live backend uses an independent artifact.
    Push-Location $repo
    try {
        & mvn -q -pl ruoyi-admin -am package --log-file (Join-Path $run 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
    $artifact = Join-Path $run 'backend.jar'
    $backupArtifact = Join-Path $run 'previous-backend.jar'
    Copy-Item -LiteralPath $jar -Destination $artifact
    Copy-Item -LiteralPath $prior.artifact -Destination $backupArtifact
    if ((Get-FileHash $backupArtifact).Hash -cne $prior.deployedSha256) { throw 'Application backup hash mismatch.' }
    $report.previousSha256 = $prior.deployedSha256
    $report.deployedSha256 = (Get-FileHash $artifact).Hash
    $report.artifact = $artifact
    $report.previousArtifactBackup = $backupArtifact
    $sourceConfig = [IO.File]::ReadAllText($config, $utf8)
    if ((Read-JarEntry $artifact 'BOOT-INF/classes/application-druid.yml') -cne $sourceConfig -or
        (Read-JarEntry $prior.artifact 'BOOT-INF/classes/application-druid.yml') -cne $sourceConfig) {
        throw 'Packaged datasource configuration differs from the inspected source.'
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $yaml = @($zip.Entries | Where-Object FullName -match '^BOOT-INF/lib/snakeyaml-[0-9][^/]*\.jar$')
        if ($yaml.Count -ne 1) { throw 'Expected one packaged YAML parser.' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($yaml[0], (Join-Path $run $yaml[0].Name))
    } finally { $zip.Dispose() }
    & (Join-Path (Split-Path $java -Parent) 'javac.exe') -encoding UTF-8 -cp "$run\*" -d $run (Join-Path $PSScriptRoot 'DatingLocalMySql.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile database deployment bridge.' }
    $identity = @(Json-Rows $target "SELECT JSON_OBJECT('port',@@port,'database',DATABASE(),'version',VERSION(),'datadir',@@datadir);")[0]
    if ($identity.port -ne 3306 -or $identity.database -ne $target -or $identity.version -notmatch '^8\.0\.') {
        throw 'Unexpected local database identity.'
    }
    $report.databaseIdentity = $identity
    $expectedTables = if ($Upgrade -eq 'Recovery') { 17 } else { 19 }
    if ([int](Query $target "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,7)='dating_';") -ne $expectedTables) {
        throw "Expected exactly $expectedTables pre-upgrade dating tables; inspect migrations before retrying."
    }
    $expectedReason = if ($Upgrade -eq 'Followup') { 1 } else { 0 }
    if ([int](Query $target "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_deduction_history' AND column_name='reason';") -ne $expectedReason) {
        throw 'Unexpected retry reason migration state; this is a one-time deployment entry point.'
    }
    if ($Upgrade -eq 'Followup' -and (
        [int](Query $target "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('dating_exception_followup','dating_exception_followup_history');") -ne 0 -or
        [int](Query $target "SELECT COUNT(*) FROM sys_menu WHERE perms='dating:exception:handle';") -ne 0)) {
        throw 'Followup schema or permission is already present; inspect before retrying.'
    }
    if ([int](Query $target @'
SELECT (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND (table_type<>'BASE TABLE' OR engine<>'InnoDB'));
'@) -ne 0) { throw 'Views, routines, triggers, events or non-InnoDB tables require separate backup review.' }
    $expectedPermission = if ($Upgrade -eq 'Recovery') { 0 } else { 1 }
    if ([int](Query $target "SELECT COUNT(*) FROM sys_menu WHERE component='dating/matches/index' AND menu_type='C' AND status='0';") -ne 1 -or
        [int](Query $target "SELECT COUNT(*) FROM sys_menu WHERE perms='dating:recovery:query';") -ne $expectedPermission) {
        throw 'Unexpected recovery permission or match menu state.'
    }
    Assert-OldProcess
    $freshInventory = (& $inventoryScript -PrivateRoot $privateRoot -Jar $artifact) | ConvertFrom-Json
    foreach ($field in @('readyDeductionTasks','errorDeductionTasks','enabledQuartzJobs','otherActiveTransactions')) {
        if ($freshInventory.$field -ne 0) { throw "Maintenance no longer idle: $field" }
    }
    Stop-Process -Id $ExpectedProcessId
    $stopped = $true
    Wait-Process -Id $ExpectedProcessId -Timeout 20 -ErrorAction SilentlyContinue
    Assert-IdleDatabase
    $tables = @(Json-Rows $target "SELECT JSON_OBJECT('name',table_name) FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name;")
    $columns = @(Json-Rows $target "SELECT JSON_OBJECT('table',table_name,'column',column_name,'type',data_type) FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position;")
    $menuIds = (Query $target 'SELECT menu_id FROM sys_menu ORDER BY menu_id;') -split '\r?\n'
    $definitions = @()
    foreach ($table in $tables) {
        $names = @($columns | Where-Object table -eq $table.name | ForEach-Object {
            $name = '`' + $_.column.Replace('`','``') + '`'
            if ($_.type -match 'blob|binary|^bit$') { "HEX($name)" } else { $name }
        })
        $filter = ''
        if ($table.name -eq 'sys_menu') { $filter = ' WHERE menu_id IN (' + ($menuIds -join ',') + ')' }
        $definitions += [pscustomobject]@{table=$table.name; projection=($names -join ','); filter=$filter}
    }
    $before = @(Fingerprint $target $definitions)
    $beforeSchema = Schema $target
    Save-Json 'original-row-fingerprints.json' $before
    $dump = Join-Path $run 'before-recovery.sql'
    & $java -cp "$run;$run\*" DatingLocalMySql $config $bin dump $target $dump
    if ($LASTEXITCODE -ne 0) { throw 'Database backup failed.' }
    $dumpText = [IO.File]::ReadAllText($dump, $utf8)
    if ($dumpText -notmatch '-- Dump completed on' -or $dumpText -match '(?im)^\s*(USE |CREATE DATABASE)') {
        throw 'Database backup completeness/isolation check failed.'
    }
    $report.backup = $dump
    $report.backupSha256 = (Get-FileHash $dump).Hash
    Assert-Preserved $before @(Fingerprint $target $definitions)
    $dbInfo = @(Json-Rows $target "SELECT JSON_OBJECT('charset',default_character_set_name,'collation',default_collation_name) FROM information_schema.schemata WHERE schema_name=DATABASE();")[0]
    if ($dbInfo.charset -notmatch '^\w+$' -or $dbInfo.collation -notmatch '^\w+$') { throw 'Invalid database metadata.' }
    Query $target ("CREATE DATABASE ``$clone`` CHARACTER SET " + $dbInfo.charset + ' COLLATE ' + $dbInfo.collation + ';') | Out-Null
    $cloneCreated = $true
    Query $clone $dumpText | Out-Null
    Assert-Preserved $before @(Fingerprint $clone $definitions)
    if ((Schema $clone) -cne $beforeSchema) { throw 'Restored backup schema differs.' }
    $epoch = Query $target 'SELECT UNIX_TIMESTAMP();'
    if ($epoch -notmatch '^\d+$') { throw 'Invalid migration timestamp.' }
    $sql = "SET timestamp=$epoch;`n"
    $report.migrations = @($migrations | ForEach-Object {
        $sql += [IO.File]::ReadAllText($_, $utf8) + "`n"
        Copy-Item -LiteralPath $_ -Destination $run
        [ordered]@{ path=$_; sha256=(Get-FileHash $_).Hash }
    })
    Query $clone $sql | Out-Null
    $expectedSchema = Schema $clone
    $expectedRecovery = Recovery-Schema $clone
    $expectedMenus = Query $clone 'SELECT JSON_ARRAY(menu_id,parent_id,menu_name,perms,create_time) FROM sys_menu ORDER BY menu_id;'
    Query $clone $sql | Out-Null
    if ((Schema $clone) -cne $expectedSchema -or (Recovery-Schema $clone) -cne $expectedRecovery -or
        (Query $clone 'SELECT JSON_ARRAY(menu_id,parent_id,menu_name,perms,create_time) FROM sys_menu ORDER BY menu_id;') -cne $expectedMenus) {
        throw 'Repeated migration changed schema or menu records.'
    }
    Assert-Preserved $before @(Fingerprint $clone $definitions)
    $report.backupRestoreVerified = $true
    $report.migrationReplayVerified = $true
    Write-Output 'PASS: full backup restored; migration replay and original row fingerprints verified.'
    Assert-IdleDatabase
    Assert-Preserved $before @(Fingerprint $target $definitions)
    Query $target $sql | Out-Null
    $report.databaseMigrationExecuted = $true
    if ((Schema $target) -cne $expectedSchema -or (Recovery-Schema $target) -cne $expectedRecovery -or
        (Query $target 'SELECT JSON_ARRAY(menu_id,parent_id,menu_name,perms,create_time) FROM sys_menu ORDER BY menu_id;') -cne $expectedMenus) {
        throw 'Applied schema or permission differs from rehearsal.'
    }
    Assert-Preserved $before @(Fingerprint $target $definitions)
    $commentTables = if ($Upgrade -eq 'Followup') {
        "'dating_exception_followup','dating_exception_followup_history'"
    } else { "'dating_recovery_run','dating_deduction_history'" }
    $expectedColumns = if ($Upgrade -eq 'Followup') { 17 } else { 18 }
    $commentCounts = @(Json-Rows $target @"
SELECT JSON_OBJECT('tables',COUNT(*),'missing',SUM(table_comment='')) FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN ($commentTables);
SELECT JSON_OBJECT('columns',COUNT(*),'missing',SUM(column_comment='')) FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name IN ($commentTables);
"@)
    if ($commentCounts[0].tables -ne 2 -or $commentCounts[0].missing -ne 0 -or
        $commentCounts[1].columns -ne $expectedColumns -or $commentCounts[1].missing -ne 0) {
        throw 'New table or column comments are incomplete.'
    }
    $report.commentCounts = $commentCounts
    if ($Upgrade -eq 'Followup') {
        if ([int](Query $target 'SELECT (SELECT COUNT(*) FROM dating_exception_followup)+(SELECT COUNT(*) FROM dating_exception_followup_history);') -ne 0 -or
            [int](Query $target "SELECT COUNT(*) FROM sys_menu WHERE perms='dating:exception:handle';") -ne 1 -or
            [int](Query $target "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,7)='dating_';") -ne 21) {
            throw 'Unexpected post-upgrade followup schema, permission or business rows.'
        }
    }
    $report.originalTablesPreserved = $tables.Count
    $report.roleGrantsUnchanged = $true
    Write-Output "PASS: migration applied; $($tables.Count) original tables preserved; new comments verified."
    Query $target "DROP DATABASE ``$clone``;" | Out-Null
    $cloneCreated = $false
    $report.temporaryDatabaseRemoved = $true
    $heartbeatEpoch = Query $target 'SELECT UNIX_TIMESTAMP();'
    if ($heartbeatEpoch -notmatch '^\d+$') { throw 'Invalid heartbeat timestamp.' }
    $newProcess = Start-Backend $artifact 'backend'
    $report.processId = $newProcess.Id
    Wait-Backend $newProcess
    $smoke = Join-Path $PSScriptRoot 'Test-DatingLocalApi.ps1'
    $followupArgs = @()
    if ($Upgrade -eq 'Followup') { $followupArgs += '-VerifyFollowup' }
    & powershell.exe -NoProfile -File $smoke -VerifyPhotos -VerifyRecovery @followupArgs *> (Join-Path $run 'api-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw 'Backend smoke checks failed.' }
    & powershell.exe -NoProfile -File $smoke -BaseUri 'http://127.0.0.1:5174/dev-api/' -VerifyPhotos -VerifyRecovery @followupArgs *> (Join-Path $run 'proxy-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw 'Frontend proxy smoke checks failed.' }
    $heartbeat = $null
    for ($i=0; $i -lt 90; $i++) {
        $newProcess.Refresh()
        if ($newProcess.HasExited) { throw 'New backend exited while awaiting recovery heartbeat.' }
        $heartbeat = @(Json-Rows $target @"
SELECT JSON_OBJECT('runs',COUNT(*),'instances',COUNT(DISTINCT instance_id),
 'successful',COALESCE(SUM(status='SUCCEEDED'),0),'scanned',COALESCE(SUM(scanned),0),
 'failed',COALESCE(SUM(failed),0),'recordFailures',COALESCE(SUM(record_failures),0),
 'latest',MAX(finished_at)) FROM dating_recovery_run WHERE started_at>=FROM_UNIXTIME($heartbeatEpoch);
"@)[0]
        if ($heartbeat.runs -ge 2) { break }
        Start-Sleep -Seconds 1
    }
    if ($heartbeat.runs -lt 2 -or $heartbeat.instances -ne 1 -or $heartbeat.successful -ne $heartbeat.runs -or
        $heartbeat.scanned -ne 0 -or $heartbeat.failed -ne 0 -or $heartbeat.recordFailures -ne 0) {
        throw 'Expected two successful idle recovery heartbeats from the new instance.'
    }
    $report.heartbeat = $heartbeat
    $afterInventory = (& $inventoryScript -PrivateRoot $privateRoot -Jar $artifact) | ConvertFrom-Json
    Save-Json 'inventory-after.json' $afterInventory
    if ($afterInventory.photoBindingsSha256 -cne $beforeInventory.photoBindingsSha256) {
        throw 'Photo bindings changed during deployment.'
    }
    # Startup can legitimately append application operation logs, but never business rows or role grants.
    $protectedDefinitions = @($definitions | Where-Object { $_.table -notin @('sys_oper_log','sys_logininfor') })
    foreach ($definition in $protectedDefinitions) {
        if ($definition.table -eq 'dating_recovery_run') {
            $definition.filter = " WHERE started_at<FROM_UNIXTIME($heartbeatEpoch)"
        }
    }
    Assert-Preserved @($before | Where-Object { $_.table -notin @('sys_oper_log','sys_logininfor') }) @(Fingerprint $target $protectedDefinitions)
    $report.protectedTablesAfterStartup = $protectedDefinitions.Count
    $report.photoBindingsUnchanged = $true
    $report.completed = $true
    $report.completedAt = (Get-Date).ToString('o')
    Write-Output "PASS: backend PID $($newProcess.Id); direct/proxy checks and two real scheduler heartbeats passed."
} catch {
    $report.error = $_.Exception.Message
    if ($stopped -and !$report.completed) {
        if ($newProcess) {
            $newProcess.Refresh()
            if (!$newProcess.HasExited) {
                Stop-Process -Id $newProcess.Id
                Wait-Process -Id $newProcess.Id -Timeout 20 -ErrorAction SilentlyContinue
            }
        }
        $restored = Start-Backend $backupArtifact 'rollback'
        Wait-Backend $restored
        $report.rollbackProcessId = $restored.Id
        $report.applicationRolledBack = $true
        Write-Warning "Restored previous application PID $($restored.Id); additive database changes are retained."
    }
    throw
} finally {
    try {
        if ($cloneCreated) {
            if ($clone -notmatch '^codex_dating_recovery_check_\d{8}_\d{6}_\d{3}$' -or $clone -eq $target) {
                throw 'Unsafe temporary database cleanup name.'
            }
            Query $target "DROP DATABASE ``$clone``;" | Out-Null
            $report.temporaryDatabaseRemoved = $true
        }
    } finally {
        Save-Json 'deployment.json' $report
        Write-Output "Deployment report: $run"
    }
}
