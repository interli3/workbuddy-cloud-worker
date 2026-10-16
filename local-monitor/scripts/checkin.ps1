$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir

node src/cli.js sync-auth
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
node src/cli.js checkin-all
exit $LASTEXITCODE
