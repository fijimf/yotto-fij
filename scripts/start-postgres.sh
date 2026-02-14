#!/bin/bash

# PostgreSQL Docker Configuration
CONTAINER_NAME="basketball-postgres"
POSTGRES_USER="basketball_user"
POSTGRES_PASSWORD="***REDACTED***"
POSTGRES_DB="basketball_db"
POSTGRES_PORT="5432"

# Check if container already exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    # Check if container is running
    if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        echo "PostgreSQL container '${CONTAINER_NAME}' is already running."
    else
        echo "Starting existing PostgreSQL container '${CONTAINER_NAME}'..."
        docker start ${CONTAINER_NAME}
    fi
else
    echo "Creating and starting new PostgreSQL container '${CONTAINER_NAME}'..."
    docker run -d \
        --name ${CONTAINER_NAME} \
        -e POSTGRES_USER=${POSTGRES_USER} \
        -e POSTGRES_PASSWORD=${POSTGRES_PASSWORD} \
        -e POSTGRES_DB=${POSTGRES_DB} \
        -p ${POSTGRES_PORT}:5432 \
        -v basketball-postgres-data:/var/lib/postgresql/data \
        postgres:16-alpine
fi

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker exec ${CONTAINER_NAME} pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB} > /dev/null 2>&1; then
        echo "PostgreSQL is ready!"
        echo ""
        echo "Connection details:"
        echo "  Host:     localhost"
        echo "  Port:     ${POSTGRES_PORT}"
        echo "  Database: ${POSTGRES_DB}"
        echo "  Username: ${POSTGRES_USER}"
        echo "  Password: ${POSTGRES_PASSWORD}"
        echo ""
        echo "Run the application with: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
        exit 0
    fi
    sleep 1
done

echo "PostgreSQL failed to start within 30 seconds."
exit 1
