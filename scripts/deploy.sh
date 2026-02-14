#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/.."

REMOTE_USER="${DEPLOY_USER:-ubuntu}"
REMOTE_HOST="${DEPLOY_HOST:?Set DEPLOY_HOST to your server address}"
REMOTE_DIR="${DEPLOY_DIR:-/opt/yotto-fij}"

IMAGE_NAME="yotto-app:latest"

echo "==> Building Docker image..."
docker build -t "${IMAGE_NAME}" "${PROJECT_DIR}"

echo "==> Transferring image to ${REMOTE_HOST}..."
docker save "${IMAGE_NAME}" | gzip | ssh "${REMOTE_USER}@${REMOTE_HOST}" 'gunzip | docker load'

echo "==> Copying configuration files..."
ssh "${REMOTE_USER}@${REMOTE_HOST}" "mkdir -p ${REMOTE_DIR}/config"
scp "${PROJECT_DIR}/.env" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/.env"
scp "${PROJECT_DIR}/config/mysite" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/config/mysite"
scp "${PROJECT_DIR}/docker-compose.yml" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/docker-compose.yml"
ssh "${REMOTE_USER}@${REMOTE_HOST}" "chmod 600 ${REMOTE_DIR}/.env"

echo "==> Restarting services on ${REMOTE_HOST}..."
ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose down && docker compose up -d"

echo "==> Deploy complete."
