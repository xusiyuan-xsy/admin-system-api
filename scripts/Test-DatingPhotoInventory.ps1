param(
    [Parameter(Mandatory = $true)][string]$PrivateRoot,
    [string]$Jar,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (!$Jar) { $Jar = Join-Path $repo 'ruoyi-admin/target/ruoyi-admin.jar' }
if (!(Test-Path -LiteralPath $Jar -PathType Leaf)) { throw 'Build the backend jar first.' }
if (![IO.Path]::IsPathRooted($PrivateRoot)) { throw 'PrivateRoot must be absolute.' }
# Only dependencies are extracted; no datasource secrets are copied to the temporary directory.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$run = Join-Path ([IO.Path]::GetTempPath()) ('dating-photo-inventory-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Jar))
try {
    foreach ($dependency in @('snakeyaml', 'fastjson2', 'mysql-connector-j')) {
        $entries = @($zip.Entries | Where-Object { $_.FullName -match "^BOOT-INF/lib/$dependency-[0-9][^/]*\.jar$" })
        if ($entries.Count -ne 1) { throw "Expected one packaged dependency: $dependency" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $run $entries[0].Name))
    }
} finally { $zip.Dispose() }

$source = Join-Path $PSScriptRoot 'DatingPhotoInventory.java'
$previousEncoding = [Console]::OutputEncoding
try {
    # Match Java's explicit UTF-8 stdout when PowerShell decodes the native pipe.
    [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
    if ($SelfTest) {
        $output = & java --class-path "$run\*" $source --self-test
    } else {
        $output = & java --class-path "$run\*" $source (Join-Path $repo 'ruoyi-admin/src/main/resources') $PrivateRoot
    }
    $code = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousEncoding
}
if ($code -ne 0) { throw 'Photo inventory failed; no photo migration was attempted.' }
if (!$SelfTest) {
    $report = $output | ConvertFrom-Json
    if ($report.privateRoot -cne [IO.Path]::GetFullPath($PrivateRoot)) {
        throw 'Inventory path round-trip failed; do not use this report for deployment.'
    }
}
Write-Output $output
