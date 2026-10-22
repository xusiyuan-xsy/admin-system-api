param(
    [Parameter(Mandatory = $true)][string]$Deployment,
    [switch]$Apply,
    [switch]$CreateAccounts
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
$utf8 = New-Object Text.UTF8Encoding($false)
$prior = Get-Content -LiteralPath $Deployment -Raw -Encoding UTF8 | ConvertFrom-Json
$live = Get-CimInstance Win32_Process -Filter "ProcessId=$($prior.processId)"
if (!$prior.completed -or !$live -or $live.Name -ne 'java.exe' -or
    (Get-FileHash -LiteralPath $prior.artifact).Hash -cne $prior.deployedSha256) {
    throw 'Require the current completed local deployment and unchanged running artifact.'
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
Add-Type -AssemblyName System.IO.Compression.FileSystem
$config = Join-Path $repo 'ruoyi-admin/src/main/resources/application-druid.yml'
$zip = [IO.Compression.ZipFile]::OpenRead($prior.artifact)
try {
    $reader = New-Object IO.StreamReader($zip.GetEntry('BOOT-INF/classes/application-druid.yml').Open(), $utf8)
    try { $packaged = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($packaged -cne [IO.File]::ReadAllText($config, $utf8)) {
        throw 'Datasource configuration differs from the running package.'
    }
    $inventory = Get-Content -LiteralPath (Join-Path (Split-Path $Deployment -Parent) 'inventory-after.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $kind = if ($CreateAccounts) { 'accounts' } else { 'roles' }
    $run = Join-Path $workspace ('deployment-records/dating-acceptance-' + $kind + '-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff'))
    & (Join-Path $PSScriptRoot 'Test-DatingPhotoDirectory.ps1') -PrivateRoot $run -PublicProfile $inventory.publicProfile -Initialize | Out-Null
    $dependencies = @('snakeyaml', 'fastjson2', 'mysql-connector-j')
    if ($CreateAccounts) { $dependencies += 'spring-security-crypto' }
    foreach ($dependency in $dependencies) {
        $entries = @($zip.Entries | Where-Object { $_.FullName -match "^BOOT-INF/lib/$dependency-[0-9][^/]*\.jar$" })
        if ($entries.Count -ne 1) { throw "Expected one packaged dependency: $dependency" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $run $entries[0].Name))
    }
} finally { $zip.Dispose() }
$source = Join-Path $PSScriptRoot 'DatingAcceptanceRoles.java'
Copy-Item -LiteralPath $source -Destination $run
Copy-Item -LiteralPath $PSCommandPath -Destination $run
$java = $live.ExecutablePath
& (Join-Path (Split-Path $java -Parent) 'javac.exe') -encoding UTF-8 -cp "$run\*" -d $run $source
if ($LASTEXITCODE -ne 0) { throw 'Role tool compilation failed; no database changes attempted.' }
if ($CreateAccounts) {
    $accountSource = Join-Path $PSScriptRoot 'DatingAcceptanceAccounts.java'
    Copy-Item -LiteralPath $accountSource -Destination $run
    & (Join-Path (Split-Path $java -Parent) 'javac.exe') -encoding UTF-8 -cp "$run;$run\*" -d $run $accountSource
    if ($LASTEXITCODE -ne 0) { throw 'Account tool compilation failed; no database changes attempted.' }
    $planDirectory = New-Item -ItemType Directory -Path (Join-Path $run 'plan')
    & $java -cp "$run;$run\*" DatingAcceptanceAccounts plan $config $planDirectory.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Account preflight rejected; no accounts created.' }
    $plan = Get-Content -LiteralPath (Join-Path $planDirectory.FullName 'accounts-result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (!$Apply -or $plan.action -eq 'ALREADY_PRESENT') {
        Write-Host "Account plan evidence: $run"
        return
    }
    # DPAPI encrypts each credential for this Windows user before any database write.
    $credentials = @()
    $passwords = @{}
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        foreach ($account in $plan.accounts) {
            $bytes = New-Object byte[] 12
            $rng.GetBytes($bytes)
            $password = [Convert]::ToBase64String($bytes) + 'aA7!'
            $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
            $credentials += New-Object Management.Automation.PSCredential($account.username, $secure)
            $passwords[$account.username] = $password
        }
        $vault = Join-Path $run 'credentials.clixml'
        $credentials | Export-Clixml -LiteralPath $vault -Encoding UTF8
        $restored = @(Import-Clixml -LiteralPath $vault)
        if ($restored.Count -ne 4) { throw 'Encrypted credential verification failed.' }
        foreach ($credential in $restored) {
            if ($credential.GetNetworkCredential().Password -cne $passwords[$credential.UserName]) {
                throw 'Encrypted credential verification failed.'
            }
        }
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $java
        $info.Arguments = "-cp `"$run;$run\*`" DatingAcceptanceAccounts apply `"$config`" `"$run`""
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.StandardOutputEncoding = $utf8
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $info
        [void]$process.Start()
        $process.StandardInput.Write(($passwords | ConvertTo-Json -Compress))
        $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEnd()
        $errors = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Account creation stopped; review protected evidence at $run. No automatic retry." }
        Write-Output $output
        Write-Host "Encrypted credentials and account evidence: $run"
    } finally {
        $rng.Dispose()
        $passwords.Clear()
        $password = $null
        $credentials = $null
        $restored = $null
        if ($process) { $process.Dispose() }
    }
    return
}
$previousEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = $utf8
    $mode = if ($Apply) { 'apply' } else { 'plan' }
    & $java -cp "$run;$run\*" DatingAcceptanceRoles $mode $config $run
    if ($LASTEXITCODE -ne 0) { throw "Role setup stopped; inspect protected evidence at $run." }
    Write-Host "Protected evidence: $run"
} finally { [Console]::OutputEncoding = $previousEncoding }
