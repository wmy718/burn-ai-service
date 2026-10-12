param([ValidateSet('backend','ui')][string]$Service, [ValidateSet('start','stop','status')][string]$Action)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$project = Join-Path $repoRoot $(if ($Service -eq 'backend') { 'burn-ai-service' } else { 'burn-ai-service-test-ui' })
$runtime = Join-Path $project '.runtime'
$pidPath = Join-Path $runtime 'service.pid'
$port = if ($Service -eq 'backend') { if ($env:BURN_AI_PORT) { [int]$env:BURN_AI_PORT } else { 8091 } } else { if ($env:TEST_UI_PORT) { [int]$env:TEST_UI_PORT } else { 5179 } }
function Get-OwnedProcess {
    if (-not (Test-Path $pidPath)) { return $null }
    $servicePid = 0
    if (-not [int]::TryParse((Get-Content $pidPath -Raw).Trim(), [ref]$servicePid)) { throw 'Invalid PID file.' }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid" -ErrorAction SilentlyContinue
    if ($processInfo -and (-not $processInfo.CommandLine -or -not $processInfo.CommandLine.Contains($project))) { throw 'PID belongs to another application. Refusing to use it.' }
    return $processInfo
}
function Find-Tool([string]$override, [string]$name) {
    $candidate = if ($override) { $override } else { $name }
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if (-not $command) { throw "Missing $name. Install it and add it to PATH, or configure its executable path." }
    return $command.Source
}
try {
    $existing = Get-OwnedProcess
    if ($Action -eq 'stop') {
        if ($existing) { Stop-Process -Id $existing.ProcessId -Force; Write-Host '[OK] Service stopped.' } else { Write-Host '[INFO] Service is not running.' }
        Remove-Item -LiteralPath $pidPath -ErrorAction SilentlyContinue
        exit 0
    }
    if ($Action -eq 'status') {
        if ($existing) { Write-Host "[OK] Running, PID $($existing.ProcessId), http://127.0.0.1:$port" } else { Write-Host '[INFO] Service is not running.' }
        exit 0
    }
    if ($existing) { Write-Host "[OK] Already running at http://127.0.0.1:$port"; exit 0 }
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use. Stop its owner or change the configured port." }
    New-Item -ItemType Directory -Force $runtime | Out-Null
    if ($Service -eq 'backend') {
        $javaExe = Find-Tool $env:JAVA_CMD 'java.exe'
        $mavenExe = Find-Tool $env:MAVEN_CMD 'mvn.cmd'
        Push-Location $project
        try { & $mavenExe --batch-mode --no-transfer-progress package -DskipTests; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' } } finally { Pop-Location }
        $jar = Join-Path $project 'target/burn-ai-service-0.0.1-SNAPSHOT.jar'
        $exe = $javaExe; $arguments = '-jar "' + $jar + '"'
    } else {
        $exe = Find-Tool $env:NODE_CMD 'node.exe'
        $arguments = '"' + (Join-Path $project 'server.js') + '"'
        if (-not $env:BURN_AI_BACKEND) { $env:BURN_AI_BACKEND = 'http://127.0.0.1:8091' }
    }
    $process = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log') -PassThru
    Set-Content -LiteralPath $pidPath -Value $process.Id -Encoding ascii
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $process.Refresh()
        if ($process.HasExited) { throw "Service exited. See $runtime\stderr.log and stdout.log." }
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $process.Id
        if ($listener) { Write-Host "[OK] Started at http://127.0.0.1:$port"; Write-Host "[INFO] Logs: $runtime"; exit 0 }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidPath -ErrorAction SilentlyContinue
    throw "Service did not listen on port $port within 45 seconds. Check $runtime."
} catch { Write-Host ('[ERROR] ' + $_.Exception.Message) -ForegroundColor Red; exit 1 }
