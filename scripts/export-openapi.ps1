param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OutputPath = "docs/openapi.json"
)

$resolvedOutput = Join-Path (Get-Location) $OutputPath
$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Invoke-WebRequest -Uri "$BaseUrl/v3/api-docs" -OutFile $resolvedOutput
Write-Host "OpenAPI snapshot written to $resolvedOutput"
