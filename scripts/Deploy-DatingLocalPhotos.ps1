param(
    [Parameter(Mandatory = $true)][int]$ExpectedProcessId,
    [Parameter(Mandatory = $true)][string]$PrivateRoot
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
$jar = Join-Path $repo 'ruoyi-admin/target/ruoyi-admin.jar'
$inventoryScript = Join-Path $PSScriptRoot 'Test-DatingPhotoInventory.ps1'
$directoryScript = Join-Path $PSScriptRoot 'Test-DatingPhotoDirectory.ps1'
$smokeScript = Join-Path $PSScriptRoot 'Test-DatingLocalApi.ps1'
$utf8 = New-Object Text.UTF8Encoding($false)
$oldProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedProcessId"
if (!$oldProcess -or $oldProcess.Name -ne 'java.exe') { throw 'Expected Java backend is not running.' }
$java = $oldProcess.ExecutablePath
$expectedCommand = "`"$java`" -jar ruoyi-admin/target/ruoyi-admin.jar --server.address=127.0.0.1 --server.port=8082 --dating.success.enabled=false"
if ($oldProcess.CommandLine -cne $expectedCommand) {
    throw 'Existing command differs from the inspected pre-photo deployment; review it manually.'
}
$owner = Invoke-CimMethod -InputObject $oldProcess -MethodName GetOwnerSid
if ($owner.ReturnValue -ne 0 -or
    $owner.Sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
    throw 'Backend must run as the current deployment user.'
}
$listener = @(Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction Stop)
if ($listener.Count -ne 1 -or $listener[0].OwningProcess -ne $ExpectedProcessId -or
    $listener[0].LocalAddress -ne '127.0.0.1') { throw 'Unexpected 8082 listener.' }

$before = (& $inventoryScript -PrivateRoot $PrivateRoot -Jar $jar) | ConvertFrom-Json
foreach ($field in @('readyDeductionTasks', 'errorDeductionTasks', 'enabledQuartzJobs', 'otherActiveTransactions')) {
    if ($before.$field -ne 0) { throw "Deployment paused: $field is nonzero." }
}
if ($before.counts.missingOrUnsafePrivateFiles -or $before.counts.invalidPrivate) {
    throw 'Existing private references need investigation before changing storage.'
}
& $directoryScript -PrivateRoot $PrivateRoot -PublicProfile $before.publicProfile | Out-Null
$run = Join-Path $workspace ('deployment-records\dating-photos-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
& $directoryScript -PrivateRoot $run -PublicProfile $before.publicProfile -Initialize | Out-Null
Write-Host "Deployment artifacts: $run"
[IO.File]::WriteAllText((Join-Path $run 'inventory-before.json'), ($before | ConvertTo-Json -Depth 10), $utf8)
$backup = Join-Path $run 'previous-backend.jar'
Copy-Item -LiteralPath $jar -Destination $backup
$oldHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -cne $oldHash) {
    throw 'Previous artifact backup checksum mismatch.'
}

function Start-Backend([string]$Artifact, [string]$LogPrefix) {
    if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8082 is occupied; refusing to stop an unknown process.'
    }
    $arguments = @('-jar', "`"$Artifact`"", '--server.address=127.0.0.1', '--server.port=8082',
        '--dating.success.enabled=false', "`"--dating.attachments.root=$PrivateRoot`"")
    return Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden `
        -PassThru -RedirectStandardOutput (Join-Path $run "$LogPrefix.out.log") `
        -RedirectStandardError (Join-Path $run "$LogPrefix.err.log")
}

function Wait-Backend($Process) {
    for ($i = 0; $i -lt 90; $i++) {
        if ($Process.HasExited) { throw 'Backend exited during startup; review the protected deployment logs.' }
        $ports = @(Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue)
        if ($ports.Count -gt 0 -and @($ports | Where-Object OwningProcess -ne $Process.Id).Count) {
            throw 'Another process acquired 8082 during startup.'
        }
        try {
            $response = Invoke-RestMethod -Uri 'http://127.0.0.1:8082/captchaImage' -TimeoutSec 2
            if ($ports.Count -eq 1 -and $response.code -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw 'Backend did not become ready within the startup window.'
}

$newProcess = $null
$stopped = $false
$verified = $false
$oldAuth = $env:DATING_AUTH_HTTP_TEST
$oldMySql = $env:DATING_MYSQL_TEST_URL
try {
    # Recheck the process immediately before stopping this exact local instance.
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedProcessId"
    if (!$current -or $current.CreationDate -ne $oldProcess.CreationDate -or
        $current.CommandLine -cne $expectedCommand) { throw 'Backend identity changed; refusing to stop it.' }
    Stop-Process -Id $ExpectedProcessId -ErrorAction Stop
    $stopped = $true
    Wait-Process -Id $ExpectedProcessId -Timeout 20 -ErrorAction SilentlyContinue
    $env:DATING_AUTH_HTTP_TEST = 'true'
    $env:DATING_MYSQL_TEST_URL = $null
    Push-Location $repo
    try {
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & mvn -q -pl ruoyi-admin -am package *> (Join-Path $run 'build-console.log')
        $buildCode = $LASTEXITCODE
        $ErrorActionPreference = $savedPreference
        if ($buildCode -ne 0) { throw 'Backend build failed; see build-console.log.' }
    } finally { Pop-Location }
    $artifact = Join-Path $run 'backend.jar'
    Copy-Item -LiteralPath $jar -Destination $artifact
    $newHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    if ($newHash -cne (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash) {
        throw 'New deployment artifact checksum mismatch.'
    }
    $newProcess = Start-Backend $artifact 'backend'
    Wait-Backend $newProcess
    # Run in a child PowerShell so an explicit exit in the smoke script cannot skip rollback.
    & powershell.exe -NoProfile -File $smokeScript -VerifyPhotos *> (Join-Path $run 'api-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw 'New backend smoke checks failed.' }
    $after = (& $inventoryScript -PrivateRoot $PrivateRoot -Jar $artifact) | ConvertFrom-Json
    [IO.File]::WriteAllText((Join-Path $run 'inventory-after.json'), ($after | ConvertTo-Json -Depth 10), $utf8)
    if ($before.photoBindingsSha256 -cne $after.photoBindingsSha256) {
        throw 'Photo bindings changed during deployment; review the two inventories.'
    }
    $report = [ordered]@{
        completedAt = (Get-Date).ToString('o'); previousProcessId = $ExpectedProcessId
        processId = $newProcess.Id; privateRoot = $PrivateRoot; artifact = $artifact
        previousArtifact = $backup; previousSha256 = $oldHash; deployedSha256 = $newHash
        successEnabled = $false; photoBindingsUnchanged = $true; databaseMigrationExecuted = $false
        authenticatedBusinessAcceptance = 'Not performed'
    }
    [IO.File]::WriteAllText((Join-Path $run 'deployment.json'), ($report | ConvertTo-Json), $utf8)
    $verified = $true
    Write-Output "PASS: new local backend PID $($newProcess.Id), port 8082, private root configured."
} catch {
    if ($stopped -and !$verified) {
        if ($newProcess -and !$newProcess.HasExited) {
            Stop-Process -Id $newProcess.Id -ErrorAction Stop
            Wait-Process -Id $newProcess.Id -Timeout 20 -ErrorAction SilentlyContinue
        }
        $restored = Start-Backend $backup 'rollback'
        Wait-Backend $restored
        Write-Warning "Restored previous backend PID $($restored.Id). New photo deployment is NOT complete."
    }
    throw
} finally {
    $env:DATING_AUTH_HTTP_TEST = $oldAuth
    $env:DATING_MYSQL_TEST_URL = $oldMySql
}
