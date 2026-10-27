param([string]$Python, [switch]$Browser)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$onWindows = $env:OS -eq 'Windows_NT'
if (-not $Python) {
    $relativePython = if ($onWindows) { 'device-agent/.venv/Scripts/python.exe' } else { 'device-agent/.venv/bin/python' }
    $Python = Join-Path $projectRoot $relativePython
}

try {
    foreach ($requiredTool in @($Python, 'java', 'docker', 'node', 'npm')) {
        if (-not (Get-Command $requiredTool -ErrorAction SilentlyContinue)) {
            throw "Missing required tool: $requiredTool. Follow the README setup instructions."
        }
    }
    $Python = (Get-Command $Python).Source
    $nodeVersion = [version](& node -p 'process.versions.node')
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -lt [version]'24.15' -or $nodeVersion.Major -ne 24) { throw 'Node 24.15+ (24.x) is required; see web/.nvmrc.' }
    $npmVersion = (& npm --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $npmVersion -notmatch '^11\.') { throw 'npm 11 is required for the frontend lockfile setup.' }
    if (-not (Test-Path (Join-Path $projectRoot 'web/node_modules'))) { throw 'Frontend dependencies missing: run npm ci from web/.' }
    & $Python -c 'import sys; assert sys.version_info >= (3,12), "Python 3.12+ required"; import pytest, yaml, simplejson, openapi_spec_validator, openapi_schema_validator'
    if ($LASTEXITCODE -ne 0) { throw 'Python setup incomplete: install device-agent[dev] in the selected environment.' }
    $javaVersion = (& java --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(openjdk|java) 21[. ]') { throw 'Java 21 is required on PATH.' }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable. Start Docker Desktop before verification.' }

    Push-Location $projectRoot
    try {
        & $Python -m pytest device-agent/tests
        if ($LASTEXITCODE -ne 0) { throw 'Python/contract verification failed.' }
        Push-Location server
        try {
            if ($onWindows) { & .\mvnw.cmd --batch-mode test }
            else { & bash ./mvnw --batch-mode test }
            if ($LASTEXITCODE -ne 0) { throw 'Java/PostgreSQL verification failed.' }
            & $Python ../scripts/verify_diagnostic_http.py
            if ($LASTEXITCODE -ne 0) { throw 'Actual diagnostic HTTP contract verification failed.' }
            & $Python ../scripts/verify_maintenance_http.py
            if ($LASTEXITCODE -ne 0) { throw 'Actual maintenance HTTP contract verification failed.' }
            if ($onWindows) { & .\mvnw.cmd --batch-mode test '-Dtest=OutboxTelemetryRecoveryDemo' "-Dautopulse.python=$Python" }
            else { & bash ./mvnw --batch-mode test '-Dtest=OutboxTelemetryRecoveryDemo' "-Dautopulse.python=$Python" }
            if ($LASTEXITCODE -ne 0) { throw 'Isolated device outbox verification failed.' }
            if ($Browser) {
                if ($onWindows) { & .\mvnw.cmd --batch-mode test '-Dtest=DashboardBrowserDemo,TripMilestoneDemo,NumericAnalyticsDemo,CareBrowserDemo,CareMilestoneDemo' "-Dautopulse.python=$Python" }
                else { & bash ./mvnw --batch-mode test '-Dtest=DashboardBrowserDemo,TripMilestoneDemo,NumericAnalyticsDemo,CareBrowserDemo,CareMilestoneDemo' "-Dautopulse.python=$Python" }
                if ($LASTEXITCODE -ne 0) { throw 'Isolated browser verification failed. See server/target/dashboard-browser.log, server/target/trip-milestone-browser.log, server/target/numeric-analytics-browser.log, server/target/care-browser.log, server/target/care-milestone-*-browser.log and Surefire reports. If Chromium is missing, run npm exec -- playwright install chromium in web/.' }
            }
        } finally { Pop-Location }
        Push-Location web
        try {
            foreach ($check in @('typecheck', 'lint', 'test', 'build')) {
                & npm run $check
                if ($LASTEXITCODE -ne 0) { throw "Frontend $check failed." }
            }
        } finally { Pop-Location }
    } finally { Pop-Location }
    Write-Host 'Python, contract, PostgreSQL and frontend checks passed.'
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
