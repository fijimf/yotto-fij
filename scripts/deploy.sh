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

echo "==> Restarting services on ${REMOTE_HOST}..."
ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose down && docker compose build trainer && docker compose up -d"

echo "==> Deploy complete."
