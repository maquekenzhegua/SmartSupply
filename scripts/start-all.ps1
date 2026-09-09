# SmartSupply one-click startup (Windows / PowerShell 5.1+).
#
# Start order (each step gated by a health check, fail fast with log tail):
#   1. docker engine (auto-launch Docker Desktop if needed) + compose up (postgres/redis/langfuse)
#   2. postgres healthy
#   3. ollama serve + embedding model warmup (avoids cold-load 502 on first RAG recall)
#   4. python sidecar via run_sidecar.py (Selector event loop -> Postgres checkpointer works)
#   5. java backend via mvn spring-boot:run (datasource creds + CORS injected)
#   6. frontend vite dev server (port 3001, /api proxied to 8080)
#
# Idempotent: components already listening are assumed healthy and verified, not restarted.
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-all.ps1
#         (or double-click start-all.cmd at repo root)
# Flags:  -SkipLangfuse | -SkipOllama | -SkipFrontend
param(
  [switch]$SkipLangfuse,
  [switch]$SkipOllama,
  [switch]$SkipFrontend
)
# 'Continue' on purpose: PS 5.1 turns native stderr (docker warnings etc.) into error records
# that would otherwise terminate the script under 'Stop'. All failure handling below is based
# on $LASTEXITCODE + explicit health gates instead.
$ErrorActionPreference = 'Continue'
$Root   = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root 'logs'
$RunDir = Join-Path $Root 'run'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
Set-Location $Root

function Info($m) { Write-Host ("[start] " + $m) }
function Ok($m)   { Write-Host ("   [OK] " + $m) -ForegroundColor Green }
function Warn($m) { Write-Host (" [WARN] " + $m) -ForegroundColor Yellow }
function Tail($path, $n) {
  if (Test-Path $path) {
    Write-Host ("   ---- tail " + $path + " ----")
    Get-Content $path -Tail $n | ForEach-Object { Write-Host ("   | " + $_) }
  }
}
function Test-Port($port) {
  $c = New-Object System.Net.Sockets.TcpClient
  try {
    $t = $c.BeginConnect('127.0.0.1', $port, $null, $null)
    if ($t.AsyncWaitHandle.WaitOne(600) -and $c.Connected) { return $true }
    return $false
  } catch { return $false } finally { $c.Close() }
}
function Wait-Port($name, $port, $timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $port) { Ok ($name + " is listening on port " + $port); return $true }
    Start-Sleep -Seconds 2
  }
  Fail ($name + " did not listen on port " + $port + " within " + $timeoutSec + "s"); return $false
}
function Wait-Http($name, $url, $timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $code = & curl.exe -s -o NUL -w "%{http_code}" --max-time 3 $url 2>$null
    if ($LASTEXITCODE -eq 0 -and $code -ne "000" -and $code -ne "") { Ok ($name + " responds HTTP " + $code + " at " + $url); return $true }
    Start-Sleep -Seconds 2
  }
  Fail ($name + " did not respond at " + $url + " within " + $timeoutSec + "s"); return $false
}
function Fail($m) { Write-Host ("  [FAIL] " + $m) -ForegroundColor Red }

# ---------- 0. load .env into process env (children inherit everything) ----------
$envPath = Join-Path $Root '.env'
if (-not (Test-Path $envPath)) { Fail (".env not found at " + $envPath + " - copy .env.example to .env and fill real values"); exit 1 }
$envMap = @{}
Get-Content $envPath -Encoding UTF8 | ForEach-Object {
  $line = $_.Trim()
  if ($line -eq '' -or $line.StartsWith('#')) { return }
  $i = $line.IndexOf('=')
  if ($i -lt 1) { return }
  $k = $line.Substring(0, $i).Trim()
  $v = $line.Substring($i + 1).Trim()
  if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'")))) {
    $v = $v.Substring(1, $v.Length - 2)
  }
  $envMap[$k] = $v
}
foreach ($k in $envMap.Keys) { Set-Item -Path ("Env:" + $k) -Value $envMap[$k] }
Info ("loaded " + $envMap.Count + " keys from .env")

# ---------- 1. docker engine + compose ----------
# native commands run through `cmd /c "... 2>&1"` so stderr is merged inside cmd and
# never surfaces as a PS error record
Info "checking docker engine..."
$engineUp = $false
for ($i = 0; $i -lt 5; $i++) {
  cmd /c "docker info >nul 2>&1"
  if ($LASTEXITCODE -eq 0) { $engineUp = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $engineUp) {
  $dd = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
  if (Test-Path $dd) {
    Warn "docker engine not reachable - launching Docker Desktop (first engine start can take 1-3 min)..."
    Start-Process $dd | Out-Null
  } else {
    Fail "docker engine not reachable and Docker Desktop.exe not found at default path - start Docker manually and retry"
    exit 1
  }
  $deadline = (Get-Date).AddSeconds(240)
  while ((Get-Date) -lt $deadline) {
    cmd /c "docker info >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 5
  }
  cmd /c "docker info >nul 2>&1"
  if ($LASTEXITCODE -ne 0) { Fail "docker engine did not come up within 240s"; exit 1 }
}
Ok "docker engine is up"

$composeArgs = @()
if (-not $SkipLangfuse) { $composeArgs = @('--profile','obs') }
$profilePart = ($composeArgs -join ' ')
Info ("docker compose up -d " + $profilePart + " (postgres/redis" + $(if (-not $SkipLangfuse) { "/langfuse" }) + ")...")
cmd /c ("docker compose " + $profilePart + " up -d 2>&1") | ForEach-Object { Write-Host ("   | " + $_) }
if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed"; exit 1 }

$deadline = (Get-Date).AddSeconds(120)
while ($true) {
  $h = (cmd /c "docker inspect --format {{.State.Health.Status}} smartsupply-postgres 2>nul") -join ""
  if ($h -match "healthy") { break }
  if ((Get-Date) -gt $deadline) {
    Fail "postgres container not healthy within 120s"
    cmd /c "docker logs --tail 20 smartsupply-postgres 2>&1" | ForEach-Object { Write-Host ("   | " + $_) }
    exit 1
  }
  Start-Sleep -Seconds 3
}
Ok "postgres healthy (ready for Flyway)"

# ---------- 2. ollama + embedding warmup ----------
$ollamaExe = $null
$g = Get-Command ollama -ErrorAction SilentlyContinue
if ($g) { $ollamaExe = $g.Source }
if (-not $ollamaExe) {
  $cand = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
  if (Test-Path $cand) { $ollamaExe = $cand }
}
if ($SkipOllama) {
  Info "skipping ollama (-SkipOllama)"
} elseif (Test-Port 11434) {
  Ok "ollama already listening on 11434"
} elseif (-not $ollamaExe) {
  Fail "ollama not found (needed for local embeddings/RAG recall); install ollama or rerun with -SkipOllama if EMBEDDING_MOCK=true"
  exit 1
} else {
  Info "starting ollama serve..."
  Start-Process -FilePath $ollamaExe -ArgumentList 'serve' `
    -RedirectStandardOutput (Join-Path $LogDir 'ollama.log') `
    -RedirectStandardError  (Join-Path $LogDir 'ollama.err.log') `
    -WindowStyle Hidden | Out-Null
  if (-not (Wait-Port 'ollama' 11434 60)) { Tail (Join-Path $LogDir 'ollama.err.log') 40; exit 1 }
}
$embModel = $envMap['EMBEDDING_MODEL']
if (-not $SkipOllama -and $embModel -and $envMap['EMBEDDING_MOCK'] -ne 'true') {
  Info ("warming up embedding model '" + $embModel + "' (first load can take ~10-60s, prevents cold-start 502 on first RAG recall)...")
  # Invoke-RestMethod instead of curl.exe: PS 5.1 strips embedded double quotes when
  # marshalling string args to native exes, which mangles the JSON body (ollama returned 400)
  try {
    $r = Invoke-RestMethod -Uri 'http://localhost:11434/api/embeddings' -Method Post -ContentType 'application/json' `
      -Body ('{"model":"' + $embModel + '","prompt":"warmup"}') -TimeoutSec 180
    if ($r.embedding) { Ok ("embedding model '" + $embModel + "' loaded into memory") }
    else { Warn "embedding warmup returned no embedding vector - check ollama logs" }
  } catch {
    Warn ("embedding warmup failed: " + $_.Exception.Message + " - model may not be pulled yet (ollama pull " + $embModel + "); first RAG query will be slow or recall degraded")
  }
}

# ---------- 3. python sidecar ----------
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { Fail "python not found on PATH (sidecar needs python + requirements installed)"; exit 1 }
if (Test-Port 8001) {
  Info "port 8001 busy - sidecar assumed already running"
} else {
  Info "starting python sidecar (run_sidecar.py: Selector loop so Postgres checkpointer works on Windows)..."
  $p = Start-Process -FilePath $py -ArgumentList 'run_sidecar.py' `
    -WorkingDirectory (Join-Path $Root 'agent-python') `
    -RedirectStandardOutput (Join-Path $LogDir 'sidecar.log') `
    -RedirectStandardError  (Join-Path $LogDir 'sidecar.err.log') `
    -WindowStyle Hidden -PassThru
  Set-Content -Path (Join-Path $RunDir 'sidecar.pid') -Value $p.Id
}
if (-not (Wait-Http 'sidecar' 'http://localhost:8001/health' 90)) {
  Tail (Join-Path $LogDir 'sidecar.err.log') 40; Tail (Join-Path $LogDir 'sidecar.log') 40; exit 1
}

# ---------- 4. java backend ----------
$mvn = $null
foreach ($c in @('mvn.cmd','mvn.bat','mvn')) { $g = Get-Command $c -ErrorAction SilentlyContinue; if ($g) { $mvn = $g.Source; break } }
if (-not $mvn) { Fail "maven (mvn) not found on PATH"; exit 1 }
# application.yml defaults (smartsupply/smartsupply123) do NOT match the dev database created by docker-compose (dev / change-me-strong-password)
if ($envMap.ContainsKey('POSTGRES_USER'))     { Set-Item Env:SPRING_DATASOURCE_USERNAME $envMap['POSTGRES_USER'] }
else                                          { Set-Item Env:SPRING_DATASOURCE_USERNAME 'dev' }
if ($envMap.ContainsKey('POSTGRES_PASSWORD')) { Set-Item Env:SPRING_DATASOURCE_PASSWORD $envMap['POSTGRES_PASSWORD'] }
else                                          { Set-Item Env:SPRING_DATASOURCE_PASSWORD 'change-me-strong-password' }
# vite dev runs on 3001 (3000 is taken by langfuse); keep 3000 origins too for the packaged frontend
Set-Item Env:CORS_ALLOWED_ORIGINS 'http://localhost:3001,http://127.0.0.1:3001,http://localhost:3000,http://127.0.0.1:3000'
if (Test-Port 8080) {
  Info "port 8080 busy - backend assumed already running"
} else {
  Info "starting java backend (mvn spring-boot:run, typically 30-60s incl. Flyway)..."
  $p = Start-Process -FilePath $mvn -ArgumentList 'spring-boot:run' `
    -WorkingDirectory (Join-Path $Root 'backend') `
    -RedirectStandardOutput (Join-Path $LogDir 'backend.log') `
    -RedirectStandardError  (Join-Path $LogDir 'backend.err.log') `
    -WindowStyle Hidden -PassThru
  Set-Content -Path (Join-Path $RunDir 'backend.pid') -Value $p.Id
}
if (-not (Wait-Http 'backend' 'http://localhost:8080/' 240)) {
  Tail (Join-Path $LogDir 'backend.err.log') 30; Tail (Join-Path $LogDir 'backend.log') 60; exit 1
}

# ---------- 5. frontend ----------
if ($SkipFrontend) {
  Info "skipping frontend (-SkipFrontend)"
} else {
  if (Test-Port 3001) {
    Info "port 3001 busy - frontend assumed already running"
  } else {
    $npm = $null
    foreach ($c in @('npm.cmd','npm')) { $g = Get-Command $c -ErrorAction SilentlyContinue; if ($g) { $npm = $g.Source; break } }
    if (-not $npm) { Fail "npm not found on PATH (frontend needs node + node_modules installed)"; exit 1 }
    Info "starting frontend dev server (vite on 3001, /api proxied to 8080)..."
    $p = Start-Process -FilePath $npm -ArgumentList 'run','dev' `
      -WorkingDirectory (Join-Path $Root 'frontend') `
      -RedirectStandardOutput (Join-Path $LogDir 'frontend.log') `
      -RedirectStandardError  (Join-Path $LogDir 'frontend.err.log') `
      -WindowStyle Hidden -PassThru
    Set-Content -Path (Join-Path $RunDir 'frontend.pid') -Value $p.Id
  }
  if (-not (Wait-Http 'frontend' 'http://localhost:3001/' 120)) {
    Tail (Join-Path $LogDir 'frontend.err.log') 40; Tail (Join-Path $LogDir 'frontend.log') 40; exit 1
  }
}

# ---------- 6. summary + smoke ----------
Info "smoke check: backend login"
try {
  $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method Post -ContentType 'application/json' `
    -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 10
  if ($r.code -eq 200) { Ok "backend login works (admin/admin123)" }
  else { Warn ("login smoke returned business code " + $r.code + " - verify manually at http://localhost:3001") }
} catch {
  Warn ("login smoke check failed: " + $_.Exception.Message)
}

Write-Host ""
Write-Host "================= SmartSupply stack is UP =================" -ForegroundColor Cyan
Write-Host "  frontend : http://localhost:3001    login admin / admin123"
Write-Host "  backend  : http://localhost:8080    logs\backend.log"
Write-Host "  sidecar  : http://localhost:8001/health   logs\sidecar.log"
Write-Host "  postgres : localhost:5432/smartsupply (docker, volume kept)"
Write-Host "  redis    : localhost:6379 (docker)"
if (-not $SkipLangfuse) { Write-Host "  langfuse : http://localhost:3000    demo@smartsupply.local / smartsupply-demo" }
Write-Host "  ollama   : localhost:11434 (embedding: " $envMap['EMBEDDING_MODEL'] ")"
Write-Host "  stop all : scripts\stop-all.cmd      status: scripts\status-all.cmd"
Write-Host "===========================================================" -ForegroundColor Cyan
