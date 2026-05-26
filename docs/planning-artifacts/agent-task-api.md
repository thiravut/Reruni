# Agent Task Brief — API (Backend)

**Agent role:** Backend developer (Go)
**Repo path:** `/Users/pond/Developer/localhost/TikTok/Rerun/server/`
**Owner:** Pond
**Estimated effort:** ~3-4 weeks (single agent, full-time AI-assisted)

> **Read these docs FIRST before writing any code:**
> 1. [tech-spec.md](tech-spec.md) — locked decisions
> 2. [api-contract.md](api-contract.md) — endpoint + WS contracts (your source of truth)
> 3. [portal-backoffice-spec.md](portal-backoffice-spec.md) — context for portal/backoffice agents
> 4. [prd.md](prds/prd-TiktokRerun-2026-05-24/prd.md) §4 Features — what each capability does

---

## 1. Your scope

คุณเป็น **owner ของ backend ทั้งหมด** ที่ครอบคลุม:
- DB schema + migrations
- REST API endpoints (per api-contract.md §2)
- WebSocket handlers (`/ws/device` + `/ws/portal`)
- Auth + session management
- Middleware (auth, admin, CORS, rate limit)
- Pair token lifecycle
- Command queue + status reporting

คุณ **ไม่ทำ:** UI, mobile companion app, deployment scripts, infrastructure provisioning

## 2. Existing codebase context

```
server/
├── main.go        # entry point — extend to register new routes + bootstrap admin
├── db.go          # DB connection — extend schema per tech-spec.md §5
├── handlers.go    # existing video/device/pair handlers — extend with auth scope
├── ws.go          # existing device WS — extend with new message types + portal WS
└── web/index.html # legacy POC dashboard — keep functional during transition
```

> **DO NOT remove `server/web/` until portal is stable.** Pond will decide migration cutover.

## 3. Order of work (must follow)

### Phase A — Auth foundation (Week 1)
1. Read existing `server/*.go` files to understand patterns
2. Extend `server/db.go` with new schema (users, sessions, pair_tokens, live_sessions, banners, commands)
3. Create `server/auth.go` with bcrypt + session token helpers
4. Create `server/middleware.go` with `requireAuth`, `requireAdmin`, CORS, rate-limit
5. Implement endpoints: `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
6. Add bootstrap admin logic on startup (env vars)
7. **Checkpoint with Pond:** smoke test signup → login → /api/auth/me

### Phase B — Tenant-scope existing endpoints (Week 1-2)
8. Add `owner_user_id` column migration to videos + devices
9. Wrap all existing `/api/videos/*` and `/api/devices/*` with `requireAuth`
10. Add `WHERE owner_user_id = ?` to all SELECT queries
11. Add `owner_user_id` to all INSERT queries
12. Update pair flow: token bound to user, device row gets `owner_user_id` on first WS connect
13. **Checkpoint:** existing legacy `server/web/` dashboard still works for old data; new users see only their own

### Phase C — New endpoints (Week 2-3)
14. Live sessions: `POST /api/lives/start`, stop, switch-video, restart, volume
15. Products: pin/unpin
16. Banners: full CRUD per api-contract §2.7
17. Commands: `GET /api/commands/:id` for status polling
18. Pair tokens: migrate from in-memory `map[string]bool` to `pair_tokens` table
19. Live history endpoints

### Phase D — Admin endpoints (Week 3)
20. All `/api/admin/*` endpoints per api-contract §2.9
21. Use `requireAdmin` middleware

### Phase E — Portal WebSocket (Week 3-4)
22. New `/ws/portal` endpoint with session cookie auth
23. Implement Server → Browser push for: `device_status_changed`, `live_started`, `live_ended`, `command_completed`, `banner_updated`, `error_notice`
24. Server-side fanout: when device sends `status`, fanout to all open `/ws/portal` of the same owner
25. Heartbeat + reconnect logic per api-contract §3.8

### Phase F — Polish & test (Week 4)
26. Unit tests: auth, middleware, validation
27. Integration test: signup → login → upload → pair → start_live → ack → status update
28. Update error response format per tech-spec §8
29. Add request logging
30. **Final checkpoint with Pond:** all api-contract endpoints + WS messages working

## 4. Definition of Done

- [ ] Every endpoint in [api-contract.md](api-contract.md) §2 returns correct response
- [ ] Every WS message type in §3 handled by server
- [ ] All DB tables in [tech-spec.md](tech-spec.md) §5 created via idempotent migrations
- [ ] Auth flow works: signup, login, session expiry, logout
- [ ] Admin role gating works on `/api/admin/*`
- [ ] Pair flow ties device to current user; future commands respect ownership
- [ ] Rate limits enforced on auth endpoints
- [ ] Errors use standard format per tech-spec §8
- [ ] Manual smoke test via curl: full happy path works
- [ ] Integration tests pass: `go test ./...`

## 5. Coordination with other agents

### What you provide them
- Working API + WS at `http://localhost:8080`
- Working OAuth flow (signup, login, cookie set)
- Stable `api-contract.md` (PR changes via Pond review)

### What you need from them
- **Portal agent**: nothing blocking — they consume your API
- **Backoffice agent**: confirm admin endpoint shapes match their needs (review §2.9)

### Communication
- Daily progress update to Pond (Slack/Line/whatever channel Pond decides)
- Surface API contract changes via PR to `api-contract.md` BEFORE implementing
- If portal/backoffice agent asks for new endpoint → propose addition in api-contract.md first

## 6. Don'ts

- ❌ Don't add JWT — session cookie only
- ❌ Don't add ORM (Prisma/sqlc/ent) — raw SQL
- ❌ Don't introduce a new framework (Fiber, Echo, Gin) — stdlib `net/http`
- ❌ Don't change `server/web/` legacy dashboard
- ❌ Don't implement features not in api-contract.md without Pond approval
- ❌ Don't break existing POC functionality during migration
- ❌ Don't commit secrets or `.env` files

## 7. Reference snippets

### Session token generation
```go
import (
    "crypto/rand"
    "encoding/base64"
)

func generateSessionToken() string {
    b := make([]byte, 32)
    rand.Read(b)
    return base64.URLEncoding.EncodeToString(b)
}
```

### Pair token format
```go
import (
    "crypto/rand"
    "encoding/base32"
    "strings"
)

func generatePairToken() string {
    b := make([]byte, 10) // 80 bits → 16-char base32
    rand.Read(b)
    return strings.ToUpper(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}
```

### Bcrypt
```go
import "golang.org/x/crypto/bcrypt"

hash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
// ... store hash

err := bcrypt.CompareHashAndPassword(hash, []byte(inputPassword))
// err == nil → match
```

### Error response helper
```go
func writeError(w http.ResponseWriter, status int, code, msg string) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(status)
    json.NewEncoder(w).Encode(map[string]any{
        "error": map[string]string{"code": code, "message": msg},
    })
}
```

## 8. Test plan (suggested)

```bash
# 1. Signup
curl -i -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"testpass1"}' \
  -c cookies.txt

# 2. Authed request
curl -b cookies.txt http://localhost:8080/api/auth/me

# 3. Upload video
curl -b cookies.txt -X POST http://localhost:8080/api/videos \
  -F "file=@./sample.mp4" -F "name=Test"

# 4. Pair token
curl -b cookies.txt -X POST http://localhost:8080/api/pair/token

# 5. (mobile app or wscat) connect to WS with token, send "pair" message
wscat -c ws://localhost:8080/ws/device

# 6. Start live
curl -b cookies.txt -X POST http://localhost:8080/api/lives/start \
  -H "Content-Type: application/json" \
  -d '{"device_ids":[1],"video_id":1,"title":"Test Live"}'
```

## 9. Questions to ask Pond before starting

1. Existing `videos` and `devices` table schema — do they have any columns I'm missing? (Read `server/db.go`)
2. Existing pair flow on mobile companion app — does it already send `device_info`? (Coordinate with mobile track)
3. Bootstrap admin password rotation — single-use after first run, or always available?
4. Specific Sentry/logging tool — yes or no for v1?
5. Live history retention — 90 days hard, or keep forever?

When unsure → **stop and ask, don't guess.**
