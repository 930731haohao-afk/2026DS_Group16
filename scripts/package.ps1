param([string]$OutputPath = '')
. (Join-Path $PSScriptRoot 'java-tools.ps1')
& (Join-Path $PSScriptRoot 'test.ps1')
Push-Location $projectRoot
try {
    if (-not $OutputPath) { $OutputPath = 'outputs\AI-Knowledge-Search-Group16-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip' }
    $packagePath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
    if (Test-Path -LiteralPath $packagePath) { throw 'Package already exists; use a new output filename.' }
    New-Item -ItemType Directory -Force -Path (Split-Path $packagePath -Parent) | Out-Null
    $entries = @('README.md','.env.example','.gitignore','.gitattributes','.dockerignore','Dockerfile','src','web','data','docs','scripts','.github')
    $entries += Get-ChildItem -LiteralPath $projectRoot -Filter '*.txt' -File | ForEach-Object Name
    & (Resolve-ProjectJava 'jar') --create --no-manifest --file $packagePath @entries
    if ($LASTEXITCODE -ne 0) { throw 'Package creation failed.' }
    $listing = & (Resolve-ProjectJava 'jar') --list --file $packagePath
    if ($listing | Where-Object { $_ -match '(^|/)(\.env|\.git|work|build|logs)(/|$)' }) { throw 'Unexpected private or runtime file in package.' }
    Write-Output $packagePath
} finally { Pop-Location }
