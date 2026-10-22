param(
    [Parameter(Mandatory = $true)][string]$PrivateRoot,
    [Parameter(Mandatory = $true)][string]$PublicProfile,
    [switch]$Initialize
)

$ErrorActionPreference = 'Stop'
foreach ($path in @($PrivateRoot, $PublicProfile)) {
    if (![IO.Path]::IsPathRooted($path)) { throw 'Storage paths must be absolute.' }
    $ancestor = [IO.Path]::GetFullPath($path)
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -Force -LiteralPath $ancestor
            if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'Storage paths must contain only ordinary directories, not links/junctions.'
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
}
$root = [IO.Path]::GetFullPath($PrivateRoot).TrimEnd('\', '/')
$profile = [IO.Path]::GetFullPath($PublicProfile).TrimEnd('\', '/')
$comparison = [StringComparison]::OrdinalIgnoreCase
if ($root.Equals($profile, $comparison) -or
    ($root + '\').StartsWith($profile + '\', $comparison) -or
    ($profile + '\').StartsWith($root + '\', $comparison) -or
    $root -eq [IO.Path]::GetPathRoot($root).TrimEnd('\')) {
    throw 'Private storage must be a dedicated directory outside the public profile.'
}
$current = [Security.Principal.WindowsIdentity]::GetCurrent().User
$allowed = @($current.Value, 'S-1-5-18', 'S-1-5-32-544')
if (!(Test-Path -LiteralPath $root)) {
    if (!$Initialize) { throw 'Directory does not exist; use -Initialize to create it explicitly.' }
    New-Item -ItemType Directory -Path $root | Out-Null
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in $allowed) {
        $identity = New-Object Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule(
            $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $root -AclObject $acl
}
$acl = Get-Acl -LiteralPath $root
if (!$acl.AreAccessRulesProtected) { throw 'Private directory inherits permissions; review ACL before deployment.' }
foreach ($rule in $acl.Access) {
    if ($rule.AccessControlType -eq 'Allow' -and
        $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed) {
        throw 'Private directory grants access outside the deployment user, SYSTEM and Administrators.'
    }
}
# Only the newly created, unpredictable probe file is ever deleted.
$probe = Join-Path $root ('.deployment-probe-' + [guid]::NewGuid().ToString('N') + '.tmp')
$bytes = [guid]::NewGuid().ToByteArray()
$created = $false
try {
    $stream = [IO.File]::Open($probe, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $created = $true
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
    if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($probe)) -cne [Convert]::ToBase64String($bytes)) {
        throw 'Private directory probe did not round-trip.'
    }
} finally {
    if ($created -and [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($probe)) -eq $root) {
        Remove-Item -LiteralPath $probe -Force
    }
}
[pscustomobject]@{
    PrivateRoot = $root
    PublicProfile = $profile
    InheritanceDisabled = $true
    AllowedIdentities = 'Current deployment user, SYSTEM, Administrators'
    WriteReadDeleteProbe = 'Passed'
    PublicServerMappings = 'Not verified by this filesystem check'
}
