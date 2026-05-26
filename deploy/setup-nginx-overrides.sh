#!/usr/bin/env bash
# setup-nginx-overrides.sh — installs nginx server blocks that override the
# CWP-generated vhosts for api/app/admin.reruni.com. Run once as root.
#
# Strategy: drop our config at /etc/nginx/conf.d/00-rerun-overrides.conf so
# nginx parses it BEFORE /etc/nginx/conf.d/vhosts/*.conf. nginx matches the
# FIRST server block for a given listen+server_name, so ours wins and CWP
# regenerating its files becomes a no-op for these subdomains. The CWP files
# still own mail/webmail/cpanel subdomain blocks (different server_names) —
# those keep working untouched.
#
# Re-run anytime — idempotent. Will print "ignored" warnings from nginx
# during `nginx -t` because the CWP vhosts duplicate our server_names; that
# is expected and means our overrides are winning.

set -euo pipefail
if [[ $EUID -ne 0 ]]; then
    echo "must run as root" >&2
    exit 1
fi

VPS_IP="${VPS_IP:-194.163.137.135}"
OUT=/etc/nginx/conf.d/00-rerun-overrides.conf

cat > "$OUT" <<EOF
# Managed by deploy/setup-nginx-overrides.sh — edits here will be overwritten.
# Re-run that script to apply changes.

# ============================================================================
# api.reruni.com — proxy to Go server on :18080, with WS + large uploads
# ============================================================================
server {
    listen ${VPS_IP}:80;
    server_name api.reruni.com www.api.reruni.com;

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }
    location / { return 301 https://api.reruni.com\$request_uri; }
}

server {
    listen ${VPS_IP}:443 ssl;
    http2 on;
    server_name api.reruni.com www.api.reruni.com;

    ssl_certificate     /etc/pki/tls/certs/api.reruni.com.bundle;
    ssl_certificate_key /etc/pki/tls/private/api.reruni.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log /usr/local/apache/domlogs/api.reruni.com.log combined;
    error_log  /usr/local/apache/domlogs/api.reruni.com.error.log error;

    # Stripe webhook + video uploads
    client_max_body_size 500m;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;

    # WebSocket — must come before generic location / so it matches first
    location /ws/ {
        proxy_pass http://127.0.0.1:18080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    # HTTP API + Stripe webhook + /uploads/* (Go serves static media itself)
    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }

    location ~ /\.ht  { deny all; }
    location ~ /\.git { deny all; }
}

# ============================================================================
# app.reruni.com — portal SPA (React + Vite); try_files fallback to index.html
# so client-side routing (react-router) works on deep links.
# ============================================================================
server {
    listen ${VPS_IP}:80;
    server_name app.reruni.com www.app.reruni.com;

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }
    location / { return 301 https://app.reruni.com\$request_uri; }
}

server {
    listen ${VPS_IP}:443 ssl;
    http2 on;
    server_name app.reruni.com www.app.reruni.com;

    ssl_certificate     /etc/pki/tls/certs/app.reruni.com.bundle;
    ssl_certificate_key /etc/pki/tls/private/app.reruni.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log /usr/local/apache/domlogs/app.reruni.com.log combined;
    error_log  /usr/local/apache/domlogs/app.reruni.com.error.log error;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;

    root /home/reruni/app.reruni.com;
    index index.html;

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    # Hashed Vite assets — cache hard
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files \$uri =404;
    }

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }

    location ~ /\.ht  { deny all; }
    location ~ /\.git { deny all; }
}

# ============================================================================
# admin.reruni.com — backoffice SPA, same pattern as app
# ============================================================================
server {
    listen ${VPS_IP}:80;
    server_name admin.reruni.com www.admin.reruni.com;

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }
    location / { return 301 https://admin.reruni.com\$request_uri; }
}

server {
    listen ${VPS_IP}:443 ssl;
    http2 on;
    server_name admin.reruni.com www.admin.reruni.com;

    ssl_certificate     /etc/pki/tls/certs/admin.reruni.com.bundle;
    ssl_certificate_key /etc/pki/tls/private/admin.reruni.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log /usr/local/apache/domlogs/admin.reruni.com.log combined;
    error_log  /usr/local/apache/domlogs/admin.reruni.com.error.log error;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;

    root /home/reruni/admin.reruni.com;
    index index.html;

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files \$uri =404;
    }

    location /.well-known/acme-challenge {
        default_type "text/plain";
        alias /usr/local/apache/autossl_tmp/.well-known/acme-challenge;
    }

    location ~ /\.ht  { deny all; }
    location ~ /\.git { deny all; }
}
EOF

chmod 644 "$OUT"

echo "--- testing nginx config (warnings about 'conflicting server name' are expected) ---"
nginx -t

systemctl reload nginx
echo "--- reloaded ---"
echo "overrides at: $OUT"
echo "CWP files still in place at /etc/nginx/conf.d/vhosts/*.conf but shadowed for api/app/admin"
