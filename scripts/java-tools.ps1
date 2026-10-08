$ErrorActionPreference = 'Stop'
function Resolve-ProjectJava {
    param([string]$Tool)
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$Tool.exe") }
    $javaFolder = Join-Path $env:ProgramFiles 'Java'
    if (Test-Path -LiteralPath $javaFolder) {
        $candidates += Get-ChildItem -LiteralPath $javaFolder -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\$Tool.exe" }
    }
    $found = Get-Command "$Tool.exe" -ErrorAction SilentlyContinue
    if ($found -and $found.Source -notmatch 'javapath|WindowsApps') { $candidates += $found.Source }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    throw '需要 JDK 21 或更新版本。請安裝 JDK 並設定 JAVA_HOME；不要只安裝 JRE。'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
