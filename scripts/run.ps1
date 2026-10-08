param([ValidateRange(1,65535)][int]$Port = 8080,[switch]$NoBuild)
$portWasSpecified = $PSBoundParameters.ContainsKey('Port')
$savedPort = $env:APP_PORT
. (Join-Path $PSScriptRoot 'java-tools.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
Push-Location $projectRoot
try {
    if ($portWasSpecified) { $env:APP_PORT = [string]$Port }
    & (Resolve-ProjectJava 'java') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp 'build\classes' edu.nccu.ai.App
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
} finally {
    if ($portWasSpecified) {
        if ($null -eq $savedPort) { Remove-Item Env:APP_PORT -ErrorAction SilentlyContinue }
        else { $env:APP_PORT = $savedPort }
    }
    Pop-Location
}
