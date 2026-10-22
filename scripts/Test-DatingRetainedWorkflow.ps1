param(
    [Parameter(Mandatory = $true)][string]$Deployment,
    [string]$MySqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0',
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
$utf8 = New-Object Text.UTF8Encoding($false)
$prior = Get-Content -LiteralPath $Deployment -Raw -Encoding UTF8 | ConvertFrom-Json
$live = Get-CimInstance Win32_Process -Filter "ProcessId=$($prior.processId)"
if (!$prior.completed -or !$live -or $live.Name -ne 'java.exe' -or
    (Get-FileHash -LiteralPath $prior.artifact).Hash -cne $prior.deployedSha256) {
    throw 'Require a completed, current and unchanged local deployment.'
}
$expected = "`"$($live.ExecutablePath)`" -jar `"$($prior.artifact)`" --server.address=127.0.0.1 --server.port=8082 --dating.success.enabled=false `"--dating.attachments.root=$($prior.privateRoot)`""
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8082)
if ($live.CommandLine.Trim() -cne $expected -or $listeners.Count -ne 1 -or
    $listeners[0].OwningProcess -ne $live.ProcessId -or $listeners[0].LocalAddress -ne '127.0.0.1') {
    throw 'Live backend differs from the confirmed local deployment.'
}
if (Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$)' }) {
    throw 'Environment overrides require review first.'
}
$config = Join-Path $repo 'ruoyi-admin/src/main/resources/application-druid.yml'
$run = Join-Path $workspace ('deployment-records/dating-retained-workflow-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff'))
$inventory = Get-Content -LiteralPath (Join-Path (Split-Path $Deployment -Parent) 'inventory-after.json') -Raw -Encoding UTF8 | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'Test-DatingPhotoDirectory.ps1') -PrivateRoot $run -PublicProfile $inventory.publicProfile -Initialize | Out-Null
$lib = New-Item -ItemType Directory -Path (Join-Path $run 'lib')
$classpath = "$run;$lib\*"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($prior.artifact)
try {
    $reader = New-Object IO.StreamReader($zip.GetEntry('BOOT-INF/classes/application-druid.yml').Open(), $utf8)
    try { $packaged = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($packaged -cne [IO.File]::ReadAllText($config, $utf8)) {
        throw 'Datasource configuration differs from the current deployment.'
    }
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/[^/]+\.jar$' }) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $lib $entry.Name))
    }
}
finally { $zip.Dispose() }
$java = $live.ExecutablePath
$javac = Join-Path (Split-Path $java -Parent) 'javac.exe'
foreach ($file in @('DatingRetainedWorkflowProbe.java','DatingLocalMySql.java','Test-DatingMySql.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $run
}
Copy-Item -LiteralPath $PSCommandPath -Destination $run
& $javac -encoding UTF-8 -cp $classpath -d $run (Join-Path $PSScriptRoot 'DatingRetainedWorkflowProbe.java') `
    (Join-Path $PSScriptRoot 'DatingLocalMySql.java')
if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed. No database writes attempted. Evidence: $run" }
$manifest = [ordered]@{
    startedAt=(Get-Date).ToString('o'); deployment=$prior.artifact; deployedSha256=$prior.deployedSha256
    businessRequested=[bool]$Apply; businessStarted=$false; completed=$false
    type='Deployed transactional service verification; not browser or authenticated HTTP acceptance'
}
$oldApproval = $env:DATING_RETAINED_BUSINESS_APPROVED
$previousEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = $utf8
    Write-Host "Protected workflow evidence: $run"
    & (Join-Path $PSScriptRoot 'Test-DatingMySql.ps1') -MySqlHome $MySqlHome -IncludeWorkflowHttp `
        -RetainedWorkflowClassPath $classpath -ArtifactManifest (Join-Path $run 'isolated-location.json') `
        *>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath (Join-Path $run 'isolated-regression.log')
    $isolated = (Get-Content -LiteralPath (Join-Path $run 'isolated-location.json') -Raw -Encoding UTF8 | ConvertFrom-Json).directory
    $manifest.isolatedDirectory = $isolated
    $isolatedCopy = New-Item -ItemType Directory -Path (Join-Path $run 'isolated')
    foreach ($name in @('workflow-data.sql','maven.log','ruoyi-system-reports','ruoyi-admin-reports',
        'retained-workflow','retained-probe.out.log','retained-probe.err.log')) {
        Copy-Item -LiteralPath (Join-Path $isolated $name) -Destination $isolatedCopy -Recurse
    }
    $isolatedResult = Get-Content -LiteralPath (Join-Path $isolated 'retained-workflow/result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($isolatedResult.status -ne 'PASSED') { throw 'The deployed probe did not pass isolation.' }
    $manifest.isolatedChecks = $isolatedResult.checks.Count
    $manifest.isolatedDumpSha256 = (Get-FileHash -LiteralPath (Join-Path $isolatedCopy 'workflow-data.sql')).Hash
    if ($Apply) {
        & $java -cp $classpath DatingLocalMySql $config (Join-Path $MySqlHome 'bin') dump ry-vue (Join-Path $run 'before-workflow.sql')
        if ($LASTEXITCODE -ne 0) { throw 'Business backup failed; no business fixtures will be created.' }
        $manifest.beforeBackupSha256 = (Get-FileHash -LiteralPath (Join-Path $run 'before-workflow.sql')).Hash
        $env:DATING_RETAINED_BUSINESS_APPROVED = 'YES'
        $manifest.businessStarted = $true
        $businessOutput = Join-Path $run 'business'
        $probe = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -Wait `
            -ArgumentList "-cp `"$classpath`" DatingRetainedWorkflowProbe business `"$config`" `"$businessOutput`"" `
            -RedirectStandardOutput (Join-Path $run 'business-probe.out.log') `
            -RedirectStandardError (Join-Path $run 'business-probe.err.log')
        if ($probe.ExitCode -ne 0) { throw 'Business workflow check failed. Partial test records are retained; do not retry blindly.' }
        Get-Content -LiteralPath (Join-Path $run 'business-probe.out.log') -Encoding UTF8 | Write-Host
        $business = Get-Content -LiteralPath (Join-Path $run 'business/result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($business.status -ne 'PASSED') { throw 'Business probe result is not PASSED.' }
        $manifest.businessBatch = $business.batch
        $manifest.businessChecks = $business.checks.Count
    }
    $manifest.completed = $true
}
finally {
    $env:DATING_RETAINED_BUSINESS_APPROVED = $oldApproval
    if ($manifest.businessStarted) {
        & $java -cp $classpath DatingLocalMySql $config (Join-Path $MySqlHome 'bin') dump ry-vue (Join-Path $run 'after-workflow.sql')
        if ($LASTEXITCODE -eq 0) {
            $manifest.afterBackupSha256 = (Get-FileHash -LiteralPath (Join-Path $run 'after-workflow.sql')).Hash
        } else {
            $manifest.completed = $false
            Write-Warning 'Post-test backup failed; database records remain in place.'
        }
    }
    [Console]::OutputEncoding = $previousEncoding
    $manifest.finishedAt = (Get-Date).ToString('o')
    [IO.File]::WriteAllText((Join-Path $run 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6), $utf8)
    Write-Host "Workflow evidence retained: $run"
}
if (!$manifest.completed) { throw 'Retained workflow verification or evidence export is incomplete.' }
