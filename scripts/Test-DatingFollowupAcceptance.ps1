param(
    [uri]$BaseUri = 'http://127.0.0.1:8082/',
    [Parameter(Mandatory = $true)][ValidateSet('Owner','Supervisor','ReadOnly','Denied')][string]$Profile,
    [Parameter(Mandatory = $true)][ValidateRange(2, [long]::MaxValue)][long]$ExpectedUserId,
    [System.Security.SecureString]$AccessToken,
    [long[]]$AllowedTaskIds = @(),
    [long[]]$ForbiddenTaskIds = @()
)

$ErrorActionPreference = 'Stop'
if ($BaseUri.Scheme -notin @('http','https') -or
    $BaseUri.Host -notin @('127.0.0.1','localhost','[::1]') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or
    $BaseUri.AbsolutePath -notin @('/','/dev-api','/dev-api/')) {
    throw 'Only a loopback backend or its /dev-api proxy is allowed.'
}
$ids = @($AllowedTaskIds) + @($ForbiddenTaskIds)
if ($ids.Count -gt 40 -or @($ids | Where-Object { $_ -le 0 }).Count -or
    @($ids | Select-Object -Unique).Count -ne $ids.Count) {
    throw 'Use at most 40 distinct positive task IDs; allow/deny samples must not overlap.'
}
if (($Profile -eq 'Denied' -and $AllowedTaskIds.Count) -or
    ($Profile -eq 'Supervisor' -and $ForbiddenTaskIds.Count)) {
    throw 'Denied cannot have allowed samples; Supervisor cannot have ownership-denied samples.'
}
if (!$AccessToken) {
    $AccessToken = Read-Host 'Token from your own authorized normal login (without Bearer)' -AsSecureString
}
if (!$AccessToken -or $AccessToken.Length -eq 0) { throw 'A nonempty authorized login token is required.' }

Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.BaseAddress = [uri]($BaseUri.AbsoluteUri.TrimEnd('/') + '/')
$checks = New-Object 'System.Collections.Generic.List[object]'
$skipped = New-Object 'System.Collections.Generic.List[string]'
$failedCheck = $null
$currentCheck = 'session'
$plainToken = $null
$ownershipDenied = [regex]::Unescape('\u65e0\u6743\u8bbf\u95ee\u8be5\u914d\u5bf9')

function Assert-Check([bool]$Passed) {
    $checks.Add([pscustomobject]@{ check=$script:currentCheck; passed=$Passed })
    if (!$Passed) { throw 'Acceptance check failed.' }
}
function Read-Api([string]$Path) {
    # This runner has no write-method parameter and never follows redirects with credentials.
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $Path)
    $request.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $plainToken)
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $body = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        return [pscustomobject]@{ http=[int]$response.StatusCode; body=$body }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}
function Assert-Code($Response, [int]$Code) {
    Assert-Check ($null -ne $Response.body.code -and $Response.body.code -eq $Code -and
        $Response.http -in @(200, $Code))
}
function Assert-Page($Response) {
    Assert-Code $Response 200
    Assert-Check ($Response.body.rows -is [Array] -and
        $null -ne $Response.body.total -and $Response.body.total -ge 0 -and
        $Response.body.rows.Count -le 1 -and $Response.body.total -ge $Response.body.rows.Count)
}
function Assert-NoData($Response) {
    Assert-Check ($null -eq $Response.body.data -and $null -eq $Response.body.rows)
}

try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($AccessToken)
    try { $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $info = Read-Api 'getInfo'
    Assert-Code $info 200
    $currentCheck = 'expected-non-admin-identity'
    Assert-Check ($info.body.user -and $info.body.user.userId -eq $ExpectedUserId -and
        $info.body.roles -notcontains 'admin' -and $info.body.permissions -is [Array])
    $permissions = @($info.body.permissions)
    $currentCheck = 'no-wildcard-permissions'
    Assert-Check (@($permissions | Where-Object { $_ -match '\*' }).Count -eq 0)
    $expected = @{
        'dating:match:query' = $Profile -ne 'Denied'
        'dating:exception:handle' = $Profile -in @('Owner','Supervisor')
        'dating:service:all' = $Profile -eq 'Supervisor'
    }
    foreach ($permission in @('dating:match:query','dating:exception:handle','dating:service:all')) {
        $currentCheck = "profile-$permission"
        Assert-Check (($permissions -contains $permission) -eq $expected[$permission])
    }
    foreach ($filter in @('', '&mine=true', '&overdueOnly=true', '&handling=UNASSIGNED',
        '&handling=IN_PROGRESS', '&handling=WAITING')) {
        $currentCheck = "exception-list$filter"
        $response = Read-Api "dating/deduction-exceptions?pageNum=1&pageSize=1$filter"
        if ($Profile -eq 'Denied') {
            Assert-Code $response 403
            Assert-NoData $response
        } else { Assert-Page $response }
    }
    $sample = 0
    foreach ($id in $AllowedTaskIds) {
        $sample++
        $currentCheck = "allowed-sample-$sample-detail"
        $response = Read-Api "dating/deduction-tasks/$id/follow-up"
        Assert-Code $response 200
        Assert-Check ($response.body.data.followup.taskId -eq $id -and
            $null -ne $response.body.data.followup.version -and
            $response.body.data.followup.version -ge 0 -and
            $response.body.data.followup.status -in @('UNASSIGNED','IN_PROGRESS','WAITING'))
        foreach ($history in @('follow-up/history','history')) {
            $currentCheck = "allowed-sample-$sample-$history"
            $response = Read-Api "dating/deduction-tasks/$id/${history}?pageNum=1&pageSize=1"
            Assert-Page $response
            foreach ($row in $response.body.rows) { Assert-Check ($row.taskId -eq $id) }
        }
    }
    $sample = 0
    foreach ($id in $ForbiddenTaskIds) {
        $sample++
        foreach ($suffix in @('follow-up','follow-up/history','history')) {
            $currentCheck = "forbidden-sample-$sample-$suffix"
            $response = Read-Api "dating/deduction-tasks/$id/$suffix"
            if ($Profile -eq 'Denied') { Assert-Code $response 403 }
            else {
                # A missing task or arbitrary 500 is not proof of ownership isolation.
                Assert-Code $response 500
                Assert-Check ($response.body.msg -ceq $ownershipDenied)
            }
            Assert-NoData $response
        }
    }
    if ($Profile -ne 'Denied' -and !$AllowedTaskIds.Count) { $skipped.Add('allowed-task-samples-not-provided') }
    if ($Profile -ne 'Supervisor' -and !$ForbiddenTaskIds.Count) { $skipped.Add('forbidden-task-samples-not-provided') }
} catch {
    # Never print server bodies, exceptions, tokens, account identities, or customer/history fields.
    $failedCheck = $currentCheck
} finally {
    $plainToken = $null
    $client.Dispose()
    $handler.Dispose()
}
$status = if ($failedCheck) { 'FAILED' } elseif ($skipped.Count) { 'PARTIAL_READ_ONLY' } else { 'PASSED_READ_ONLY' }
[ordered]@{
    status=$status; profile=$Profile; checkedAt=(Get-Date).ToString('o')
    failedCheck=$failedCheck; checks=@($checks.ToArray()); skipped=@($skipped.ToArray())
    writeOperations='NOT_EXECUTED'; fullWorkflowAcceptance=$false
} | ConvertTo-Json -Depth 6
if ($failedCheck) { exit 1 }
