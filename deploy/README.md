# Deploy — reruni.com

Auto-deploy pipeline for the Rerun stack on a Contabo VPS running AlmaLinux 9.

## Layout

```
deploy/
├── Caddyfile                       # reverse proxy + TLS for all subdomains
├── bootstrap.sh                    # one-time VPS provisioning (run as root)
├── deploy.sh                       # per-release switch + health-check + rollback
└── systemd/
    └── rerun-server.service
.github/workflows/deploy.yml        # CI: build → test → rsync → switch
server/migrations/                  # goose SQL migrations (Postgres)
```

## What gets deployed

| Subdomain          | What                            | Where on VPS                    |
|--------------------|---------------------------------|---------------------------------|
| `api.reruni.com`   | Go server (Stripe + WS)         | `/opt/rerun/bin/...` (systemd)  |
| `app.reruni.com`   | Portal SPA (operators)          | `/opt/rerun/portal/`            |
| `admin.reruni.com` | Backoffice SPA (Rerun staff)    | `/opt/rerun/backoffice/`        |
| `db.reruni.com`    | Adminer (Postgres UI, basic auth)| PHP-FPM + Caddy                |
| `reruni.com`       | 301 → `app.reruni.com`          | (Caddy redirect)                |

`/opt/rerun/data/uploads/` holds uploaded videos (served via the Go server).
PostgreSQL 16 runs on the same box, bound to `127.0.0.1:5432`, accessed via
`DATABASE_URL` in `/etc/rerun/env`.

## One-time setup (per VPS)

1. **Provision the VPS** — Contabo, AlmaLinux 9, get root SSH access.
2. **Run bootstrap** on the VPS as root:
   ```bash
   git clone https://github.com/<owner>/<repo>.git /root/rerun-src
   /root/rerun-src/deploy/bootstrap.sh
   ```
   This installs: Caddy, PostgreSQL 16, php-fpm, Adminer, firewalld; creates
   the `rerun` + `deploy` users; provisions the `rerun` Postgres role/database;
   sets up daily pg_dump cron; writes systemd units. **Save the Adminer
   password printed at the end.**
3. **Add the CI public key** to `/home/deploy/.ssh/authorized_keys`.
   Generate the key pair locally:
   ```bash
   ssh-keygen -t ed25519 -f rerun-deploy -C "github-actions"
   ```
4. **Capture `known_hosts`**:
   ```bash
   ssh-keyscan -t ed25519 <VPS-IP>
   ```
5. **Fill secrets** in `/etc/rerun/env`:
   - `DATABASE_URL` — already filled in by bootstrap
   - `JWT_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, price IDs,
     `PUBLIC_BASE_URL`
6. **Point DNS** A records at the VPS IP for `api`, `app`, `admin`, `db`,
   apex, and `www`.

After step 6, push to `main` and the first deploy will run.

## GitHub repository secrets

| Secret              | Value                                                       |
|---------------------|-------------------------------------------------------------|
| `DEPLOY_HOST`       | VPS IP or hostname                                          |
| `DEPLOY_USER`       | `deploy`                                                    |
| `DEPLOY_SSH_KEY`    | Private key (the `rerun-deploy` file, full PEM)             |
| `DEPLOY_KNOWN_HOSTS`| Output of `ssh-keyscan` for the VPS                         |

Optionally create a **`production`** environment in repo settings for a manual
gate before deploys go out.

## How a deploy works

1. **Push to `main`** triggers `.github/workflows/deploy.yml`.
2. **Build**:
   - Spin up a `postgres:16` service container
   - `go test ./...` runs against `TEST_DATABASE_URL` (CI Postgres)
   - `go build` (linux/amd64, CGO off, stripped binary)
   - `bun run build` for both SPAs
3. **Deploy**:
   - rsync release into `/opt/rerun/releases/<sha>/`
   - SSH executes `sudo deploy.sh <sha>`:
     - atomic symlink swap for the server binary
     - rsync SPAs into `/opt/rerun/portal/` and `/opt/rerun/backoffice/`
     - `systemctl restart rerun-server` (server runs goose migrations on boot)
     - poll `http://127.0.0.1:18080/api/health` for up to 30s
     - **on failure**: restore previous symlink and restart
     - **on success**: keep the latest 3 releases, prune the rest

## Database

### Migrations

Schema lives in `server/migrations/000N_*.sql` (goose format). The Go binary
runs `goose up` against the configured `DATABASE_URL` on every boot — additive
migrations apply automatically as part of a deploy.

To add a new migration locally:
```bash
cd server
ls migrations/                       # find next number
$EDITOR migrations/0002_add_xxx.sql  # follow the +goose Up/Down format
```

### Daily backup

`cron.d/rerun-backup` runs `/usr/local/sbin/rerun-pgbackup` at 03:17 UTC daily.
Backups land in `/opt/rerun/backups/` (gzipped pg_dump, 14-day retention).

**Restore on a fresh VPS:**
```bash
sudo systemctl stop rerun-server
sudo -u postgres psql -c "DROP DATABASE rerun"
sudo -u postgres psql -c "CREATE DATABASE rerun OWNER rerun"
gunzip < /opt/rerun/backups/rerun-<ts>.sql.gz | sudo -u rerun psql \
  -h 127.0.0.1 -d rerun
sudo systemctl start rerun-server
```

### Adminer UI

Browse to `https://db.reruni.com`. Use the basic-auth credentials saved during
bootstrap. Inside Adminer, log in with:
- System: `PostgreSQL`
- Server: `127.0.0.1`
- Username: `rerun`
- Password: from `/etc/rerun/env` `DATABASE_URL`
- Database: `rerun`

## Rollback

- **Automatic** — `deploy.sh` reverts if `/api/health` fails after restart.
- **Manual** — re-run a previous green workflow run on GitHub.
- **From the VPS**:
  ```bash
  sudo ln -sfn /opt/rerun/releases/<old-sha>/server /opt/rerun/bin/tiktokrerun-server
  sudo systemctl restart rerun-server
  ```

Schema rollback is **not automatic** — goose migrations are forward-only by
default. If a release ships a bad migration, write a follow-up migration that
reverses it rather than running `goose down` in prod.

## Logs

```bash
journalctl -u rerun-server -f
journalctl -u caddy -f
journalctl -u postgresql-16 -f
tail -f /var/log/rerun-backup.log
```

## Local checks before pushing

```bash
# server (need a local Postgres for tests, or skip)
cd server
TEST_DATABASE_URL=postgres://postgres@127.0.0.1:5432/rerun_test?sslmode=disable \
  go test ./...
go build ./...

# SPAs
cd portal && bun run build
cd backoffice && bun run build
```

To get a local Postgres for tests (one of):
- `brew services start postgresql@17`  +  `createdb rerun_test`
- `docker run --rm -d -p 5432:5432 -e POSTGRES_PASSWORD=test -e POSTGRES_DB=rerun_test postgres:16`
