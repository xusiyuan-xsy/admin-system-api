param(
    [Parameter(Mandatory = $true)][string]$PreviousDeployment,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
$utf8 = New-Object Text.UTF8Encoding($false)
$target = 'ry-vue'
$bin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$config = Join-Path $repo 'ruoyi-admin/src/main/resources/application-druid.yml'
$prior = Get-Content -LiteralPath $PreviousDeployment -Raw -Encoding UTF8 | ConvertFrom-Json
$old = Get-CimInstance Win32_Process -Filter "ProcessId=$($prior.processId)"
if (!$old -or $old.Name -ne 'java.exe' -or !$old.CommandLine.Contains($prior.artifact) -or
    (Get-FileHash -LiteralPath $prior.artifact).Hash -cne $prior.deployedSha256) {
    throw 'The manifest must identify the running, unchanged local backend.'
}
$java = $old.ExecutablePath
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$run = Join-Path $workspace "deployment-records\region-repair-$stamp"
$clone = "codex_dating_recovery_check_$stamp"
$inventory = Get-Content -LiteralPath (Join-Path (Split-Path $PreviousDeployment -Parent) 'inventory-before.json') -Raw -Encoding UTF8 | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'Test-DatingPhotoDirectory.ps1') -PrivateRoot $run -PublicProfile $inventory.publicProfile -Initialize | Out-Null

# Reuse reviewed query and comparison functions without running their deployment entry points.
$comparisonHelpers = @(Get-ChildItem -LiteralPath $workspace -Directory | ForEach-Object {
    Join-Path $_.FullName 'Invoke-DatingLocalUpgrade.ps1'
} | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
if ($comparisonHelpers.Count -ne 1) { throw 'Expected one existing local comparison helper.' }
foreach ($entry in @(
    @{ Path=(Join-Path $PSScriptRoot 'Deploy-DatingLocalRecovery.ps1'); Names=@('Query','Json-Rows') },
    @{ Path=$comparisonHelpers[0]; Names=@('Fingerprint','Assert-Preserved') }
)) {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($entry.Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Cannot parse existing database helpers.' }
    foreach ($name in $entry.Names) {
        $definition = @($ast.EndBlock.Statements | Where-Object {
            $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -eq $name
        })
        if ($definition.Count -ne 1) { throw "Missing helper: $name" }
        . ([scriptblock]::Create($definition[0].Extent.Text))
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($prior.artifact)
try {
    foreach ($entry in @($zip.Entries | Where-Object FullName -match '^BOOT-INF/lib/[^/]+\.jar$')) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $run $entry.Name))
    }
    $reader = New-Object IO.StreamReader($zip.GetEntry('BOOT-INF/classes/application-druid.yml').Open(), $utf8)
    try {
        if ($reader.ReadToEnd() -cne [IO.File]::ReadAllText($config, $utf8)) {
            throw 'Running artifact datasource differs from source configuration.'
        }
    } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
& (Join-Path (Split-Path $java -Parent) 'javac.exe') -encoding UTF-8 -cp "$run\*" -d $run `
    (Join-Path $PSScriptRoot 'DatingLocalMySql.java') (Join-Path $PSScriptRoot 'RegionQueryProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile local database probes.' }

function Save-Json([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $run $Name), ($Value | ConvertTo-Json -Depth 10), $utf8)
}
function Probe([string]$Database) {
    $output = & $java -cp "$run;$run\*" RegionQueryProbe $config $Database 2>&1
    [IO.File]::WriteAllText((Join-Path $run "$Database-probe.log"), ($output -join "`n"), $utf8)
    if ($LASTEXITCODE -ne 0) {
        throw 'Region mapper/service probe failed; see protected probe log.'
    }
    Write-Output ($output -join "`n")
}
function Assert-Regions([string]$Database) {
    $counts = Query $Database 'SELECT division_level,COUNT(*) FROM base_administrative_division WHERE country_code=''CN'' GROUP BY division_level ORDER BY division_level;'
    if ($counts -cne "1`t34`n2`t337`n3`t2846" -and $counts -cne "1`t34`r`n2`t337`r`n3`t2846") {
        throw "Unexpected seed counts: $counts"
    }
    $invalid = Query $Database @'
SELECT COUNT(*) FROM base_administrative_division d
WHERE (d.division_level=1 AND d.parent_id<>d.country_code)
OR (d.division_level>1 AND NOT EXISTS (
 SELECT 1 FROM base_administrative_division p
 WHERE p.country_code=d.country_code AND p.division_id=d.parent_id AND p.division_level<d.division_level));
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='base_administrative_division' AND column_comment='';
'@
    if (($invalid -split '\r?\n' | Where-Object { $_ -ne '0' }).Count) { throw 'Invalid hierarchy or missing column comments.' }
    Probe $Database
}
$report = [ordered]@{
    target='127.0.0.1:3306/ry-vue'; startedAt=(Get-Date).ToString('o')
    backup=$null; rehearsal=$false; applied=$false; verified=$false
    backendRestarted=$false; authenticatedBrowserAcceptance='Not performed'
}
$cloneCreated = $false
try {
    if ([int](Query $target "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='base_administrative_division';") -ne 0) {
        throw 'Region table already exists; inspect before running this missing-table repair.'
    }
    if ([int](Query $target @'
SELECT (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())
+ (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND (table_type<>'BASE TABLE' OR engine<>'InnoDB'));
'@) -ne 0) { throw 'Backup requires a reviewed InnoDB-only schema without database programs.' }
    $backup = Join-Path $run 'before-regions.sql'
    & $java -cp "$run;$run\*" DatingLocalMySql $config $bin dump $target $backup
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed.' }
    $dumpText = [IO.File]::ReadAllText($backup, $utf8)
    if ($dumpText -notmatch '-- Dump completed on' -or $dumpText -match '(?im)^\s*(USE |CREATE DATABASE)') {
        throw 'Backup completeness or isolation check failed.'
    }
    $report.backup = $backup
    $report.backupSha256 = (Get-FileHash -LiteralPath $backup).Hash
    Query $target "CREATE DATABASE ``$clone`` CHARACTER SET utf8mb4;" | Out-Null
    $cloneCreated = $true
    Query $clone $dumpText | Out-Null
    $columns = @(Json-Rows $clone "SELECT JSON_OBJECT('table',table_name,'column',column_name,'type',data_type) FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position;")
    $definitions = @($columns | Group-Object table | ForEach-Object {
        $names = @($_.Group | ForEach-Object {
            $name = '`' + $_.column.Replace('`','``') + '`'
            if ($_.type -match 'blob|binary|^bit$') { "HEX($name)" } else { $name }
        })
        [pscustomobject]@{ table=$_.Name; projection=($names -join ','); filter='' }
    })
    $before = @(Fingerprint $clone $definitions)
    Save-Json 'backup-row-fingerprints.json' $before
    $script = Join-Path $repo 'sql/china_administrative_division.sql'
    $sql = [IO.File]::ReadAllText($script, $utf8)
    Copy-Item -LiteralPath $script -Destination (Join-Path $run 'china_administrative_division.sql')
    $report.scriptSha256 = (Get-FileHash -LiteralPath $script).Hash
    Query $clone $sql | Out-Null
    Assert-Regions $clone
    $regionDefinition = @([pscustomobject]@{
        table='base_administrative_division'; filter=''
        projection='id,division_id,parent_id,country_code,division_level,name_en,name_local,local_lang,postal_code,sort_order'
    })
    $regions = @(Fingerprint $clone $regionDefinition)
    Query $clone $sql | Out-Null
    Assert-Preserved $regions @(Fingerprint $clone $regionDefinition)
    Assert-Preserved $before @(Fingerprint $clone $definitions)
    # Only the disposable clone receives test edits, exercising non-destructive re-runs.
    Query $clone "UPDATE base_administrative_division SET name_local='Preserved custom label' WHERE country_code='CN' AND division_id='110000';" | Out-Null
    Query $clone $sql | Out-Null
    if ((Query $clone "SELECT name_local FROM base_administrative_division WHERE country_code='CN' AND division_id='110000';") -cne 'Preserved custom label') {
        throw 'Re-running seed overwrote an existing region label.'
    }
    $report.rehearsal = $true
    $report.originalTablesPreservedInRehearsal = $definitions.Count
    Write-Output 'Backup restored; repeated seed, hierarchy, comments, custom-label preservation and original-table preservation passed.'
    if ($Apply) {
        # Runtime logs may change independently; compare every other existing table immediately around repair.
        $stable = @($definitions | Where-Object table -notin @('sys_oper_log','sys_logininfor','dating_recovery_run'))
        $liveBefore = @(Fingerprint $target $stable)
        Save-Json 'live-before-fingerprints.json' $liveBefore
        if ([int](Query $target "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='base_administrative_division';") -ne 0) {
            throw 'Region table appeared during rehearsal; refusing to merge without inspection.'
        }
        Query $target $sql | Out-Null
        $report.applied = $true
        Assert-Regions $target
        Assert-Preserved $regions @(Fingerprint $target $regionDefinition)
        Assert-Preserved $liveBefore @(Fingerprint $target $stable)
        $report.liveStableTablesPreserved = $stable.Count
        $report.verified = $true
        Write-Output 'Local database repaired and read-only deployed mapper/service checks passed. Backend was not restarted.'
    }
} finally {
    if ($cloneCreated -and $clone -cmatch '^codex_dating_recovery_check_[0-9]{8}_[0-9]{6}_[0-9]{3}$') {
        Query $target "DROP DATABASE ``$clone``;" | Out-Null
        $report.cloneRemoved = $true
    }
    $report.finishedAt = (Get-Date).ToString('o')
    Save-Json 'repair.json' $report
    Write-Output "Protected repair record: $run"
}
