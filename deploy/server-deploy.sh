#!/usr/bin/env bash
# server-deploy.sh — runs ON THE VPS after `git pull`. Builds the Go server
# and the two SPAs, swaps the binary, syncs static files into the CWP-served
# folders, then restarts and health-checks the systemd unit.
#
# Invoked by the GitHub Actions workflow via SSH:
#     cd ~/rerun-src && bash deploy/server-deploy.sh
#
# Runs as `reruni` (no sudo for build/file-copy; uses NOPASSWD sudo only for
# `systemctl restart rerun-server` per /etc/sudoers.d/reruni-deploy).

set -euo pipefail

# Make sure go + bun are visible under non-login ssh sessions.
export PATH=/usr/local/go/bin:/usr/local/bin:$PATH

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
HEALTH_URL="http://127.0.0.1:18080/api/health"
HEALTH_TIMEOUT_S=30

GIT_SHA="$(cd "$REPO_DIR" && git rev-parse --short HEAD)"
echo "=== deploying $GIT_SHA at $(date -Iseconds) ==="

# -----------------------------------------------------------------------------
# 1. Build server (linux/amd64, stripped binary)
# -----------------------------------------------------------------------------
echo "--- building server ---"
cd "$REPO_DIR/server"
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.gitSHA=${GIT_SHA}" \
    -o /tmp/rerun-server.new .

# -----------------------------------------------------------------------------
# 2. Build SPAs (portal + backoffice) — install + build in parallel
# -----------------------------------------------------------------------------
# VITE_API_BASE_URL tells the SPAs which origin to fetch from. Without it the
# bundled fetch hits the SPA's own host (app/admin.reruni.com) which only
# serves static files — every API call returns nginx 405 / SPA index.html.
export VITE_API_BASE_URL="https://api.reruni.com"

echo "--- building portal ---"
cd "$REPO_DIR/portal"
bun install --frozen-lockfile --no-progress
bun run build

echo "--- building backoffice ---"
cd "$REPO_DIR/backoffice"
bun install --frozen-lockfile --no-progress
bun run build

# -----------------------------------------------------------------------------
# 3. Atomic binary swap + restart
# -----------------------------------------------------------------------------
echo "--- installing new binary ---"
mv -f /tmp/rerun-server.new /home/reruni/rerun-bin/server

# -----------------------------------------------------------------------------
# 4. Rsync static SPA bundles into CWP-served folders
# -----------------------------------------------------------------------------
if [[ -d "$REPO_DIR/landing" ]]; then
    echo "--- syncing landing → /home/reruni/public_html ---"
    rsync -a --delete --exclude='backupcwp/' --exclude='cwp_stats/' \
        "$REPO_DIR/landing/" /home/reruni/public_html/
else
    echo "--- landing/ not in sparse-checkout, skipping ---"
fi

echo "--- syncing portal → /home/reruni/app.reruni.com ---"
rsync -a --delete "$REPO_DIR/portal/dist/"     /home/reruni/app.reruni.com/

echo "--- syncing backoffice → /home/reruni/admin.reruni.com ---"
rsync -a --delete "$REPO_DIR/backoffice/dist/" /home/reruni/admin.reruni.com/

# -----------------------------------------------------------------------------
# 5. Restart Go service (goose migrations run on boot)
# -----------------------------------------------------------------------------
echo "--- restarting rerun-server ---"
sudo /usr/bin/systemctl restart rerun-server

# -----------------------------------------------------------------------------
# 6. Health check
# -----------------------------------------------------------------------------
echo "--- waiting for health (max ${HEALTH_TIMEOUT_S}s) ---"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_S ))
while (( $(date +%s) < deadline )); do
    if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
        echo "=== deployed $GIT_SHA — healthy ==="
        exit 0
    fi
    sleep 1
done

echo "!!! health check failed after ${HEALTH_TIMEOUT_S}s" >&2
sudo /usr/bin/journalctl -u rerun-server -n 30 --no-pager >&2 || true
exit 1
