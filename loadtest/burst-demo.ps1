<#
.SYNOPSIS
    Fires a burst at a guarded endpoint and reports the 200/429 split.

.DESCRIPTION
    A dependency-free way to see enforcement working - no k6 required.
    Runs on both Windows PowerShell 5.1 and PowerShell 7+.

    -Endpoint otp        Sliding window, 3 sends per rolling minute. Throttles almost
                         immediately, so it is the quickest way to see a 429.
    -Endpoint authorize  Token bucket, ~1700/s sustained with a 2000 burst. Needs a
                         few thousand requests to throttle; use k6 for real volume.

.EXAMPLE
    ./loadtest/burst-demo.ps1
    ./loadtest/burst-demo.ps1 -Endpoint authorize -Requests 2500
    ./loadtest/burst-demo.ps1 -BaseUrl http://localhost:8081
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',

    [ValidateSet('otp', 'authorize')]
    [string]$Endpoint = 'otp',

    [int]$Requests = 6,

    [string]$ApiKey = "burst-demo-$(Get-Random)"
)

Set-StrictMode -Version Latest

if ($Endpoint -eq 'otp') {
    $uri = "$BaseUrl/api/v1/payments/otp"
    $body = '{"orderId":"ord-burst","phone":"+919876543210"}'
}
else {
    $uri = "$BaseUrl/api/v1/payments/authorize"
    $body = '{"orderId":"ord-burst","amount":149.99,"currency":"INR"}'
}

$headers = @{ 'X-API-Key' = $ApiKey }

# PowerShell 5.1 raises a terminating error on 4xx instead of returning the
# response, and has no -SkipHttpErrorCheck. Normalise both behaviours here so the
# 429 we are trying to demonstrate is treated as a result, not a failure.
function Invoke-Probe {
    param([string]$Uri, [hashtable]$Headers, [string]$Body)

    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Post -Headers $Headers -Body $Body `
            -ContentType 'application/json' -TimeoutSec 10 -UseBasicParsing
        return [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Headers = $response.Headers
        }
    }
    catch {
        $webResponse = $null
        if ($_.Exception.PSObject.Properties['Response']) {
            $webResponse = $_.Exception.Response
        }
        if ($null -ne $webResponse) {
            $collected = @{}
            foreach ($name in @('Retry-After', 'X-RateLimit-Limit', 'X-RateLimit-Policy')) {
                $collected[$name] = $webResponse.Headers[$name]
            }
            return [pscustomobject]@{
                Status  = [int]$webResponse.StatusCode
                Headers = $collected
            }
        }
        return [pscustomobject]@{ Status = -1; Headers = @{} }
    }
}

function Get-HeaderValue {
    param($Headers, [string]$Name)

    if ($null -eq $Headers) { return $null }
    $value = $Headers[$Name]
    if ($null -eq $value) { return $null }
    return ($value -join ',')
}

Write-Host "Sending $Requests requests to $uri as '$ApiKey'..." -ForegroundColor Cyan

$admitted = 0
$throttled = 0
$unexpected = 0
$retryAfter = $null
$limit = $null
$policy = $null

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Requests; $i++) {
    $result = Invoke-Probe -Uri $uri -Headers $headers -Body $body

    switch ($result.Status) {
        200 { $admitted++ }
        429 {
            $throttled++
            if (-not $retryAfter) { $retryAfter = Get-HeaderValue $result.Headers 'Retry-After' }
        }
        default { $unexpected++ }
    }

    if (-not $limit) { $limit = Get-HeaderValue $result.Headers 'X-RateLimit-Limit' }
    if (-not $policy) { $policy = Get-HeaderValue $result.Headers 'X-RateLimit-Policy' }
}

$stopwatch.Stop()

Write-Host ''
Write-Host '=== Burst result =======================================' -ForegroundColor Yellow
Write-Host ("  policy           : {0}" -f $policy)
Write-Host ("  limit            : {0}" -f $limit)
Write-Host ("  admitted  (200)  : {0}" -f $admitted) -ForegroundColor Green
Write-Host ("  throttled (429)  : {0}" -f $throttled) -ForegroundColor Red
if ($unexpected -gt 0) {
    Write-Host ("  unexpected       : {0}" -f $unexpected) -ForegroundColor Magenta
}
Write-Host ("  retry-after      : {0}s" -f $retryAfter)
Write-Host ("  wall clock       : {0:N2}s" -f $stopwatch.Elapsed.TotalSeconds)
Write-Host '========================================================' -ForegroundColor Yellow
