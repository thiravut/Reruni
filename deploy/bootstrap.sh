#!/usr/bin/env bash
# bootstrap.sh — one-time provisioning for a fresh AlmaLinux VPS.
#
# Run as root on the VPS:
#     curl -fsSL https://raw.githubusercontent.com/<owner>/<repo>/main/deploy/bootstrap.sh | bash
# or scp this file + the deploy/ tree first and run it locally.
#
# Idempotent: safe to re-run; skips steps that are already done.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
	echo "must run as root" >&2
	exit 1
fi

REPO_DIR="${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
DEPLOY_DIR="$REPO_DIR/deploy"

# -----------------------------------------------------------------------------
# 1. System packages
# -----------------------------------------------------------------------------
dnf -y install epel-release
dnf -y install curl tar rsync firewalld policycoreutils-python-utils

# Caddy (official COPR for RHEL-likes)
if ! command -v caddy >/dev/null; then
	dnf -y install 'dnf-command(copr)'
	dnf -y copr enable @caddy/caddy
	dnf -y install caddy
fi

# Litestream (single binary)
if ! command -v litestream >/dev/null; then
	LITESTREAM_VERSION="0.3.13"
	curl -fsSL "https://github.com/benbjohnson/litestream/releases/download/v${LITESTREAM_VERSION}/litestream-v${LITESTREAM_VERSION}-linux-amd64.tar.gz" \
		| tar -xz -C /usr/local/bin litestream
	chmod +x /usr/local/bin/litestream
fi

# -----------------------------------------------------------------------------
# 2. Service user + deploy user + directories
# -----------------------------------------------------------------------------
# 'rerun' runs the Go server (no shell, no sudo).
id -u rerun >/dev/null 2>&1 || useradd --system --home /opt/rerun --shell /sbin/nologin rerun

# 'deploy' is the SSH target for GitHub Actions; can sudo only the deploy script.
id -u deploy >/dev/null 2>&1 || useradd --create-home --shell /bin/bash deploy
install -d -o deploy -g deploy -m 0700 /home/deploy/.ssh
if [[ ! -f /home/deploy/.ssh/authorized_keys ]]; then
	touch /home/deploy/.ssh/authorized_keys
	chown deploy:deploy /home/deploy/.ssh/authorized_keys
	chmod 0600 /home/deploy/.ssh/authorized_keys
	echo "add the CI public key to /home/deploy/.ssh/authorized_keys"
fi

# Sudoers: deploy can run only the release switcher under /opt/rerun/releases/*/deploy.sh
cat > /etc/sudoers.d/rerun-deploy <<'EOF'
deploy ALL=(root) NOPASSWD: /opt/rerun/releases/*/deploy.sh *
deploy ALL=(root) NOPASSWD: /usr/bin/install -d -o deploy -g deploy /opt/rerun/releases/*
EOF
chmod 0440 /etc/sudoers.d/rerun-deploy
visudo -c -f /etc/sudoers.d/rerun-deploy >/dev/null

install -d -o rerun -g rerun -m 0755 /opt/rerun
install -d -o rerun -g rerun -m 0755 /opt/rerun/bin
install -d -o rerun -g rerun -m 0755 /opt/rerun/releases
install -d -o rerun -g rerun -m 0755 /opt/rerun/portal
install -d -o rerun -g rerun -m 0755 /opt/rerun/backoffice
install -d -o rerun -g rerun -m 0750 /opt/rerun/data
install -d -o rerun -g rerun -m 0750 /opt/rerun/data/uploads

install -d -o root -g root -m 0750 /etc/rerun
if [[ ! -f /etc/rerun/env ]]; then
	cat > /etc/rerun/env <<'EOF'
# Rerun server runtime secrets — owned by root, mode 0600.
# Fill these in before starting the service.
JWT_SECRET=
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_PRICE_BASIC=
STRIPE_PRICE_PRO=
PUBLIC_BASE_URL=https://api.reruni.com
EOF
	chmod 0600 /etc/rerun/env
	echo "edit /etc/rerun/env and fill in the secrets before starting rerun-server"
fi

if [[ ! -f /etc/rerun/litestream.env ]]; then
	cat > /etc/rerun/litestream.env <<'EOF'
# Backblaze B2 / S3 credentials for litestream.
# See https://litestream.io/guides/s3/ — also works with B2 via S3 API.
LITESTREAM_ACCESS_KEY_ID=
LITESTREAM_SECRET_ACCESS_KEY=
EOF
	chmod 0600 /etc/rerun/litestream.env
	echo "edit /etc/rerun/litestream.env before enabling litestream"
fi

# -----------------------------------------------------------------------------
# 3. Install systemd units + Caddyfile + litestream config
# -----------------------------------------------------------------------------
install -m 0644 "$DEPLOY_DIR/systemd/rerun-server.service" /etc/systemd/system/rerun-server.service
install -m 0644 "$DEPLOY_DIR/systemd/litestream.service"   /etc/systemd/system/litestream.service
install -m 0644 "$DEPLOY_DIR/Caddyfile"                    /etc/caddy/Caddyfile
install -m 0644 "$DEPLOY_DIR/litestream.yml"               /etc/litestream.yml

systemctl daemon-reload

# -----------------------------------------------------------------------------
# 4. Firewall — open 80/443 only; API stays on 127.0.0.1:8080 behind Caddy.
# -----------------------------------------------------------------------------
systemctl enable --now firewalld
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload

# -----------------------------------------------------------------------------
# 5. SELinux — allow Caddy to reverse-proxy to localhost:8080
# -----------------------------------------------------------------------------
if command -v setsebool >/dev/null; then
	setsebool -P httpd_can_network_connect 1 || true
fi

# -----------------------------------------------------------------------------
# 6. Enable Caddy now (it will fail TLS until DNS points at this box — that's OK)
# -----------------------------------------------------------------------------
systemctl enable --now caddy

cat <<'EOF'

bootstrap complete.

next steps:
  1. point DNS A records to this VPS:
       api.reruni.com    → <this VPS IP>
       app.reruni.com    → <this VPS IP>
       admin.reruni.com  → <this VPS IP>
       reruni.com        → <this VPS IP>
       www.reruni.com    → <this VPS IP>
  2. edit /etc/rerun/env and fill in secrets
  3. edit /etc/rerun/litestream.env if using SQLite backup
  4. push to main — GitHub Actions will deploy
EOF
