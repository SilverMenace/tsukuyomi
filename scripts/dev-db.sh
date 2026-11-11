#!/bin/sh
# Starts (or restarts) the local development database in Docker.
# Matches backend/src/main/resources/application-dev.properties.
# Tests do not use this; they start their own throwaway PostgreSQL.
#
#   scripts/dev-db.sh          start
#   scripts/dev-db.sh reset    delete all local data and start fresh
set -e
NAME=tsukuyomi-dev-db
if [ "$1" = "reset" ]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
fi
if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  docker start "$NAME" >/dev/null
else
  docker run -d --name "$NAME" -p 5433:5432 \
    -e POSTGRES_USER=tsukuyomi -e POSTGRES_PASSWORD=tsukuyomi -e POSTGRES_DB=tsukuyomi \
    postgres:18-alpine >/dev/null
fi
echo "PostgreSQL is starting on localhost:5433 (user/password/database: tsukuyomi)"
