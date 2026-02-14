#!/bin/bash

CONTAINER_NAME="basketball-postgres"

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Stopping PostgreSQL container '${CONTAINER_NAME}'..."
    docker stop ${CONTAINER_NAME}
    echo "Container stopped."
else
    echo "PostgreSQL container '${CONTAINER_NAME}' is not running."
fi
