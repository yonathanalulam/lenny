[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:SKILLED_SERVICES_BASE_URL) { $env:SKILLED_SERVICES_BASE_URL } else { "http://localhost:8080" }),
    [string]$Password = $(if ($env:SKILLED_SERVICES_SMOKE_PASSWORD) { $env:SKILLED_SERVICES_SMOKE_PASSWORD } else { "smoke-only-password-$([Guid]::NewGuid().ToString('N'))" })
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$runId = "$(Get-Date -Format 'yyyyMMddHHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$customerEmail = "smoke-customer-$runId@example.test"
$providerEmail = "smoke-provider-$runId@example.test"

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', received '$Actual'."
    }
}

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST", "PUT", "PATCH", "DELETE")][string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token
    )
    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Depth 8
    }
    if ($Token) {
        $parameters.Headers = @{ Authorization = "Bearer $Token" }
    }
    Invoke-RestMethod @parameters
}

try {
    Write-Host "[1/7] Checking API health at $BaseUrl"
    $health = Invoke-Json -Method GET -Path "/api/v1/health"
    Assert-Equal $health.status "UP" "API health is not UP"

    Write-Host "[2/7] Registering disposable customer"
    $customer = Invoke-Json -Method POST -Path "/api/v1/auth/register/customers" -Body @{
        email = $customerEmail
        password = $Password
    }
    Assert-Equal $customer.user.email $customerEmail "Customer email mismatch"
    Assert-Equal ($customer.user.roles -contains "CUSTOMER") $true "Customer role was not assigned"
    if (-not $customer.accessToken -or -not $customer.refreshToken) { throw "Customer token pair was not returned." }

    Write-Host "[3/7] Verifying customer /me"
    $customerMe = Invoke-Json -Method GET -Path "/api/v1/me" -Token $customer.accessToken
    Assert-Equal $customerMe.id $customer.user.id "Customer /me identity mismatch"
    if ($customerMe.PSObject.Properties.Name -contains "passwordHash") { throw "/me exposed passwordHash." }
    if ($customerMe.PSObject.Properties.Name -contains "refreshToken") { throw "/me exposed refreshToken." }

    Write-Host "[4/7] Rotating customer refresh token"
    $rotated = Invoke-Json -Method POST -Path "/api/v1/auth/refresh" -Body @{ refreshToken = $customer.refreshToken }
    if (-not $rotated.accessToken -or $rotated.refreshToken -eq $customer.refreshToken) {
        throw "Refresh rotation did not return a new token pair."
    }

    Write-Host "[5/7] Registering disposable service provider"
    $provider = Invoke-Json -Method POST -Path "/api/v1/auth/register/service-providers" -Body @{
        email = $providerEmail
        password = $Password
    }
    Assert-Equal ($provider.user.roles -contains "SERVICE_PROVIDER") $true "Provider role was not assigned"

    Write-Host "[6/7] Verifying provider /me and protected onboarding"
    $providerMe = Invoke-Json -Method GET -Path "/api/v1/me" -Token $provider.accessToken
    Assert-Equal $providerMe.id $provider.user.id "Provider /me identity mismatch"
    $null = Invoke-Json -Method GET -Path "/api/v1/providers/me/onboarding" -Token $provider.accessToken

    Write-Host "[7/7] Verifying protected endpoint rejects missing bearer token"
    try {
        $null = Invoke-Json -Method GET -Path "/api/v1/me"
        throw "Unauthenticated /me unexpectedly succeeded."
    } catch {
        if ($_.Exception.Message -eq "Unauthenticated /me unexpectedly succeeded.") { throw }
        $status = [int]$_.Exception.Response.StatusCode
        Assert-Equal $status 401 "Unauthenticated /me returned the wrong status"
    }

    Write-Host "SMOKE TEST PASSED: health, customer auth/refresh, provider auth, /me, and protected access."
    exit 0
} catch {
    Write-Error "SMOKE TEST FAILED: $($_.Exception.Message)"
    exit 1
}
