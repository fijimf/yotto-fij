#!/usr/bin/env bash
# Retrain ML models and reload them in the running app without restarting.
#
# Usage (from the server, in the project directory):
#   ./scripts/retrain.sh 2023,2024,2025 2025
#
# Arguments:
#   $1  Comma-separated train seasons  (default: 2023,2024,2025)
#   $2  Test season                    (default: 2025)
#
# Requires ADMIN_USERNAME and ADMIN_PASSWORD in .env (used for HTTP Basic auth
# against POST /admin/ml/reload — see SecurityConfig.httpBasic()).
set -euo pipefail

TRAIN_SEASONS="${1:-2023,2024,2025}"
TEST_SEASON="${2:-2025}"

echo "[retrain] Starting ML model training (train=${TRAIN_SEASONS}, test=${TEST_SEASON})..."
docker compose --profile training run --rm trainer \
  --train-seasons "$TRAIN_SEASONS" \
  --test-season   "$TEST_SEASON"

echo "[retrain] Training complete. Triggering model reload..."
# shellcheck source=.env
source .env
curl -sf \
  --user "${ADMIN_USERNAME}:${ADMIN_PASSWORD}" \
  -X POST "http://localhost/admin/ml/reload" \
  && echo "[retrain] Models reloaded successfully." \
  || echo "[retrain] WARNING: reload request failed. You can reload manually from the Admin dashboard."
