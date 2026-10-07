<#
    Posts a Genesys webhook payload to the local service with a valid
    X-Hub-Signature-256 header.

    The signature is "sha256=" followed by the BASE64 (not hex) HMAC-SHA256 of
    the raw request body, which is what the Genesys Postman collection's
    pre-request script produces and what GenesysSignatureVerifier checks.

    The bytes that are signed must be the bytes that are sent. Re-serialising
    the JSON between signing and sending changes the whitespace and the
    signature no longer matches — a real failure mode, and one
    GenesysSignatureVerifierTest covers.

    Usage:
        .\post-webhook.ps1
        .\post-webhook.ps1 -PayloadFile agent-message.json -Secret local-dev-secret
#>

param(
    [string] $PayloadFile = "agent-message.json",
    [string] $Url         = "http://localhost:8080/genesys/webhook",
    [string] $Secret      = "local-dev-secret"
)

$ErrorActionPreference = "Stop"

$path = Join-Path $PSScriptRoot $PayloadFile
if (-not (Test-Path $path)) {
    throw "Payload file not found: $path"
}

# Raw bytes, exactly as they will go on the wire.
$body = [System.IO.File]::ReadAllBytes($path)

$hmac = [System.Security.Cryptography.HMACSHA256]::new()
$hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
$signature = "sha256=" + [Convert]::ToBase64String($hmac.ComputeHash($body))

Write-Host "POST $Url"
Write-Host "X-Hub-Signature-256: $signature"
Write-Host ""

try {
    $response = Invoke-WebRequest -Uri $Url -Method Post `
        -ContentType "application/json" `
        -Headers @{ "X-Hub-Signature-256" = $signature } `
        -Body $body

    Write-Host "HTTP $($response.StatusCode)" -ForegroundColor Green
}
catch {
    $status = $_.Exception.Response.StatusCode.value__
    Write-Host "HTTP $status" -ForegroundColor Red
    if ($status -eq 401) {
        Write-Host "The signature was rejected. Check that -Secret matches" `
                   "handoff.genesys.webhook-secret in application.properties."
    }
    throw
}
