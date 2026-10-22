param(
    [uri]$BaseUri = 'http://127.0.0.1:8082/',
    [System.Security.SecureString]$AccessToken,
    [switch]$Authenticated,
    [switch]$VerifyPhotos,
    [switch]$VerifyRecovery,
    [switch]$VerifyFollowup
)

$ErrorActionPreference = 'Stop'
if ($BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.Host -notin @('127.0.0.1', 'localhost', '[::1]') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or
    $BaseUri.AbsolutePath -notin @('/', '/dev-api', '/dev-api/')) {
    throw 'Only a loopback backend or its /dev-api proxy is allowed.'
}
if ($Authenticated -and !$AccessToken) {
    $AccessToken = Read-Host 'Access token from a normal login (without Bearer)' -AsSecureString
}
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.BaseAddress = [uri]($BaseUri.AbsoluteUri.TrimEnd('/') + '/')
$checks = New-Object 'System.Collections.Generic.List[object]'

function Invoke-Read([string]$Path, [string]$Token = '', [string]$Method = 'GET') {
    $request = New-Object System.Net.Http.HttpRequestMessage(
        (New-Object System.Net.Http.HttpMethod($Method)), $Path.TrimStart('/'))
    $response = $null
    try {
        if ($Token) {
            $request.Headers.Authorization =
                New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $body = $null
        try { $body = ConvertFrom-Json -InputObject $text -ErrorAction Stop } catch { }
        return [pscustomobject]@{ Http = [int]$response.StatusCode; Body = $body }
    } catch {
        throw "$Method $Path failed. Check the backend process, port and local proxy."
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Assert-Code([string]$Name, $Result, [int]$Expected) {
    $actual = $null
    if ($Result.Body -and $null -ne $Result.Body.code) { $actual = [int]$Result.Body.code }
    $passed = $actual -eq $Expected -and
        ($Result.Http -eq 200 -or $Result.Http -eq $Expected)
    $checks.Add([pscustomobject]@{
        Check = $Name; Http = $Result.Http; Expected = $Expected; Actual = $actual; Passed = $passed
    })
    if (!$passed) { throw "$Name failed; HTTP $($Result.Http), API code $actual, expected $Expected." }
}

function Has-Permission([string]$Permission) {
    return $permissions -contains '*:*:*' -or $permissions -contains $Permission
}

$endpoints = @(
    @{ Path = '/system/payingUsers/list?pageNum=1&pageSize=1'; Permission = 'system:payingUsers:list'; Rows = $true },
    @{ Path = '/system/payingUsers/stats'; Permission = 'system:payingUsers:list' },
    @{ Path = '/dating/orders?pageNum=1&pageSize=1'; Permission = 'dating:order:list'; Rows = $true },
    @{ Path = '/dating/entitlements?pageNum=1&pageSize=1'; Permission = 'dating:entitlement:list'; Rows = $true },
    @{ Path = '/dating/integrity-issues?pageNum=1&pageSize=1'; Permission = 'dating:entitlement:list'; Rows = $true },
    @{ Path = '/dating/matches?pageNum=1&pageSize=1'; Permission = 'dating:match:list'; Rows = $true },
    @{ Path = '/dating/success-cases?pageNum=1&pageSize=1'; Permission = 'dating:success:list'; Rows = $true },
    @{ Path = '/dating/success-policy'; Permission = 'dating:success:close' }
)
if ($VerifyRecovery) {
    $endpoints += @(
        @{ Path = '/dating/recovery/overview'; Permission = 'dating:recovery:query' },
        @{ Path = '/dating/recovery/runs?pageNum=1&pageSize=1'; Permission = 'dating:recovery:query'; Rows = $true },
        @{ Path = '/dating/deduction-exceptions?pageNum=1&pageSize=1'; Permission = 'dating:match:query'; Rows = $true }
    )
}
$plainToken = $null
$failure = $null
try {
    $captcha = Invoke-Read '/captchaImage'
    Assert-Code 'captcha' $captcha 200
    if ($null -eq $captcha.Body.captchaEnabled) { throw 'Missing captcha configuration in response.' }
    if ($captcha.Body.captchaEnabled -and (!$captcha.Body.uuid -or !$captcha.Body.img)) {
        throw 'Captcha is enabled but its image or identifier is missing.'
    }
    foreach ($path in @('/getInfo') + @($endpoints | ForEach-Object { $_.Path })) {
        Assert-Code "anonymous $path" (Invoke-Read $path) 401
        Assert-Code "invalid-token $path" (Invoke-Read $path 'invalid-p0-test-token') 401
    }
    if ($VerifyPhotos) {
        # Impossible archive ID and no file body: even a broken guard cannot create an attachment.
        foreach ($photo in @(
            @{ Path = '/dating/archives/0/photos/00000000000000000000000000000000.png'; Method = 'GET' },
            @{ Path = '/dating/archives/0/photos'; Method = 'POST' }
        )) {
            Assert-Code "anonymous photo $($photo.Method)" (Invoke-Read $photo.Path '' $photo.Method) 401
            Assert-Code "invalid-token photo $($photo.Method)" (Invoke-Read $photo.Path 'invalid-p0-test-token' $photo.Method) 401
        }
        # OpenAPI was retired with system tools. Anonymous checks prove access control only;
        # controller registration is covered by the backend route regression tests.
    }

    if ($VerifyRecovery) {
        Assert-Code 'anonymous deduction history' (Invoke-Read '/dating/deduction-tasks/0/history') 401
        Assert-Code 'invalid-token deduction history' (
            Invoke-Read '/dating/deduction-tasks/0/history' 'invalid-p0-test-token') 401
    }

    if ($VerifyFollowup) {
        foreach ($endpoint in @(
            @{ Path = '/dating/deduction-tasks/{id}/follow-up'; Method = 'get' },
            @{ Path = '/dating/deduction-tasks/{id}/follow-up'; Method = 'post' },
            @{ Path = '/dating/deduction-tasks/{id}/follow-up/history'; Method = 'get' }
        )) {
            # Impossible task ID and bodyless POST: no authenticated business operation.
            $path = $endpoint.Path.Replace('{id}', '0')
            $method = $endpoint.Method
            Assert-Code "anonymous followup $method $path" (Invoke-Read $path '' $method) 401
            Assert-Code "invalid-token followup $method $path" (
                Invoke-Read $path 'invalid-p0-test-token' $method) 401
        }
    }

    if ($AccessToken) {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($AccessToken)
        try { $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        if ([string]::IsNullOrWhiteSpace($plainToken)) { throw 'An empty access token cannot verify a login.' }
        $info = Invoke-Read '/getInfo' $plainToken
        Assert-Code 'authenticated getInfo' $info 200
        if ($null -eq $info.Body.permissions -or !$info.Body.user) {
            throw 'Missing user or permission data in getInfo.'
        }
        $permissions = @($info.Body.permissions)
        Assert-Code 'authenticated getRouters' (Invoke-Read '/getRouters' $plainToken) 200
        foreach ($endpoint in $endpoints) {
            $expected = 403
            if (Has-Permission $endpoint.Permission) { $expected = 200 }
            $result = Invoke-Read $endpoint.Path $plainToken
            Assert-Code "authenticated $($endpoint.Path)" $result $expected
            if ($expected -ne 200) { continue }
            if ($endpoint.Rows -and
                ('rows' -notin $result.Body.PSObject.Properties.Name -or $null -eq $result.Body.total)) {
                throw "Missing pagination data: $($endpoint.Path)"
            }
            if ($endpoint.Path -eq '/dating/success-policy' -and
                $result.Body.data.enabled -cne $false) {
                throw 'Success closure is not confirmed disabled. No state change was attempted.'
            }
            if ($endpoint.Path -like '/dating/entitlements?*') {
                $first = @($result.Body.rows) | Select-Object -First 1
                if ($first -and $first.entitlementId) {
                    Assert-Code 'authenticated entitlement detail' (
                        Invoke-Read "/dating/entitlements/$($first.entitlementId)" $plainToken) 200
                } else {
                    Write-Output 'SKIP entitlement detail: no visible entitlement in this account.'
                }
            }
        }
        Write-Output 'Permission checks reflect this account only; cross-owner and write tests are still required.'
    } elseif ($Authenticated) {
        throw 'Authenticated checks require a nonempty access token.'
    } else {
        Write-Output 'SKIP authenticated checks: no access token supplied.'
    }
} catch {
    $failure = $_.Exception.Message
} finally {
    $plainToken = $null
    $client.Dispose()
    $handler.Dispose()
    $checks | Format-Table -AutoSize
}
if ($failure) {
    Write-Error $failure
    exit 1
}
Write-Output 'Read-only API smoke checks passed; this is not full workflow acceptance.'
