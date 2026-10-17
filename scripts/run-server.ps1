param([string]$Config = 'config/application.properties')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & mvn.cmd compile exec:java '-Dexec.mainClass=vn.edu.music.server.ServerMain' "-Dexec.args=$Config"
    if ($LASTEXITCODE -ne 0) { throw 'Server command failed. Check JAVA_HOME and Maven output.' }
} finally {
    Pop-Location
}
