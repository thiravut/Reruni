# Portal + Backoffice — Implementation Spec

**สถานะ:** Draft v1 / pre-implementation
**วันที่:** 2026-05-26
**Owner:** TBD (agent hand-off)
**Track:** Parallel to mobile-app track (own-built VCam LSPosed module — see [system-overview.md](system-overview.md) §9)

---

## 1. Why this exists

POC ปัจจุบันมีแค่ single-account dashboard ฝัง `server/web/index.html` ใช้สำหรับ control device fleet เดียว — เพียงพอสำหรับ technical POC แต่ไม่ใช่ product

Portal + Backoffice = production-grade user-facing layer ที่ wraps POC:
- **Portal (member-facing):** ลูกค้า/ผู้ใช้จัดการ devices, videos, live config, history ของตัวเอง
- **Backoffice (admin-facing):** ทีมเรา monitor ทั้ง platform, support members, intervene เมื่อจำเป็น

แยกออกจาก mobile-app track เพราะ:
- ไม่ block / ไม่ถูก block ด้วย mobile broadcast decisions
- ใช้ stack คนละชุด (web frontend vs Android)
- reusable regardless of broadcast technology (VCam Camera2 hijack, future RTMP, etc.)

---

## 2. Architecture overview

```
┌─────────────────────────┐    ┌──────────────────────────┐
│   Portal SPA            │    │   Backoffice SPA         │
│   app.<domain>          │    │   backoffice.<domain>    │
│   Vite + React + TS     │    │   Vite + React + TS      │
│   (member-facing)       │    │   (admin-facing)         │
└────────────┬────────────┘    └────────────┬─────────────┘
             │                              │
             │  HTTPS + session cookie      │
             ▼                              ▼
        ┌────────────────────────────────────────────────┐
        │   Go API server (server/)                      │
        │   api.<domain>                                 │
        │   - REST + WebSocket (existing)                │
        │   - Auth (session cookie, bcrypt)              │
        │   - Per-user data scoping (no multi-tenant)    │
        │   - CORS configured for portal + backoffice    │
        └─────────────────────┬──────────────────────────┘
                              │ WSS (existing)
                              ▼
                       [Android devices]
```

**Subdomain split decision:** Portal และ Backoffice อยู่คนละ subdomain เพื่อ:
- แยก security boundary (different cookies, less attack surface ของ admin tool)
- แยก deployment lifecycle (deploy portal ไม่กระทบ backoffice และในทางกลับ)
- Production-grade signal ตั้งแต่แรก

**Dev URLs:**
- Portal: `http://localhost:5173`
- Backoffice: `http://localhost:5174`
- API: `http://localhost:8080`

Vite dev server ตั้ง proxy `/api/*` → `http://localhost:8080` ทั้ง 2 SPAs

---

## 3. Stack choices

| Layer | Choice | Notes |
|---|---|---|
| Backend | **Go** (continue existing `server/`) | stdlib `net/http` + `gorilla/websocket` + `modernc.org/sqlite` |
| Auth | **session cookie** (HTTP-only, Secure in prod) | server-stored sessions; not JWT |
| Password hashing | **bcrypt** (`golang.org/x/crypto/bcrypt`) | cost = 12 |
| Frontend (both SPAs) | **Vite + React + TypeScript + Tailwind CSS** | React 19, Tailwind 4 |
| Routing | **React Router v6** (or v7) | client-side routing |
| API client | **plain `fetch`** + small wrapper | no axios, no TanStack Query initially |
| State | **React Context + `useState/useReducer`** | TanStack Query later if needed |
| UI components | hand-rolled + Tailwind, OR **shadcn/ui** | agent's call — keep deps lean |
| Package manager | **`bun`** (project already uses it) | `bun create vite`, `bun install`, `bun run dev` |
| Database | continue **SQLite** for now | migrate to Postgres in MVP layer (out of scope) |

**Stack guardrails — do not introduce:**
- ❌ TanStack Query / SWR ในรอบแรก (overkill ตอนนี้)
- ❌ Redux / Zustand (Context พอ)
- ❌ Auth library (NextAuth, Auth0 SDK) — backend cookie session straightforward
- ❌ Component library ที่หนัก (MUI, Ant Design) — Tailwind + custom สบายกว่า

---

## 4. Phase 1a — Backend (Go) auth + per-user ownership

### 4.1 Schema (extend `server/db.go`)

**Ownership model:** **Direct user ownership** — no multi-tenant abstraction. Per persona pivot 2026-05-26 (see decision log), TiktokRerun targets solo seller market, not agency/team. Each User owns their devices, videos, sessions directly.

```sql
CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'user',  -- 'user' | 'admin'
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessions (
    token       TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME NOT NULL
);

-- Extend existing tables with owner_user_id (default 0 = legacy/unowned):
ALTER TABLE videos  ADD COLUMN owner_user_id INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN owner_user_id INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_videos_owner   ON videos(owner_user_id);
CREATE INDEX idx_devices_owner  ON devices(owner_user_id);
CREATE INDEX idx_sessions_user  ON sessions(user_id);
```

**Why no `tenants` table:** PRD targets solo seller persona (1 user = 1 account = own everything). Multi-user / team / agency features explicitly out of scope per PRD §6.2 — revisit ONLY if enterprise demand emerges. Adding `tenants` now adds joins + complexity for zero current value.

**Migration path (if multi-user becomes needed):** add `tenants(id, owner_user_id, name)` table + change column from `owner_user_id` → `tenant_id`, with backfill `tenant_id = owner_user_id`. Cleanly reversible.

### 4.2 New endpoints

| Method | Path | Body | Returns | Auth |
|---|---|---|---|---|
| POST | `/api/auth/signup` | `{email, password}` | `{user, expires_at}` + Set-Cookie | none |
| POST | `/api/auth/login` | `{email, password}` | `{user, expires_at}` + Set-Cookie | none |
| POST | `/api/auth/logout` | — | 204 + clear cookie | session |
| GET | `/api/auth/me` | — | `{user}` | session |

### 4.3 Middleware

- `requireAuth(handler)` — reads session cookie, looks up + checks expiry; rejects with 401 if invalid. Sets `userID` in request context (used directly as owner key).
- `requireAdmin(handler)` — wraps `requireAuth` + checks `user.role == "admin"`. Used for `/admin/*` endpoints.
- CORS middleware — allow origins:
  - dev: `http://localhost:5173`, `http://localhost:5174`
  - prod: from env var (e.g., `CORS_ORIGINS=https://app.example.com,https://backoffice.example.com`)
  - allow credentials (cookies)

### 4.4 Scope existing resources by user

All existing endpoints in `handlers.go` and `ws.go` must:
1. Be wrapped with `requireAuth`
2. Filter queries by `WHERE owner_user_id = ?` using the context's userID
3. INSERT with `owner_user_id` from context

Existing endpoints to update:
- `POST /api/videos` — insert owner_user_id
- `GET /api/videos` — filter by owner_user_id
- `DELETE /api/videos/{id}` — verify owner_user_id ownership before delete
- `GET /api/devices` — filter by owner_user_id
- `POST /api/devices/{id}/play` — verify ownership
- `POST /api/devices/{id}/start-live` — verify ownership
- `POST /api/pair` — record owner_user_id on pair token (so first WS connect ties device to owner)
- `WS /ws/device` — when device first pairs, set `devices.owner_user_id` from the pair token's owner

### 4.5 Pair token flow with user ownership

Update `pairTokens` from `map[string]bool` to `map[string]int64` (token → owner user ID). When user clicks "สร้าง Pair QR" in portal, server creates token bound to current session's user. When phone connects via that token, device row gets `owner_user_id = current user`.

### 4.6 First admin user

Bootstrap a single admin user on first run:
- Read env var `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`
- If `users` table empty and both env vars set → insert with `role='admin'`
- Otherwise: no admins; admins must be promoted manually via SQL until backoffice has admin-management UI

---

## 5. Phase 1b — Portal SPA (member-facing)

**Repo location:** `portal/` (sibling of `server/`)

### 5.1 Initial setup

```bash
cd /Users/pond/Developer/localhost/TikTok/Rerun
bun create vite portal -- --template react-ts
cd portal
bun add react-router-dom
bun add -d tailwindcss@latest @tailwindcss/vite
```

Configure Vite proxy `/api` → `http://localhost:8080` (in `vite.config.ts`) and credentials include in fetch.

### 5.2 Pages

| Route | Purpose |
|---|---|
| `/login` | email/password form → POST `/api/auth/login` → redirect `/dashboard` |
| `/signup` | email/password + confirm → POST `/api/auth/signup` → redirect `/dashboard` |
| `/dashboard` | overview: counts (devices online, videos, recent lives) |
| `/devices` | device list + status badges + pair-new-device button (generates QR via `/api/pair`) |
| `/videos` | video library: upload, list, delete (existing API) |
| `/live-config` | start-live config: pick device, video, live title, product keywords, auto-start checkbox; submit → existing `/api/devices/:id/play` or `/start-live` |
| `/history` | past lives (out of scope for v1 if `lives` table not yet added — stub the page) |

### 5.3 Layout

- Top nav with: app title, user email, logout button
- Side nav (or top tabs for v1): Dashboard / Devices / Videos / Live / History
- Protected route guard: redirect to `/login` if `GET /api/auth/me` fails

### 5.4 Migrate features from `server/web/index.html`

Existing functionality in the old vanilla dashboard:
- Video upload, list, delete
- Device list with online status (5s polling)
- Pair token QR generation
- Play form: device + video + auto-start checkbox + live title + product keywords
- Action log (recent commands)

→ split across `/devices`, `/videos`, `/live-config`, `/history` pages. Reuse the API shape.

### 5.5 Disposition of the old dashboard — DECIDED 2026-05-26
**Decision (Pond):** Keep `server/web/` POC dashboard **permanently** at `/api/legacy/*` endpoints
- No migration cutover needed
- No deprecation warning
- Portal lives at `app.<domain>` separately; legacy dashboard stays at server root for ad-hoc use
- Future agents: do NOT remove `server/web/` or `/api/legacy/*` routes

### 5.5b Original open question (now resolved)

ตัดสินใจกับ owner ภายหลังว่าจะลบ `server/web/index.html` หรือเก็บเป็น `/legacy` admin view ระหว่าง transition

---

## 6. Phase 1c — Backoffice SPA (admin-facing)

**Repo location:** `backoffice/` (sibling of `server/`)

### 6.1 Initial setup

Same stack as Portal. `bun create vite backoffice -- --template react-ts`. Vite port 5174.

### 6.2 Pages

| Route | Purpose |
|---|---|
| `/admin/login` | admin email/password — separate page to avoid confusion |
| `/admin/users` | list all users + role + created_at; promote/demote; reset password |
| `/admin/devices` | list ALL devices (across all users) with online/offline + last_seen + owner email |
| `/admin/videos` | list ALL videos (across all users) — disk usage breakdown |
| `/admin/metrics` | system metrics: total devices, online %, broadcasts last 24h, total disk, total users |
| `/admin/lives` | active + recent lives — manual intervention (force stop, message device) |

### 6.3 New backend endpoints needed (admin-only)

| Method | Path | Returns |
|---|---|---|
| GET | `/api/admin/users` | list users + role + per-user stats (devices, videos, last_active) |
| PATCH | `/api/admin/users/:id/role` | change role |
| POST | `/api/admin/users/:id/reset-password` | server-generated temp password or email link |
| GET | `/api/admin/devices` | all devices across all users |
| GET | `/api/admin/videos` | all videos across all users |
| GET | `/api/admin/metrics` | dashboard summary |
| POST | `/api/admin/devices/:id/disconnect` | force-disconnect a device |
| DELETE | `/api/admin/users/:id` | hard-delete user + cascade |

All `/api/admin/*` endpoints wrapped with `requireAdmin` middleware.

---

## 7. Dev workflow

```bash
# Terminal 1 — Go API
cd server && go run .

# Terminal 2 — Portal SPA
cd portal && bun install && bun run dev   # http://localhost:5173

# Terminal 3 — Backoffice SPA
cd backoffice && bun install && bun run dev   # http://localhost:5174

# Browser
open http://localhost:5173            # portal
open http://localhost:5174/admin      # backoffice
```

Bootstrap admin via env vars before first `go run .`:
```bash
export BOOTSTRAP_ADMIN_EMAIL=admin@example.com
export BOOTSTRAP_ADMIN_PASSWORD=changeme
go run .
```

---

## 8. Deployment outline (out of scope for v1 build — for planning only)

- API server: AlmaLinux box ที่ Pond มีอยู่ (per [project memory](../../../../../.claude/projects/-Users-pond-Developer-localhost-TikTok-Rerun/memory/project_tiktokrerun.md)) หรือ VPS ใหม่ (Hetzner / Railway)
- SPAs: static build → nginx / Cloudflare Pages / Vercel
- Subdomains via DNS A/AAAA records + reverse proxy
- TLS via Let's Encrypt
- SQLite OK for early MVP; Postgres when scaling

---

## 9. Hand-off plan

แยกงานเป็น 2 streams เพื่อ agents 2 ตัวทำขนาน:

### Stream X — Backend + Portal (member-facing)
Owner: agent X
- §4 (Backend auth + per-user ownership): สำคัญที่สุด ไม่ทำเรื่องอื่น blocks both streams
- §5 (Portal SPA): หลัง §4 พื้นฐานพร้อม
- Validate: e2e flow signup → pair device → upload video → start live

### Stream Y — Backoffice
Owner: agent Y
- ต้องรอ §4 เสร็จก่อน (auth + role + admin endpoints) — สามารถเริ่ม UI scaffold + mock data ก่อนได้
- §6 (Backoffice SPA + admin endpoints) — งานเด่นของ stream นี้
- Validate: admin login → see all users → see all devices → see metrics

### Co-ordination
- Cross-cutting changes (CORS, session middleware, env vars) — Stream X ทำเป็น owner
- API contracts (§4.2, §6.3) — agree before each agent ลงมือเขียน frontend ฝั่งตน
- Shared CSS/component conventions — define ใน `docs/planning-artifacts/portal-style-guide.md` (TBD, out of scope this doc)

---

## 10. Out of scope (this spec)

- Production deployment automation
- Billing / pricing tiers
- Email service (transactional)
- OAuth (Google/Apple)
- Postgres migration
- Activity log / audit trail
- Live history table + queries (needs a separate `lives` table — design TBD)
- WebSocket events from API → portal (real-time device status push)
- i18n (Thai/English toggle) — keep Thai-first for now

These will be addressed in MVP layer after Phase 1a-c stabilize.
