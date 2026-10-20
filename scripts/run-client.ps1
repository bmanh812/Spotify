$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & mvn.cmd compile exec:java '-Dexec.mainClass=vn.edu.music.client.ClientMain'
    if ($LASTEXITCODE -ne 0) { throw 'Client command failed. Check JAVA_HOME and Maven output.' }
} finally {
    Pop-Location
}
