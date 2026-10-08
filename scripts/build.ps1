param([switch]$Tests)
. (Join-Path $PSScriptRoot 'java-tools.ps1')
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path 'build\classes' | Out-Null
    $sources = @(Get-ChildItem -LiteralPath 'src\main\java' -Filter '*.java' -Recurse | ForEach-Object FullName)
    if ($Tests) { $sources += Get-ChildItem -LiteralPath 'src\test\java' -Filter '*.java' -Recurse | ForEach-Object FullName }
    $compiler = Resolve-ProjectJava 'javac'
    & $compiler --release 21 -encoding UTF-8 -Xlint:all -Werror -d 'build\classes' @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
    Write-Output ('Build OK: ' + $sources.Count + ' Java files')
} finally { Pop-Location }
