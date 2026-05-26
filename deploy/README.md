# Deploy — reruni.com

Auto-deploy pipeline for the Rerun stack on a Contabo VPS running AlmaLinux.

## Layout

```
deploy/
├── Caddyfile                       # reverse proxy + TLS for all subdomains
├── bootstrap.sh                    # one-time VPS provisioning (run as root)
├── deploy.sh                       # per-release switch + health-check + rollback
├── litestream.yml                  # continuous SQLite replication to S3/B2
└── systemd/
    ├── rerun-server.service
    └── litestream.service
.github/workflows/deploy.yml        # CI: build → rsync → switch
```

## What gets deployed

| Subdomain          | What                       | Where on VPS                    |
|--------------------|----------------------------|---------------------------------|
| `api.reruni.com`   | Go server (Stripe + WS)    | `/opt/rerun/bin/...` (systemd)  |
| `app.reruni.com`   | Portal SPA (operators)     | `/opt/rerun/portal/`            |
| `admin.reruni.com` | Backoffice SPA (Rerun staff)| `/opt/rerun/backoffice/`       |
| `reruni.com`       | 301 → `app.reruni.com`     | (Caddy redirect)                |

`/opt/rerun/data/` holds `rerun.db` (SQLite) and `uploads/`. Caddy reverse-proxies
`/uploads/*` through the Go server, which serves files from `data/uploads/`.

## One-time setup (per VPS)

1. **Provision the VPS** — Contabo, AlmaLinux 9, get root SSH access.
2. **Run bootstrap** on the VPS as root:
   ```bash
   git clone https://github.com/<owner>/<repo>.git /root/rerun-src
   cd /root/rerun-src/deploy
   sudo ./bootstrap.sh
   ```
3. **Add the CI public key** to `/home/deploy/.ssh/authorized_keys`.
   Generate the key pair locally:
   ```bash
   ssh-keygen -t ed25519 -f rerun-deploy -C "github-actions"
   ```
   The private key goes to `DEPLOY_SSH_KEY`; paste the `.pub` into
   `authorized_keys`.
4. **Capture `known_hosts`** so CI doesn't fail on first connect:
   ```bash
   ssh-keyscan -t ed25519 <VPS-IP>
   ```
   Paste the line into the `DEPLOY_KNOWN_HOSTS` secret.
5. **Fill secrets on the VPS**:
   - `/etc/rerun/env` — `JWT_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`,
     price IDs, `PUBLIC_BASE_URL`
   - `/etc/rerun/litestream.env` — B2/S3 keys (optional, only if using backup)
6. **Point DNS** A records at the VPS IP for `api`, `app`, `admin`, apex, and
   `www`.
7. **Enable litestream** (optional, but recommended for prod):
   ```bash
   sudo systemctl enable --now litestream
   ```

After step 6, push to `main` and the first deploy will run.

## GitHub repository secrets

| Secret              | Value                                                       |
|---------------------|-------------------------------------------------------------|
| `DEPLOY_HOST`       | VPS IP or hostname (e.g. `api.reruni.com`)                  |
| `DEPLOY_USER`       | `deploy`                                                    |
| `DEPLOY_SSH_KEY`    | Private key (the `rerun-deploy` file, full PEM)             |
| `DEPLOY_KNOWN_HOSTS`| Output of `ssh-keyscan` for the VPS                         |

Also create a **`production`** environment in repo settings if you want a manual
gate before deploys go out.

## How a deploy works

1. **Push to `main`** triggers `.github/workflows/deploy.yml`.
2. **Build**:
   - `go test ./...` → `go build` (linux/amd64, CGO off, stripped binary)
   - `bun run build` for both SPAs
   - Artifacts uploaded for retention (rollback by re-running an older workflow run)
3. **Deploy**:
   - rsync release into `/opt/rerun/releases/<sha>/`
   - SSH executes `sudo deploy.sh <sha>`:
     - atomic symlink swap for the server binary
     - rsync SPAs into `/opt/rerun/portal/` and `/opt/rerun/backoffice/`
     - `systemctl restart rerun-server`
     - poll `http://127.0.0.1:8080/api/health` for up to 30s
     - **on failure**: restore previous symlink and restart
     - **on success**: keep the latest 3 releases, prune the rest

## Rollback

- **Automatic** — `deploy.sh` reverts if `/api/health` fails after restart.
- **Manual** — re-run a previous green workflow run on GitHub:
  Actions → deploy → pick run → "Re-run all jobs".
- **From the VPS** — point the symlink at the previous release and restart:
  ```bash
  sudo ln -sfn /opt/rerun/releases/<old-sha>/server /opt/rerun/bin/tiktokrerun-server
  sudo systemctl restart rerun-server
  ```

## SQLite backup / restore

Litestream replicates `rerun.db` continuously to S3/B2.

**Restore on a fresh VPS** (run before starting `rerun-server`):
```bash
sudo -u rerun litestream restore -config /etc/litestream.yml /opt/rerun/data/rerun.db
```

## Logs

```bash
journalctl -u rerun-server -f
journalctl -u caddy -f
journalctl -u litestream -f
```

## Local checks before pushing

```bash
# server
cd server && go test ./... && go build ./...

# SPAs
cd portal && bun run build
cd backoffice && bun run build
```
