$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repoRoot

mvn -Dtest=ApplicationSchemeExperimentTest test

$reportPath = Join-Path $repoRoot 'target/experiments/application-scheme-experiments.json'
$report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($report.status -ne 'PASS') {
    throw "Application scheme experiments did not pass: $($report.status)"
}

$report | Select-Object suite, status, caseCount, passedCount, failedCount, passRate | Format-List
