param(
    [Parameter(Mandatory = $true)][string]$Vault
)

$ErrorActionPreference = 'Stop'
$file = Get-Item -LiteralPath $Vault
if ($file.Name -ne 'credentials.clixml' -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Require the original encrypted credentials.clixml file.'
}
$result = Get-Content -LiteralPath (Join-Path $file.DirectoryName 'accounts-result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($result.action -ne 'CREATED') { throw 'No successful account creation report in this directory.' }
$credentials = @(Import-Clixml -LiteralPath $file.FullName)
$expected = @('accept_owner', 'accept_supervisor', 'accept_readonly', 'accept_denied')
if ($credentials.Count -ne 4 -or
    (Compare-Object $expected @($credentials | ForEach-Object { $_.UserName }))) {
    throw 'Unexpected credential contents.'
}
Write-Warning 'Local secret display. Do not run with transcript/recording enabled, share screenshots, or paste output into chat.'
if ((Read-Host 'Display passwords on this local screen? Type SHOW') -cne 'SHOW') { return }
$credentials | ForEach-Object {
    [pscustomobject]@{ Account = $_.UserName; Password = $_.GetNetworkCredential().Password }
} | Format-Table -AutoSize
Write-Host 'After acceptance, disable these accounts or reset their passwords through User Management.'
