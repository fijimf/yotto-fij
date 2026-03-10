#!/usr/bin/env bash
# Retrain ML models and reload them in the running app without restarting.
#
# Usage (from the server, in the project directory):
#   ./scripts/retrain.sh                    # auto-detect seasons from DB
#   ./scripts/retrain.sh 2025,2026 2026     # explicit: train seasons, test season
#
# Requires ADMIN_USERNAME, ADMIN_PASSWORD, POSTGRES_USER, POSTGRES_PASSWORD,
# POSTGRES_DB in .env (same directory as docker-compose.yml).
set -euo pipefail

# Source .env early — needed for DB discovery and the reload curl
# shellcheck source=.env
source .env

if [ $# -ge 2 ]; then
    TRAIN_SEASONS="$1"
    TEST_SEASON="$2"
else
    echo "[retrain] Discovering seasons with power rating snapshots..."
    SEASONS=$(docker compose exec -T db psql \
        -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        -t -A \
        -c "SELECT DISTINCT s.year
            FROM seasons s
            JOIN team_power_rating_snapshots r ON r.season_id = s.id
            ORDER BY s.year" \
        | grep -v '^$')

    if [ -z "$SEASONS" ]; then
        echo "[retrain] ERROR: No seasons with power rating snapshots found."
        echo "[retrain]        Run 'Power Ratings' from the admin dashboard first."
        exit 1
    fi

    TRAIN_SEASONS=$(echo "$SEASONS" | tr '\n' ',' | sed 's/,$//')
    TEST_SEASON=$(echo "$SEASONS" | tail -1)
    echo "[retrain] Found seasons: ${TRAIN_SEASONS} — using ${TEST_SEASON} as test season."
fi

echo "[retrain] Starting ML model training (train=${TRAIN_SEASONS}, test=${TEST_SEASON})..."
docker compose --profile training run --rm trainer \
  --train-seasons "$TRAIN_SEASONS" \
  --test-season   "$TEST_SEASON"

echo "[retrain] Training complete. Triggering model reload..."
curl -sf \
  --user "${ADMIN_USERNAME}:${ADMIN_PASSWORD}" \
  -X POST "http://localhost/admin/ml/reload" \
  && echo "[retrain] Models reloaded successfully." \
  || echo "[retrain] WARNING: reload request failed. You can reload manually from the Admin dashboard."
