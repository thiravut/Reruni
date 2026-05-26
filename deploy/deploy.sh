#!/usr/bin/env bash
# deploy.sh — atomic-ish release switch run on the VPS by the CI step.
#
# Invoked over SSH after rsync has finished uploading:
#     /opt/rerun/releases/<sha>/server                  (Go binary, linux/amd64)
#     /opt/rerun/releases/<sha>/portal/                 (vite dist)
#     /opt/rerun/releases/<sha>/backoffice/             (vite dist)
#
# Usage:
#     deploy.sh <git-sha>
#
# On health-check failure, the previous symlink target is restored and the
# server is restarted — so a bad build does not stay live.

set -euo pipefail

SHA="${1:?usage: deploy.sh <git-sha>}"
RELEASE_DIR="/opt/rerun/releases/$SHA"
HEALTH_URL="http://127.0.0.1:8080/api/health"
HEALTH_TIMEOUT_S=30

if [[ ! -x "$RELEASE_DIR/server" ]]; then
	echo "missing $RELEASE_DIR/server" >&2
	exit 1
fi

# Capture current target so we can roll back on health failure.
PREV_TARGET=""
if [[ -L /opt/rerun/bin/tiktokrerun-server ]]; then
	PREV_TARGET="$(readlink -f /opt/rerun/bin/tiktokrerun-server || true)"
fi

# --- swap symlinks atomically -------------------------------------------------
ln -sfn "$RELEASE_DIR/server" /opt/rerun/bin/tiktokrerun-server.new
mv -Tf  /opt/rerun/bin/tiktokrerun-server.new /opt/rerun/bin/tiktokrerun-server

# Static SPAs — rsync into place (delete stale files).
# We do this rather than symlink the whole tree so Caddy's file_server doesn't
# briefly miss assets while a rebuild is mid-flight.
rsync -a --delete "$RELEASE_DIR/portal/"     /opt/rerun/portal/
rsync -a --delete "$RELEASE_DIR/backoffice/" /opt/rerun/backoffice/
chown -R rerun:rerun /opt/rerun/portal /opt/rerun/backoffice

# --- restart + health check ---------------------------------------------------
systemctl restart rerun-server

deadline=$(( $(date +%s) + HEALTH_TIMEOUT_S ))
while (( $(date +%s) < deadline )); do
	if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
		echo "deploy ok: $SHA"
		# Prune old releases, keep latest 3.
		ls -1dt /opt/rerun/releases/*/ 2>/dev/null | tail -n +4 | xargs -r rm -rf
		exit 0
	fi
	sleep 1
done

# --- health failed → roll back -----------------------------------------------
echo "health check failed after ${HEALTH_TIMEOUT_S}s — rolling back" >&2
if [[ -n "$PREV_TARGET" && -x "$PREV_TARGET" ]]; then
	ln -sfn "$PREV_TARGET" /opt/rerun/bin/tiktokrerun-server.new
	mv -Tf /opt/rerun/bin/tiktokrerun-server.new /opt/rerun/bin/tiktokrerun-server
	systemctl restart rerun-server
	echo "rolled back to $PREV_TARGET" >&2
else
	echo "no previous binary to roll back to — server is down" >&2
fi
exit 1
