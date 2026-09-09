# SmartSupply health status (Windows / PowerShell 5.1+).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-all.ps1
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Stat($name, $port, $url) {
  $listen = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  $code = "-"
  if ($url) { $code = (& curl.exe -s -o NUL -w "%{http_code}" --max-time 3 $url 2>$null) }
  if ($listen) {
    $pids = ($listen | Select-Object -ExpandProperty OwningProcess -Unique) -join ','
    Write-Host ("  [UP]   {0,-10} port {1,-6} pid {2,-10} http {3}" -f $name, $port, $pids, $code)
  } else {
    Write-Host ("  [DOWN] {0,-10} port {1,-6}" -f $name, $port) -ForegroundColor Yellow
  }
}

Write-Host "[status] SmartSupply stack"
Stat 'frontend' 3001 'http://localhost:3001/'
Stat 'backend'  8080 'http://localhost:8080/'
Stat 'sidecar'  8001 'http://localhost:8001/health'
Stat 'ollama'  11434 ''
$pg = docker inspect --format "{{.State.Health.Status}}" smartsupply-postgres 2>$null
$rd = docker inspect --format "{{.State.Status}}" smartsupply-redis 2>$null
$lf = docker inspect --format "{{.State.Status}}" smartsupply-langfuse 2>$null
Write-Host ("  postgres container: " + $(if ($pg) { $pg } else { "stopped" }))
Write-Host ("  redis    container: " + $(if ($rd) { $rd } else { "stopped" }))
Write-Host ("  langfuse container: " + $(if ($lf) { $lf } else { "stopped" }))
Write-Host ""
Write-Host "  URLs: frontend http://localhost:3001 (admin/admin123) | langfuse http://localhost:3000 (demo@smartsupply.local/smartsupply-demo)"
