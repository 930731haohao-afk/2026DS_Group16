. (Join-Path $PSScriptRoot 'java-tools.ps1')
& (Join-Path $PSScriptRoot 'build.ps1') -Tests
Push-Location $projectRoot
try {
    & (Resolve-ProjectJava 'java') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -ea -cp 'build\classes' edu.nccu.ai.TestSuite
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
} finally { Pop-Location }
