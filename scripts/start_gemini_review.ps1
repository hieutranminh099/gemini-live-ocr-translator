param([switch]$Check)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$commonGit = (& git -C $sourceRoot rev-parse --path-format=absolute --git-common-dir)
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the shared Git repository.' }
$projectRoot = Split-Path -Parent $commonGit.Trim()
$reviewRoot = Join-Path $projectRoot '.worktrees\gemini-review'
if (-not (Test-Path -LiteralPath (Join-Path $reviewRoot '.git'))) {
    throw 'The gemini-review worktree must be created first. See docs/GITHUB_COLLABORATION.md.'
}
$cliEntry = Join-Path $projectRoot '.tools\gemini-cli\node_modules\@google\gemini-cli\bundle\gemini.js'
if (-not (Test-Path -LiteralPath $cliEntry)) {
    throw 'Install the official Gemini CLI locally or run gemini --approval-mode plan inside gemini-review. No authentication settings have been changed.'
}
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if ($nodeCommand) {
    $nodePath = $nodeCommand.Source
} else {
    $nodePath = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
    if (-not (Test-Path -LiteralPath $nodePath)) { throw 'Node.js is required to run Gemini CLI.' }
}
if ($Check) {
    Write-Output "Gemini review worktree: $reviewRoot"
    & $nodePath $cliEntry --version
    exit $LASTEXITCODE
}
Push-Location -LiteralPath $reviewRoot
try {
    # Read-only CLI mode for development review; no auth/model/config override.
    & $nodePath $cliEntry --approval-mode plan
    if ($LASTEXITCODE -ne 0) { throw "Gemini CLI exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
