# SmartSupply one-click stop (Windows / PowerShell 5.1+).
#
# Stops in reverse dependency order:
#   - app processes: frontend(3001), backend(8080), sidecar(8001), ollama(11434)
#     (kills by recorded pid file, then by listening port, then by command-line sweep)
#   - docker containers via compose down (postgres/redis/langfuse; named volumes are KEPT, data survives)
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-all.ps1
#        (or double-click stop-all.cmd at repo root)
$ErrorActionPreference = 'Continue'
$Root   = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root 'run'
Set-Location $Root

function Kill-Tree($procId, $label) {
  if (-not $procId) { return }
  Write-Host ("   killing " + $label + " (pid " + $procId + ", process tree)")
  taskkill /PID $procId /T /F *> $null
}

Write-Host "[stop] SmartSupply stack..."

# 1. pid files (only if the pid still is the expected process - never kill a recycled pid)
$expect = @{ 'sidecar.pid' = 'python'; 'backend.pid' = 'java|mvn|cmd'; 'frontend.pid' = 'node|cmd|npm'; 'ollama.pid' = 'ollama' }
if (Test-Path $RunDir) {
  Get-ChildItem $RunDir -Filter '*.pid' -ErrorAction SilentlyContinue | ForEach-Object {
    $pidFromFile = Get-Content $_.FullName -ErrorAction SilentlyContinue
    $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $pidFromFile) -ErrorAction SilentlyContinue
    if ($proc -and $proc.Name -match $expect[$_.Name]) { Kill-Tree $proc.ProcessId ($_.BaseName) }
    Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
  }
}

# 2. anything still listening on our ports
foreach ($port in @(3001, 8080, 8001, 11434)) {
  $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique
  foreach ($opid in $owners) {
    if (-not $opid -or $opid -eq 0) { continue }
    $pname = (Get-Process -Id $opid -ErrorAction SilentlyContinue).ProcessName
    if ($port -eq 11434 -and $pname -notmatch 'ollama') { Write-Host ("   [skip] port 11434 owned by " + $pname + " (not ollama) - left alone"); continue }
    Kill-Tree $opid ("port " + $port + " owner " + $pname)
  }
}

# 3. stragglers without a listening port (mvn wrapper, npm cmd wrapper, ollama tray, runner children)
$strays = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  ($_.Name -eq 'java.exe'  -and $_.CommandLine -match 'spring-boot') -or
  ($_.Name -eq 'python.exe' -and $_.CommandLine -match 'run_sidecar') -or
  ($_.Name -match '^node'  -and $_.CommandLine -match 'vite') -or
  ($_.Name -match '^ollama') -or
  ($_.Name -eq 'cmd.exe'   -and $_.CommandLine -match 'npm run dev')
}
foreach ($s in $strays) { Kill-Tree $s.ProcessId ("stray " + $s.Name) }

# 4. docker containers (volumes kept)
Write-Host "   docker compose down (containers removed, data volumes kept)"
cmd /c "docker compose --profile obs --profile deep down 2>&1" | ForEach-Object { Write-Host ("   | " + $_) }

# 5. verify
Start-Sleep -Seconds 2
$left = Get-NetTCPConnection -LocalPort @(3001, 8080, 8001, 11434) -State Listen -ErrorAction SilentlyContinue
if ($left) {
  Write-Host " [WARN] some ports still listening:" -ForegroundColor Yellow
  $left | ForEach-Object { Write-Host ("   port " + $_.LocalPort + " pid " + $_.OwningProcess) }
} else {
  Write-Host "   [OK] ports 3001 / 8080 / 8001 / 11434 all free"
}
$names = docker ps --format "{{.Names}}" 2>$null
if ($names) { Write-Host (" [WARN] docker still running: " + ($names -join ', ')) -ForegroundColor Yellow }
else { Write-Host "   [OK] no smartsupply docker containers running" }
Write-Host "[stop] done. Start again anytime with start-all.cmd"
