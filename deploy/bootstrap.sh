#!/usr/bin/env bash
# bootstrap.sh — one-time provisioning for a fresh AlmaLinux VPS.
#
# Run as root on the VPS:
#     git clone https://github.com/<owner>/<repo>.git /root/rerun-src
#     /root/rerun-src/deploy/bootstrap.sh
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
dnf -y install curl tar rsync firewalld policycoreutils-python-utils \
	php-fpm php-cli php-pgsql php-mysqlnd unzip cronie

# ffmpeg — used by POST /api/videos/concat to re-encode video playlists
# into a single MP4. RPM Fusion has the patent-encumbered codec build;
# AlmaLinux 9 also ships ffmpeg-free in epel but it's missing libx264.
if ! command -v ffmpeg >/dev/null; then
	dnf -y install \
		https://download1.rpmfusion.org/free/el/rpmfusion-free-release-9.noarch.rpm \
		https://download1.rpmfusion.org/nonfree/el/rpmfusion-nonfree-release-9.noarch.rpm
	dnf -y install ffmpeg ffmpeg-libs
fi

systemctl enable --now crond

# Caddy (official COPR for RHEL-likes)
if ! command -v caddy >/dev/null; then
	dnf -y install 'dnf-command(copr)'
	dnf -y copr enable @caddy/caddy
	dnf -y install caddy
fi

# PostgreSQL 16 (PGDG repo — AlmaLinux 9)
if ! command -v psql >/dev/null; then
	dnf -y install https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm
	dnf -qy module disable postgresql || true
	dnf -y install postgresql16-server postgresql16-contrib
	/usr/pgsql-16/bin/postgresql-16-setup initdb
	systemctl enable --now postgresql-16
fi

# Adminer — single PHP file, dropped into /var/www/adminer
if [[ ! -f /var/www/adminer/index.php ]]; then
	install -d -m 0755 /var/www/adminer
	curl -fsSL https://www.adminer.org/latest.php -o /var/www/adminer/index.php
	chown -R root:caddy /var/www/adminer
	chmod 0750 /var/www/adminer
	chmod 0640 /var/www/adminer/index.php
fi

# php-fpm — Adminer needs it. Use a unix socket served by Caddy.
if [[ ! -f /etc/php-fpm.d/adminer.conf ]]; then
	cat > /etc/php-fpm.d/adminer.conf <<'EOF'
[adminer]
user = caddy
group = caddy
listen = /run/php-fpm/adminer.sock
listen.owner = caddy
listen.group = caddy
listen.mode = 0660
pm = ondemand
pm.max_children = 8
pm.process_idle_timeout = 30s
chdir = /var/www/adminer
php_admin_value[expose_php] = Off
EOF
	# Disable the default www pool so only Adminer is served.
	if [[ -f /etc/php-fpm.d/www.conf ]]; then
		mv /etc/php-fpm.d/www.conf /etc/php-fpm.d/www.conf.disabled
	fi
fi
systemctl enable --now php-fpm

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
install -d -o rerun -g rerun -m 0750 /opt/rerun/backups

# -----------------------------------------------------------------------------
# 3. Postgres database + role + DATABASE_URL secret
# -----------------------------------------------------------------------------
PG_PASS_FILE="/etc/rerun/.pgpass-rerun"
if [[ ! -f "$PG_PASS_FILE" ]]; then
	install -d -o root -g root -m 0750 /etc/rerun
	openssl rand -base64 32 | tr -d '/+=' | head -c 40 > "$PG_PASS_FILE"
	chmod 0600 "$PG_PASS_FILE"
fi
PG_PASS="$(cat "$PG_PASS_FILE")"

sudo -iu postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='rerun'" | grep -q 1 || \
	sudo -iu postgres psql -c "CREATE ROLE rerun LOGIN PASSWORD '${PG_PASS}'"
sudo -iu postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='rerun'" | grep -q 1 || \
	sudo -iu postgres psql -c "CREATE DATABASE rerun OWNER rerun"

# -----------------------------------------------------------------------------
# 4. Runtime secret files
# -----------------------------------------------------------------------------
if [[ ! -f /etc/rerun/env ]]; then
	cat > /etc/rerun/env <<EOF
# Rerun server runtime secrets — owned by root, mode 0600.
# Fill these in before starting the service.
DATABASE_URL=postgres://rerun:${PG_PASS}@127.0.0.1:5432/rerun?sslmode=disable
JWT_SECRET=
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_PRICE_BASIC=
STRIPE_PRICE_PRO=
PUBLIC_BASE_URL=https://api.reruni.com
EOF
	chmod 0600 /etc/rerun/env
	echo "edit /etc/rerun/env and fill in the Stripe + JWT secrets"
else
	# Make sure DATABASE_URL is present in an existing env file (re-runs).
	if ! grep -q '^DATABASE_URL=' /etc/rerun/env; then
		echo "DATABASE_URL=postgres://rerun:${PG_PASS}@127.0.0.1:5432/rerun?sslmode=disable" >> /etc/rerun/env
	fi
fi

# -----------------------------------------------------------------------------
# 5. pg_dump cron — daily backup, 14-day retention
# -----------------------------------------------------------------------------
install -m 0750 -o root -g root /dev/stdin /usr/local/sbin/rerun-pgbackup <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ts=$(date +%Y%m%d-%H%M%S)
out="/opt/rerun/backups/rerun-${ts}.sql.gz"
export PGPASSWORD="$(cat /etc/rerun/.pgpass-rerun)"
/usr/pgsql-16/bin/pg_dump -h 127.0.0.1 -U rerun -d rerun --no-owner --no-acl \
	| gzip > "$out"
chown rerun:rerun "$out"
chmod 0640 "$out"
find /opt/rerun/backups -name 'rerun-*.sql.gz' -mtime +14 -delete
EOF

cat > /etc/cron.d/rerun-backup <<'EOF'
# Daily Postgres backup at 03:17 UTC
17 3 * * * root /usr/local/sbin/rerun-pgbackup >> /var/log/rerun-backup.log 2>&1
EOF
chmod 0644 /etc/cron.d/rerun-backup

# -----------------------------------------------------------------------------
# 6. Install systemd units + Caddyfile
# -----------------------------------------------------------------------------
install -m 0644 "$DEPLOY_DIR/systemd/rerun-server.service" /etc/systemd/system/rerun-server.service
install -m 0644 "$DEPLOY_DIR/Caddyfile"                    /etc/caddy/Caddyfile
systemctl daemon-reload

# -----------------------------------------------------------------------------
# 7. Firewall — open 80/443 only; API + Postgres + Adminer stay loopback-only.
# -----------------------------------------------------------------------------
systemctl enable --now firewalld
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload

# -----------------------------------------------------------------------------
# 8. SELinux — allow Caddy to reverse-proxy + run PHP-FPM via socket
# -----------------------------------------------------------------------------
if command -v setsebool >/dev/null; then
	setsebool -P httpd_can_network_connect 1 || true
	setsebool -P httpd_execmem 1 || true
fi

# -----------------------------------------------------------------------------
# 9. Adminer basic-auth credentials (Caddy reads this hash)
# -----------------------------------------------------------------------------
if [[ ! -f /etc/caddy/adminer.basicauth ]]; then
	ADMINER_USER="admin"
	ADMINER_PASS="$(openssl rand -base64 18 | tr -d '/+=')"
	# bcrypt hash via caddy
	HASH="$(caddy hash-password --plaintext "$ADMINER_PASS")"
	install -m 0640 -o root -g caddy /dev/stdin /etc/caddy/adminer.basicauth <<EOF
basic_auth {
	$ADMINER_USER $HASH
}
EOF
	echo "==========================================================="
	echo "Adminer login (https://db.reruni.com)"
	echo "  user: $ADMINER_USER"
	echo "  pass: $ADMINER_PASS"
	echo "==========================================================="
	echo "(stored at /etc/caddy/adminer.basicauth — rotate later with"
	echo " 'caddy hash-password' and editing the file)"
fi

# -----------------------------------------------------------------------------
# 10. Enable Caddy (TLS will provision once DNS resolves)
# -----------------------------------------------------------------------------
systemctl enable --now caddy

cat <<'EOF'

bootstrap complete.

next steps:
  1. point DNS A records to this VPS:
       api.reruni.com    → <this VPS IP>
       app.reruni.com    → <this VPS IP>
       admin.reruni.com  → <this VPS IP>
       db.reruni.com     → <this VPS IP>
       reruni.com        → <this VPS IP>
       www.reruni.com    → <this VPS IP>
  2. edit /etc/rerun/env and fill in JWT_SECRET + Stripe keys
  3. add the CI deploy public key to /home/deploy/.ssh/authorized_keys
  4. push to main — GitHub Actions will deploy and run migrations automatically
EOF
