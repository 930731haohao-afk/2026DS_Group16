param(
    [ValidateSet('prepare', 'score')][string]$Action = 'prepare',
    [string]$Path = '',
    [ValidateSet('dev', 'test')][string]$Split = 'dev',
    [string]$OutputJson = '',
    [switch]$NoBuild
)
. (Join-Path $PSScriptRoot 'java-tools.ps1')
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
Push-Location $projectRoot
try {
    $taskArguments = @($Action)
    if ($Path) { $taskArguments += $Path }
    if ($Action -eq 'prepare') {
        $taskArguments += @('--split', $Split)
    } else {
        if (-not $Path) { throw 'score 需要 -Path 指定已填寫的 CSV。' }
        if ($OutputJson) { $taskArguments += $OutputJson }
    }
    & (Resolve-ProjectJava 'java') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp 'build\classes' edu.nccu.ai.EvaluationRunner @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation command failed.' }
} finally { Pop-Location }
