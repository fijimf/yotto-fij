#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/.."

REMOTE_USER="${DEPLOY_USER:-admin}"
REMOTE_HOST="${DEPLOY_HOST:-fijimf.com}"
REMOTE_DIR="${DEPLOY_DIR:-/home/admin/deepfij}"

IMAGE_NAME="yotto-app:latest"

echo "==> Building Docker image..."
docker build --platform linux/amd64 -t "${IMAGE_NAME}" "${PROJECT_DIR}"

echo "==> Transferring image to ${REMOTE_HOST}..."
docker save "${IMAGE_NAME}" | gzip | ssh "${REMOTE_USER}@${REMOTE_HOST}" 'gunzip | docker load'

echo "==> Copying configuration files..."
ssh "${REMOTE_USER}@${REMOTE_HOST}" "mkdir -p ${REMOTE_DIR}/config ${REMOTE_DIR}/scripts"
scp "${PROJECT_DIR}/.env" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/.env"
scp "${PROJECT_DIR}/config/mysite" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/config/mysite"
scp "${PROJECT_DIR}/docker-compose.yml" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/docker-compose.yml"
scp "${SCRIPT_DIR}/Dockerfile.trainer" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/scripts/Dockerfile.trainer"
scp "${SCRIPT_DIR}/requirements.txt" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/scripts/requirements.txt"
scp "${SCRIPT_DIR}/train_models.py" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/scripts/train_models.py"
scp "${SCRIPT_DIR}/retrain.sh" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/scripts/retrain.sh"
ssh "${REMOTE_USER}@${REMOTE_HOST}" "chmod 600 ${REMOTE_DIR}/.env && chmod +x ${REMOTE_DIR}/scripts/retrain.sh"

echo "==> Copying Netdata configuration..."
ssh "${REMOTE_USER}@${REMOTE_HOST}" "mkdir -p ${REMOTE_DIR}/netdata"
scp -r "${PROJECT_DIR}/netdata/go.d" "${PROJECT_DIR}/netdata/health.d" \
    "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/netdata/"
scp "${PROJECT_DIR}/netdata/health_alarm_notify.conf" \
    "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/netdata/health_alarm_notify.conf"
# Render the postgres collector config from .env (the DSN embeds the netdata role password,
# so postgres.conf is generated server-side rather than committed).
ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_DIR} && set -a && . ./.env && set +a \
    && sed -e \"s|__NETDATA_DB_PASSWORD__|\${NETDATA_DB_PASSWORD}|\" \
           -e \"s|__POSTGRES_DB__|\${POSTGRES_DB}|\" \
           netdata/go.d/postgres.conf.template > netdata/go.d/postgres.conf \
    && chmod 600 netdata/go.d/postgres.conf"

echo "==> Restarting services on ${REMOTE_HOST}..."
# Build trainer image first (while app is still running) to minimise downtime.
# docker compose build works on profiled services when named explicitly.
ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose build trainer && docker compose down && docker compose up -d"

echo "==> Deploy complete."
