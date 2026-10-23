param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$venvPython = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    python -m venv .venv
    & $venvPython -m pip install -r windows-server/requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Không cài đặt được thư viện Python.' }
}
$launchArgs = @('windows-server/launcher.py')
if ($NoBrowser) { $launchArgs += '--no-browser' }
& $venvPython @launchArgs
