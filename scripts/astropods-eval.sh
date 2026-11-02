#!/usr/bin/env bash
# Astropods Agent Blueprint Verification and Eval Harness (POSIX Bash)
# Usage: ./scripts/astropods-eval.sh
set -euo pipefail

echo "=========================================================="
echo "  Astropods Agent Runtime & Guardrail Verification Suite  "
echo "=========================================================="

agents=("coach" "underwriting" "guardian" "trust" "desk-analyst")

echo ""
echo "[1/2] Verifying Agent Blueprints and Schemas under agents/..."
for a in "${agents[@]}"; do
  bp="agents/${a}/blueprint.yaml"
  sp="agents/${a}/prompts/system.prompt"
  ev="agents/${a}/evals/eval-dataset.json"

  if [[ ! -f "$bp" ]] || [[ ! -f "$sp" ]] || [[ ! -f "$ev" ]]; then
    echo "  [MISSING] $a assets missing"
    exit 1
  fi
  name=$(grep '^name:' "$bp" | head -n1 | awk '{print $2}')
  model=$(grep '^model:' "$bp" | head -n1 | awk '{print $2}')
  echo "  [OK] $a ($name): model=$model"
done

echo ""
echo "[2/2] Running Automated Gradle Eval Suite (:modules:agent-gateway:eval)..."
./backend/gradlew -p backend :modules:agent-gateway:eval

echo ""
echo "=========================================================="
echo "  All Astropods Agents, Blueprints & Evals VERIFIED GREEN  "
echo "=========================================================="
