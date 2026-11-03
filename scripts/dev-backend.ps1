# Starts the backend with the secrets from the repo-root .env (git-ignored). Run from the repo root:
#   powershell -ExecutionPolicy Bypass -File scripts/dev-backend.ps1
# Needs PostgreSQL on localhost:5432 with role seed / database paypalseed (see AGENTS.md).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^\s*[^#\s][^=]*=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  if ($value) { [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process') }
}
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/paypalseed'
$env:DATABASE_USER = 'seed'
$env:DATABASE_PASSWORD = 'seed_password'
$env:SEED_ROLE = 'worker'
$env:SPRING_PROFILES_ACTIVE = 'demo,dev'
$env:JAVA_TOOL_OPTIONS = '-Xmx768m'
Set-Location (Join-Path $root 'backend')
.\gradlew.bat :app:bootRun
