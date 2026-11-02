# Astropods Agent Blueprint Verification and Eval Harness (PowerShell)
# Usage: .\scripts\astropods-eval.ps1

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Astropods Agent Runtime & Guardrail Verification Suite  " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$agentDirs = @("coach", "underwriting", "guardian", "trust", "desk-analyst")
$allValid = $true

Write-Host "`n[1/2] Verifying Agent Blueprints and Schemas under agents/..." -ForegroundColor Yellow
foreach ($agent in $agentDirs) {
    $blueprintPath = Join-Path "agents" (Join-Path $agent "blueprint.yaml")
    $promptPath = Join-Path "agents" (Join-Path $agent "prompts\system.prompt")
    $evalPath = Join-Path "agents" (Join-Path $agent "evals\eval-dataset.json")

    if (-not (Test-Path $blueprintPath)) {
        Write-Host "  [MISSING] $blueprintPath" -ForegroundColor Red
        $allValid = $false
        continue
    }
    if (-not (Test-Path $promptPath)) {
        Write-Host "  [MISSING] $promptPath" -ForegroundColor Red
        $allValid = $false
        continue
    }
    if (-not (Test-Path $evalPath)) {
        Write-Host "  [MISSING] $evalPath" -ForegroundColor Red
        $allValid = $false
        continue
    }

    $name = (Get-Content $blueprintPath | Select-String "^name:\s*(.+)").Matches.Groups[1].Value.Trim()
    $model = (Get-Content $blueprintPath | Select-String "^model:\s*(.+)").Matches.Groups[1].Value.Trim()
    $role = (Get-Content $blueprintPath | Select-String "^role:\s*(.+)").Matches.Groups[1].Value.Trim()

    Write-Host "  [OK] $agent ($name): model=$model, role=$role" -ForegroundColor Green
}

if (-not $allValid) {
    Write-Host "`nBlueprint validation failed!" -ForegroundColor Red
    exit 1
}

Write-Host "`n[2/2] Running Automated Gradle Eval Suite (:modules:agent-gateway:eval)..." -ForegroundColor Yellow
$gradleCmd = ".\backend\gradlew.bat"
if (-not (Test-Path $gradleCmd)) {
    $gradleCmd = "gradle"
}

& $gradleCmd -p backend :modules:agent-gateway:eval
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nEval suite failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  All Astropods Agents, Blueprints & Evals VERIFIED GREEN  " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
