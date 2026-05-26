#!/usr/bin/env bash
# Load .env vars and start the dev server.
# Usage: ./run-dev.sh

set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "ERROR: .env not found. Copy .env.example to .env and fill values."
    exit 1
fi

set -a
source .env
set +a

echo "Starting TiktokRerun API server with env loaded from .env"
exec go run .
