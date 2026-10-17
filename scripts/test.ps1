$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & mvn.cmd verify
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
} finally {
    Pop-Location
}
