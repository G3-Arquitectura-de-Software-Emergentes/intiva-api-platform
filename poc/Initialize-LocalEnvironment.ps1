param([string]$Path = ".env.poc")
$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath $Path) {
    throw "The environment file already exists; no values were changed."
}
function New-LocalSecret {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}
$keys = @("POSTGRES_PASSWORD","AGENT_DB_PASSWORD","FINANCES_DB_PASSWORD",
    "COMMUNICATIONS_DB_PASSWORD","SAVINGS_DB_PASSWORD","LEGACY_DB_PASSWORD",
    "LEGACY_JWT_SECRET","INTIVA_SERVICE_KEY","RABBITMQ_PASSWORD")
$lines = foreach ($key in $keys) { "$key=$(New-LocalSecret)" }
$lines += "GEMINI_API_KEY="
$lines += "GEMINI_MODEL=gemini-2.5-flash"
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $Path), $lines)
Write-Output "Created $Path. Set GEMINI_API_KEY locally before starting the demo."
