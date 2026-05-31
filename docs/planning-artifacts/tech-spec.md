# TiktokRerun — Technical Specification

**Status:** Locked decisions for MVP build
**Date:** 2026-05-26
**Audience:** Backend (API), Portal, Backoffice agents
**Reads alongside:** [api-contract.md](api-contract.md), [portal-backoffice-spec.md](portal-backoffice-spec.md), [technical-architecture-draft.md](technical-architecture-draft.md)

> **Purpose:** ใช้ doc นี้เป็น single source of truth สำหรับ "what tech, how to do it, where to put it." ถ้า agent หาคำตอบใน doc นี้ไม่ได้ ให้ถาม Pond ก่อนตัดสินใจเอง

---

## 1. Repo Structure

```
/Users/pond/Developer/localhost/TikTok/Rerun/
├── server/            # Go API + WebSocket (existing — extend)
│   ├── main.go
│   ├── db.go
│   ├── handlers.go
│   ├── ws.go
│   ├── auth.go        # NEW — Phase 1a
│   ├── admin.go       # NEW — Phase 1c admin endpoints
│   ├── middleware.go  # NEW — auth, CORS, rate limit
│   ├── uploads/       # video files (already used)
│   └── web/           # legacy POC dashboard — keep until portal stable
├── portal/            # NEW — Vite + React (member-facing SPA)
├── backoffice/        # NEW — Vite + React (admin-facing SPA)
├── mobile/            # Android Companion App (separate track, Smart Overlay POC)
└── docs/planning-artifacts/  # specs + decision log
```

## 2. Locked Tech Decisions

### Backend
| Layer | Choice | Version |
|---|---|---|
| Language | Go | 1.22+ |
| HTTP server | stdlib `net/http` | — |
| WebSocket | `gorilla/websocket` | latest |
| Database | SQLite via `modernc.org/sqlite` | latest (CGo-free) |
| Migration | hand-rolled SQL in `db.go` `init()` (idempotent CREATE IF NOT EXISTS) | — |
| Password hash | `golang.org/x/crypto/bcrypt` | cost = 12 |
| JSON | stdlib `encoding/json` | — |
| UUID (where needed) | `github.com/google/uuid` | latest |
| Validation | hand-rolled in handlers (no external lib) | — |
| Logging | stdlib `log` + structured prefix | — |
| Testing | stdlib `testing` + `httptest` | — |

### Frontend (both Portal + Backoffice)
| Layer | Choice | Version |
|---|---|---|
| Bundler | Vite | 5+ |
| Framework | React | 19+ |
| Language | TypeScript | 5+ |
| Styling | Tailwind CSS | 4 |
| Routing | React Router | 6 |
| State | React Context + hooks (no Redux/Zustand) | — |
| API client | plain `fetch` wrapped in `src/api/client.ts` | — |
| UI library | hand-rolled + Tailwind (no MUI/AntD/shadcn for v1) | — |
| Form handling | controlled components + hand-rolled validation | — |
| Date utils | `Intl.DateTimeFormat` + ISO 8601 strings | stdlib |
| Package manager | `bun` | latest |

### Infra
- Dev hosting: localhost
- Prod hosting: **Hybrid stack** — GCP (Cloud Run for Go backend, Cloud SQL HA for Postgres, Memorystore for Redis) + Cloudflare R2 for object storage + CDN (free egress)
- TLS: Cloudflare proxy + Let's Encrypt on origin
- Subdomain split: `app.<domain>` (portal), `backoffice.<domain>`, `api.<domain>`

## 3. Conventions

### API design
- **REST style** for synchronous request/response
- **WebSocket** for real-time bidirectional (device commands + status updates)
- **Base path:** `/api/...` (no versioning in path; breaking changes documented in changelog)
- **Auth:** two session cookies (HttpOnly, Secure in prod, SameSite=Lax):
  - `tkr_session` — Portal session (set by `/api/auth/*`, scope='portal')
  - `tkr_admin_session` — Backoffice session (set by `/api/admin/auth/*`, scope='admin')
  - See §4 for the rationale and routing rules
- **CORS:** allow origins listed in `CORS_ORIGINS` env var; allow credentials
- **Content-Type:** `application/json` for request/response; `multipart/form-data` for file upload

### Error response format
```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Email or password incorrect"
  }
}
```
- HTTP status code reflects category (4xx client, 5xx server)
- `code` is stable machine-readable identifier (UPPER_SNAKE)
- `message` is human-readable, Thai or English per `Accept-Language` (v1: Thai default)

### Date/time
- All API datetime fields = **ISO 8601 UTC** (e.g., `"2026-05-26T10:30:00Z"`)
- Frontend renders in local timezone (Asia/Bangkok)

### IDs
- **Integer auto-increment** for users, videos, devices, sessions, lives, banners
- **UUID v4 string** for pair tokens, command IDs, WS message IDs
- Pair token format: 16-char base32 (e.g., `K7M2X9N4QH8T3WPY`)

### Pagination
- Query params: `?limit=20&offset=0`
- Response: `{ "items": [...], "total": 123, "limit": 20, "offset": 0 }`
- Default limit: 50; max: 200

### Validation rules (apply server-side AND client-side)
| Field | Constraint |
|---|---|
| email | RFC 5322 minimal, lowercase store, max 255 |
| password | min 8 chars, min 1 letter + 1 digit (no special required for v1) |
| device name | max 50 chars, allow Thai + English + numbers + spaces |
| video filename | max 100 chars, server stores sanitized version |
| video file | max 500 MB, mp4/mov, max 60 min |
| banner text | max 80 chars, utf-8 |
| live title | max 100 chars |
| live caption | max 500 chars |
| hashtag | max 10 tags, each max 30 chars, no spaces |

## 4. Auth & Session

### Two-cookie design (Portal vs Backoffice)

Portal (`localhost:5173` / `app.<domain>`) and Backoffice
(`localhost:5174` / `backoffice.<domain>`) share the same eTLD+1 in
development and (intentionally) the same registrable domain in production.
Browsers **ignore port** when scoping cookies, so a single cookie name would
be shared between the two SPAs — preventing the legitimate case of a user
being logged in to Portal as themselves while an admin is logged in to
Backoffice in the same browser.

To support simultaneous independent identities we use **two cookies and two
auth endpoint groups**:

| Cookie | Endpoints | `sessions.scope` | Used by |
|---|---|---|---|
| `tkr_session` | `/api/auth/*` (signup, login, logout, me, change-password) | `'portal'` | Portal SPA + every `/api/*` user-facing endpoint (videos, devices, lives, banners, billing, …) |
| `tkr_admin_session` | `/api/admin/auth/*` (login, logout, me, change-password) | `'admin'` | Backoffice SPA + every `/api/admin/*` endpoint |

Both cookies use HttpOnly, SameSite=Lax, Secure-in-prod, Max-Age=2592000,
Path=/. They live in the same browser jar without conflict because their
names differ.

### Session lifecycle
1. Login/signup → server generates random 32-byte session token (base64)
2. Insert into `sessions(token, user_id, scope, expires_at)` with 30-day expiry.
   - `/api/auth/signup` and `/api/auth/login` write `scope='portal'` and set `tkr_session`.
   - `/api/admin/auth/login` verifies `role='admin'` then writes `scope='admin'` and sets `tkr_admin_session`. Non-admin accounts get 403 FORBIDDEN with no cookie set.
3. Each request → middleware reads the relevant cookie → looks up session AND validates `scope` matches the endpoint:
   - `requireAuth` (Portal) accepts only `tkr_session` + `scope='portal'`.
   - `requireAdmin` (Backoffice) accepts only `tkr_admin_session` + `scope='admin'` + `user.role='admin'`. A demoted user's stale admin cookie is rejected with 403 and immediately deleted server-side.
4. Logout deletes only the row for the cookie that was presented — the other scope's session (if any) is untouched. Change-password deletes ALL other sessions for the user across both scopes (current session preserved) so a credential rotation propagates everywhere.
5. Migration: existing `sessions` rows are backfilled to `scope='portal'` via the column default — no data loss.

### Password rules
- Hash with bcrypt cost 12 on signup
- Verify with `bcrypt.CompareHashAndPassword`
- No password complexity beyond §3 validation
- Password reset (v1): admin-generated temp password via `/admin/users/:id/reset-password`
- **Forced password change after admin reset:**
  - `users.must_change_password` set to `true` when admin resets
  - Login/signup/me responses include `must_change_password` in user object
  - Portal & Backoffice clients MUST redirect to change-password screen and block normal navigation when `must_change_password === true`
  - User completes via `POST /api/auth/change-password` (current + new password)
  - On success: server clears flag + invalidates all other sessions

### Roles
| Role | Description |
|---|---|
| `user` | Default for self-serve signup — owns devices, videos, lives |
| `admin` | TiktokRerun team — can access `/api/admin/*` and Backoffice |

Bootstrap admin via env vars on server startup:
```
BOOTSTRAP_ADMIN_EMAIL=admin@example.com
BOOTSTRAP_ADMIN_PASSWORD=<set-by-pond>
```
If `users` table empty and both env vars set → insert with `role='admin'`

### Rate limiting (v1 — minimal)
- `POST /api/auth/login` — 5 attempts per IP per minute → 429 on excess
- `POST /api/auth/signup` — 3 signups per IP per hour
- Implementation: in-memory map (acceptable for single-instance MVP)

### CSRF
- SameSite=Lax cookie blocks most CSRF — sufficient for v1
- Add explicit CSRF token only if cross-origin POST becomes needed

## 5. Data Model (lock for v1)

```sql
-- Users
CREATE TABLE users (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    email                 TEXT NOT NULL UNIQUE,
    password_hash         TEXT NOT NULL,
    role                  TEXT NOT NULL DEFAULT 'user',  -- 'user' | 'admin'
    must_change_password  BOOLEAN NOT NULL DEFAULT 0,    -- forced after admin reset
    created_at            DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Sessions
CREATE TABLE sessions (
    token       TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope       TEXT NOT NULL DEFAULT 'portal',   -- 'portal' | 'admin'
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME NOT NULL
);
CREATE INDEX idx_sessions_user  ON sessions(user_id);
CREATE INDEX idx_sessions_scope ON sessions(scope);
-- Existing rows backfill to scope='portal' via the column default (idempotent).

-- Videos (extend existing)
ALTER TABLE videos ADD COLUMN owner_user_id INTEGER NOT NULL DEFAULT 0;
ALTER TABLE videos ADD COLUMN duration_sec INTEGER;  -- detected on upload
ALTER TABLE videos ADD COLUMN size_bytes  INTEGER;
CREATE INDEX idx_videos_owner ON videos(owner_user_id);

-- Devices (extend existing)
ALTER TABLE devices ADD COLUMN owner_user_id INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN name TEXT;
ALTER TABLE devices ADD COLUMN last_seen_at DATETIME;
CREATE INDEX idx_devices_owner ON devices(owner_user_id);

-- Pair tokens (persist instead of in-memory)
CREATE TABLE pair_tokens (
    token         TEXT PRIMARY KEY,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at    DATETIME NOT NULL,
    used_at       DATETIME
);
CREATE INDEX idx_pair_tokens_owner ON pair_tokens(owner_user_id);

-- Live sessions (history + active)
CREATE TABLE live_sessions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL REFERENCES devices(id),
    owner_user_id   INTEGER NOT NULL REFERENCES users(id),
    video_id        INTEGER REFERENCES videos(id),
    title           TEXT,
    caption         TEXT,
    hashtags        TEXT,                  -- comma-separated
    pinned_sku      TEXT,
    started_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    ended_at        DATETIME,
    end_reason      TEXT                   -- 'user_stop' | 'error' | 'tiktok_end'
);
CREATE INDEX idx_lives_device ON live_sessions(device_id);
CREATE INDEX idx_lives_owner  ON live_sessions(owner_user_id);

-- Banners (attached to videos OR live sessions)
CREATE TABLE banners (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id   INTEGER NOT NULL REFERENCES users(id),
    video_id        INTEGER REFERENCES videos(id),       -- nullable: video-level
    live_session_id INTEGER REFERENCES live_sessions(id),-- nullable: session-level (dynamic)
    slot            TEXT NOT NULL,         -- 'top' | 'bottom' | 'top-left' | etc.
    text            TEXT NOT NULL,
    bg_color        TEXT NOT NULL DEFAULT '#000000',
    text_color      TEXT NOT NULL DEFAULT '#FFFFFF',
    font_size       TEXT NOT NULL DEFAULT 'M',  -- 'S' | 'M' | 'L'
    deadline        DATETIME,              -- for countdown banners
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_banners_video ON banners(video_id);
CREATE INDEX idx_banners_live  ON banners(live_session_id);

-- Subscriptions (Stripe-backed billing)
CREATE TABLE subscriptions (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id                 INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_customer_id      TEXT NOT NULL,
    stripe_subscription_id  TEXT,                 -- nullable until checkout completes
    stripe_price_id         TEXT,                 -- which tier price
    tier                    TEXT NOT NULL,         -- 'starter' | 'growth' | 'pro' | 'enterprise'
    status                  TEXT NOT NULL,         -- 'pending' | 'active' | 'past_due' | 'canceled' | 'incomplete'
    current_period_start    DATETIME,
    current_period_end      DATETIME,
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT 0,
    created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_subscriptions_user ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_stripe_sub ON subscriptions(stripe_subscription_id);

-- Stripe webhook event log (for idempotency + audit)
CREATE TABLE stripe_events (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    stripe_event_id   TEXT NOT NULL UNIQUE,
    event_type        TEXT NOT NULL,
    payload_json      TEXT,
    processed_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Command audit log (last 90 days)
CREATE TABLE commands (
    id              TEXT PRIMARY KEY,      -- UUID v4
    owner_user_id   INTEGER NOT NULL REFERENCES users(id),
    device_id       INTEGER NOT NULL REFERENCES devices(id),
    type            TEXT NOT NULL,         -- 'start_live' | 'stop_live' | ...
    payload_json    TEXT,
    issued_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    ack_at          DATETIME,
    status          TEXT NOT NULL DEFAULT 'pending'  -- 'pending' | 'ack' | 'error'
);
CREATE INDEX idx_commands_device ON commands(device_id);
CREATE INDEX idx_commands_owner  ON commands(owner_user_id);
```

**Schema migration policy:** all DDL must be idempotent (use `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN` wrapped in error-tolerant check). No down-migrations for v1 — destructive changes require manual SQL.

## 6. Environment Variables

```bash
# Required
BOOTSTRAP_ADMIN_EMAIL=admin@example.com
BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
CORS_ORIGINS=http://localhost:5173,http://localhost:5174

# Optional (with defaults)
PORT=8080
DB_PATH=./rerun.db
UPLOAD_DIR=./uploads
SESSION_TTL_HOURS=720       # 30 days
SESSION_SECRET=<random-32b>  # used if we switch to signed cookies later
LOG_LEVEL=info

# Stripe (required for billing — Phase 2)
STRIPE_PUBLISHABLE_KEY=pk_test_...
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PRICE_PER_DEVICE=price_xxx  # Stripe Price ID for flat 299 บาท/device/month (metered/per-unit)
PORTAL_SUCCESS_URL=http://localhost:5173/billing/success
PORTAL_CANCEL_URL=http://localhost:5173/billing/cancel
```

Backend reads via `os.Getenv(...)`. Default values in code, override via env.

## 7. Module Responsibilities

### Backend (`server/`)
- `main.go` — entry point, env load, DB init, route registration, server start
- `db.go` — DB connection, schema init, query helpers
- `auth.go` — signup/login/logout/me handlers + bcrypt + session management
- `middleware.go` — `requireAuth`, `requireAdmin`, CORS, rate limit, request logging
- `handlers.go` — video, device, pair, live, banner, command endpoints (user-scoped)
- `admin.go` — admin endpoints (`/api/admin/*`)
- `ws.go` — `/ws/device` (mobile companion) + `/ws/portal` (browser real-time)

### Portal (`portal/`)
- `src/main.tsx` — entry point, router setup
- `src/api/client.ts` — fetch wrapper with credentials + error handling
- `src/api/{auth,videos,devices,lives,banners}.ts` — typed API call functions
- `src/contexts/AuthContext.tsx` — current user, login/logout
- `src/pages/{Login,Signup,Dashboard,Devices,Videos,LiveConfig,History}.tsx` — pages
- `src/components/...` — shared UI primitives
- `src/hooks/useWebSocket.ts` — portal WS for real-time updates

### Backoffice (`backoffice/`)
- `src/main.tsx`, `src/api/`, `src/contexts/` — mirrors portal pattern
- `src/pages/{AdminLogin,AdminUsers,AdminDevices,AdminVideos,AdminMetrics,AdminLives}.tsx`
- Different auth context: `requireAdmin` redirect logic

## 8. Error Handling Patterns

### Backend
```go
// Standard error response helper
func writeError(w http.ResponseWriter, status int, code, msg string) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(status)
    json.NewEncoder(w).Encode(map[string]any{
        "error": map[string]string{
            "code":    code,
            "message": msg,
        },
    })
}

// Usage
writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "อีเมลหรือรหัสผ่านไม่ถูกต้อง")
```

### Frontend
```typescript
// api/client.ts
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(body.error?.code ?? 'UNKNOWN', body.error?.message ?? res.statusText, res.status);
  }
  return res.json();
}
```

## 9. WebSocket Protocol

### Connection
- `/ws/device` — mobile companion app connects with pair token (first time) or device token (subsequent)
- `/ws/portal` — browser connects with session cookie

### Message format
```json
{
  "id": "uuid-v4",
  "type": "start_live",
  "payload": { ... },
  "timestamp": "2026-05-26T10:30:00Z"
}
```

### Liveness
- Ping every 30s, expect pong within 10s
- 60s of no activity → server closes; client reconnects with exponential backoff (1s, 2s, 4s, 8s, max 30s)

### Message types — see `api-contract.md` §3

## 10. Testing Expectations

### Backend
- Unit tests for: auth (bcrypt + session), middleware (auth, admin, CORS), validation helpers
- Integration tests via `httptest.Server` for: signup → login → authenticated request flow
- Skip: full WS load test for v1

### Frontend
- No required tests for v1 (focus on shipping)
- Suggested: basic component smoke tests for forms (login, signup, banner editor) via Vitest if time permits

## 11. Conventions ที่ห้ามแหก

- ❌ ห้าม introduce TanStack Query / Redux / Zustand / NextAuth ใน v1
- ❌ ห้ามใช้ MUI / Ant Design / Chakra — hand-rolled + Tailwind
- ❌ ห้ามเขียน JWT — session cookie only
- ❌ ห้ามใช้ ORM (Prisma, sqlc, ent) — raw SQL via `database/sql`
- ❌ ห้าม mock data ใน production code paths
- ❌ ห้ามใส่ feature ที่อยู่ใน "Out of scope" ของ PRD §6.2 หรือ portal-spec §10
- ✅ ใช้ `bun` ไม่ใช่ `npm`/`pnpm`/`yarn`
- ✅ ภาษา UI: Thai-first (text user-facing เป็นภาษาไทย; code/comments เป็นภาษาอังกฤษ)
- ✅ Commit message: imperative present tense, English
- ✅ ถ้าเจอ requirement ไม่ชัด → stop + ถาม Pond, don't guess

## 12. Definition of Done (per agent)

### API agent
- All endpoints in `api-contract.md` §2 implemented and responding correctly
- All WS message types in `api-contract.md` §3 handled
- All DB schema in §5 applied via idempotent migrations
- Unit + integration tests for auth pass
- Manual smoke test: signup → login → upload video → pair device → start live → stop live works end-to-end with curl

### Portal agent
- All routes in `portal-backoffice-spec.md` §5.2 rendered
- Auth flow works (login, signup, logout, protected redirect)
- Forms validate per §3 rules client-side
- WS connection shows live device status updates
- Manual smoke test: signup → upload video → pair device → start live → see status change

### Backoffice agent
- All routes in `portal-backoffice-spec.md` §6.2 rendered
- Admin auth flow works (separate login page)
- Lists paginate per §3 pagination convention
- Metrics dashboard pulls from `/api/admin/metrics`
- Manual smoke test: admin login → see users → see all devices across users → see metrics

## 13. Handover & Coordination

- **Owner of api-contract.md:** API agent (changes go through code review with Pond)
- **Cross-agent communication:** when an agent needs a new endpoint or message type, propose change via PR to `api-contract.md` first, then implement
- **Shared types:** Portal and Backoffice may share `src/types/api.ts` if convenient, but no required shared package for v1
- **Daily sync:** Pond reviews progress + answers blockers daily during MVP build
