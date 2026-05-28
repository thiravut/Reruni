# TiktokRerun — API Contract

**Status:** v1 contract — locked for MVP build
**Date:** 2026-05-26
**Owner:** API agent (changes via PR review with Pond)
**Reads alongside:** [tech-spec.md](tech-spec.md)

> **Conventions** (from tech-spec.md §3):
> - Base path `/api/...` ทุก endpoint
> - **Auth uses TWO cookies** (HttpOnly, SameSite=Lax):
>   - `tkr_session` — Portal/member session (set by `/api/auth/*`, scope='portal')
>   - `tkr_admin_session` — Backoffice/admin session (set by `/api/admin/auth/*`, scope='admin')
>   - Cookies are independent: Portal and Backoffice can be logged in as different identities in the same browser
> - All datetime = ISO 8601 UTC
> - Error format: `{ "error": { "code": "...", "message": "..." } }`
> - Pagination: `?limit=20&offset=0`
> - IDs: integer auto-increment (except pair tokens = base32, command IDs = UUID v4)

---

## 1. Auth Level Conventions

| Tag | Description |
|---|---|
| 🔓 Public | No auth required |
| 🔒 User | Requires valid `tkr_session` cookie (Portal scope). `role` may be `user` or `admin`, but admins acting as users still need a Portal session. |
| 👑 Admin | Requires valid `tkr_admin_session` cookie (Backoffice scope) AND `role='admin'`. The portal cookie is NOT accepted. |
| 🤖 Device | WebSocket auth via pair token (first) or device token (subsequent) |

---

## 2. REST Endpoints

### 2.1 Auth (`/api/auth/*`) — Portal scope

> All endpoints in this section read/write the **`tkr_session`** cookie
> (scope='portal'). This is the member-facing session — Portal SPA uses it for
> every user-owned resource (videos, devices, lives, banners, billing, etc.).
> The backoffice cookie `tkr_admin_session` is rejected by these endpoints.

#### 🔓 `POST /api/auth/signup`
Create a new user account.

**Request:**
```json
{ "email": "user@example.com", "password": "minimum8chars1" }
```

**Response 201:**
```json
{
  "user": { "id": 42, "email": "user@example.com", "role": "user", "created_at": "2026-05-26T10:00:00Z", "must_change_password": false },
  "expires_at": "2026-06-25T10:00:00Z"
}
```
+ Sets cookie `tkr_session=...`

**Errors:**
- `400 INVALID_EMAIL` — email format invalid
- `400 WEAK_PASSWORD` — password fails validation rules
- `409 EMAIL_TAKEN` — email already registered
- `429 RATE_LIMITED` — too many signups from IP

---

#### 🔓 `POST /api/auth/login`
Authenticate existing user.

**Request:**
```json
{ "email": "user@example.com", "password": "..." }
```

**Response 200:**
```json
{
  "user": { "id": 42, "email": "user@example.com", "role": "user", "created_at": "2026-05-26T10:00:00Z", "must_change_password": false },
  "expires_at": "2026-06-25T10:00:00Z"
}
```
+ Sets cookie `tkr_session=...`

**Errors:**
- `401 INVALID_CREDENTIALS` — email or password wrong (don't disambiguate)
- `429 RATE_LIMITED` — too many failed attempts from IP

---

#### 🔒 `POST /api/auth/logout`
Invalidate current session.

**Response 204** + Clears cookie

---

#### 🔒 `GET /api/auth/me`
Get current user with subscription state.

**Response 200:**
```json
{
  "user": {
    "id": 42,
    "email": "user@example.com",
    "role": "user",
    "created_at": "...",
    "must_change_password": false,
    "subscription_status": "active",
    "subscription_tier": "starter"
  }
}
```

`subscription_status` values: `none` | `pending` | `active` | `past_due` | `canceled` | `incomplete`

> Portal/Backoffice clients use this field to gate feature access. When `subscription_status !== 'active'`, ALL feature pages redirect to `/billing` (or `/subscribe` for new users).

**Errors:**
- `401 UNAUTHORIZED` — no/invalid session

---

#### 🔒 `POST /api/auth/change-password`
Change current user's password. Required after admin password reset (when `must_change_password: true`).

**Request:**
```json
{ "current_password": "...", "new_password": "..." }
```

**Response 200:**
```json
{ "user": { "id": 42, "email": "user@example.com", "role": "user", "must_change_password": false, ... } }
```

Side effects:
- Updates `users.password_hash` with new bcrypt hash
- Sets `users.must_change_password = false`
- Invalidates all other sessions for this user (forces re-login on other devices)

**Errors:**
- `401 INVALID_CREDENTIALS` — current_password wrong
- `400 WEAK_PASSWORD` — new_password fails validation

> **Important:** Both signup login responses AND `/auth/me` MUST include `must_change_password` field. Portal/Backoffice clients MUST redirect to a change-password screen and refuse to proceed with normal app navigation when `must_change_password === true`.

---

### 2.1b Admin Auth (`/api/admin/auth/*`) — Backoffice scope

> All endpoints in this section read/write the **`tkr_admin_session`** cookie
> (scope='admin'). They are distinct from `/api/auth/*` so that a Portal session
> and a Backoffice session can coexist independently in the same browser. The
> portal cookie `tkr_session` is rejected by these endpoints.

#### 🔓 `POST /api/admin/auth/login`
Authenticate as admin. **Only `role='admin'` accounts may obtain an admin session.**

**Request:**
```json
{ "email": "admin@example.com", "password": "..." }
```

**Response 200:**
```json
{
  "user": { "id": 1, "email": "admin@example.com", "role": "admin", "created_at": "...", "must_change_password": false },
  "expires_at": "2026-06-25T10:00:00Z"
}
```
+ Sets cookie `tkr_admin_session=...`

**Errors:**
- `401 INVALID_CREDENTIALS` — email or password wrong
- `403 FORBIDDEN` — credentials valid but account is not an admin (no cookie set)
- `429 RATE_LIMITED` — too many failed attempts from IP

---

#### 👑 `POST /api/admin/auth/logout`
Invalidate the current admin session.

**Response 204** + Clears `tkr_admin_session` cookie. Does NOT touch any portal session the user may also hold.

---

#### 👑 `GET /api/admin/auth/me`
Get current admin identity. Only resolves admin-scoped sessions — a portal cookie alone returns 401.

**Response 200:**
```json
{
  "user": {
    "id": 1, "email": "admin@example.com", "role": "admin",
    "created_at": "...", "must_change_password": false
  }
}
```

**Errors:**
- `401 UNAUTHORIZED` — no/invalid admin session
- `403 FORBIDDEN` — admin session present but user no longer has `role='admin'` (server invalidates the stale session)

---

#### 👑 `POST /api/admin/auth/change-password`
Change the current admin's password. Same shape as `/api/auth/change-password`. Invalidates every other session for this user (both portal AND admin scopes) except the current admin session.

**Request:**
```json
{ "current_password": "...", "new_password": "..." }
```

**Response 200:** `{ "user": { ... } }`

**Errors:**
- `401 INVALID_CREDENTIALS` — current_password wrong
- `400 WEAK_PASSWORD` — new_password fails validation

---

### 2.2 Videos (`/api/videos`)

#### 🔒 `GET /api/videos?limit=20&offset=0`
List videos owned by current user.

**Response 200:**
```json
{
  "items": [
    {
      "id": 1,
      "filename": "promo-11am.mp4",
      "duration_sec": 180,
      "size_bytes": 12345678,
      "uploaded_at": "2026-05-26T10:00:00Z"
    }
  ],
  "total": 1, "limit": 20, "offset": 0
}
```

---

#### 🔒 `POST /api/videos` (multipart)
Upload a new video.

**Request:** `multipart/form-data`
- field `file` — video file (mp4/mov, max 500 MB, max 60 min)
- field `name` — optional display name (else use filename)

**Response 201:**
```json
{
  "id": 5,
  "filename": "sanitized-name.mp4",
  "duration_sec": 180,
  "size_bytes": 12345678,
  "uploaded_at": "2026-05-26T10:30:00Z"
}
```

**Errors:**
- `400 INVALID_FORMAT` — not mp4/mov
- `400 FILE_TOO_LARGE` — > 500 MB
- `400 DURATION_EXCEEDED` — > 60 min

---

#### 🔒 `DELETE /api/videos/:id`
Delete a video (only if owned by current user).

**Response 204**

**Errors:**
- `404 VIDEO_NOT_FOUND` — not exists or not owned
- `409 VIDEO_IN_USE` — currently broadcasting on ≥1 device

---

### 2.3 Devices (`/api/devices`)

#### 🔒 `GET /api/devices?limit=50`
List devices owned by current user with status.

**Response 200:**
```json
{
  "items": [
    {
      "id": 11,
      "name": "Snacks-1",
      "status": "live",
      "current_video_id": 5,
      "current_pinned_sku": "SKU-2024",
      "last_seen_at": "2026-05-26T10:30:00Z"
    }
  ],
  "total": 1, "limit": 50, "offset": 0
}
```

Status values: `idle` | `pairing` | `live` | `error` | `offline`

---

#### 🔒 `PATCH /api/devices/:id`
Update device metadata (name only for v1).

**Request:**
```json
{ "name": "Snacks-1" }
```

**Response 200:** updated device object

**Errors:**
- `404 DEVICE_NOT_FOUND`

---

#### 🔒 `DELETE /api/devices/:id`
Unpair device. Future pair token must be re-issued.

**Response 204**

---

### 2.4 Pairing (`/api/pair/*`)

#### 🔒 `POST /api/pair/token`
Generate a new pairing token for the current user.

**Response 201:**
```json
{
  "token": "K7M2X9N4QH8T3WPY",
  "expires_at": "2026-05-26T10:35:00Z",
  "qr_url": "/api/pair/qr?token=K7M2X9N4QH8T3WPY"
}
```

Token TTL: 5 minutes.

---

#### 🔓 `GET /api/pair/qr?token=...`
Returns a PNG QR code image (for embedding in `<img>`).

**Response 200:** `image/png` body

---

### 2.5 Live Sessions (`/api/lives/*`)

#### 🔒 `POST /api/lives/start`
Start a live session on one or more devices.

**Request:**
```json
{
  "device_ids": [11, 12, 13],
  "video_ids": [5, 7, 9],
  "loop_count": 3,
  "loop_forever": false,
  "title": "Flash Sale 11AM",
  "caption": "ส่งฟรีวันนี้!",
  "hashtags": ["flashsale", "ส่งฟรี"],
  "pinned_sku": "SKU-2024"
}
```

- `video_ids` (preferred) — list of video IDs to broadcast. The server
  assigns one video per device using round-robin indexing
  `device_ids[i] → video_ids[i % len(video_ids)]`:
  - 1 video, N devices → every device plays the same video (legacy behaviour)
  - K videos, N devices (K ≤ N) → videos cycle, diversifying the fleet so
    TikTok's duplicate-stream heuristics see distinct content per phone
  - K videos, N devices (K > N) → first N videos play, extras unused
- `video_id` (legacy) — single integer, accepted for backward compatibility.
  Equivalent to `video_ids: [video_id]`. When both are sent, `video_ids`
  wins.
- `loop_count` (optional, integer, 1–1000) — how many full passes through
  the assigned video the device should play. Default `1` (play once).
- `loop_forever` (optional, bool) — when `true`, overrides `loop_count`
  and tells the device to keep looping until explicitly stopped. The
  server persists this as `loop_count: null` in `live_sessions`.

The mobile companion still receives one `start_live` envelope per device
carrying a single `video_url`/`video_id` plus a `loop_count` field
(`null` for forever, `N` for finite). The playlist split + loop policy
are purely server-side state — this endpoint can ship without a mobile
rebuild; older mobile clients ignore the new field and play once.

**Response 202:**
```json
{
  "commands": [
    { "command_id": "uuid-1", "device_id": 11 },
    { "command_id": "uuid-2", "device_id": 12 },
    { "command_id": "uuid-3", "device_id": 13 }
  ]
}
```

Async — device may report success via WS status. Use `GET /api/commands/:id` to poll.

**Errors:**
- `400 INVALID_INPUT` — neither `video_ids` nor `video_id` supplied, or
  `loop_count` is < 1 / > 1000
- `400 NO_DEVICES_ONLINE` — none of the device_ids currently online
- `404 VIDEO_NOT_FOUND` — error message includes the offending video id
- `403 DEVICE_NOT_OWNED` — at least one device not owned by current user

---

#### 🔒 `POST /api/lives/:device_id/stop`
Stop the active live on a specific device.

**Response 202:** `{ "command_id": "uuid" }`

---

#### 🔒 `POST /api/lives/:device_id/switch-video`
Switch video on a running live (no restart).

**Request:** `{ "video_id": 7 }`
**Response 202:** `{ "command_id": "uuid" }`

---

#### 🔒 `POST /api/lives/:device_id/restart`
Restart a failed live session.

**Response 202:** `{ "command_id": "uuid" }`

---

#### 🔒 `PATCH /api/lives/:device_id/volume`
Adjust audio volume of broadcast.

**Request:** `{ "volume": 75 }` (0-100)
**Response 202:** `{ "command_id": "uuid" }`

---

#### 🔒 `GET /api/lives/active?limit=50`
List currently active live sessions for current user.

**Response 200:**
```json
{
  "items": [
    {
      "id": 101,
      "device_id": 11,
      "video_id": 5,
      "title": "Flash Sale 11AM",
      "pinned_sku": "SKU-2024",
      "started_at": "2026-05-26T11:00:00Z"
    }
  ]
}
```

---

#### 🔒 `GET /api/lives/history?limit=50&offset=0&device_id=11`
List ended live sessions (last 90 days).

**Response 200:** paginated list including `ended_at`, `end_reason`

---

### 2.6 Products (`/api/lives/:device_id/products/*`)

#### 🔒 `POST /api/lives/:device_id/products/pin`
Pin a product on an active live.

**Request:** `{ "sku": "SKU-2024" }`
**Response 202:** `{ "command_id": "uuid" }`

---

#### 🔒 `POST /api/lives/:device_id/products/unpin`
Unpin current product.

**Response 202:** `{ "command_id": "uuid" }`

---

### 2.7 Banners (`/api/banners/*`)

#### 🔒 `GET /api/banners?video_id=5`
List banners attached to a video (static).

**Response 200:**
```json
{
  "items": [
    {
      "id": 33,
      "video_id": 5,
      "slot": "top",
      "text": "FLASH SALE -50%",
      "bg_color": "#FF3D00",
      "text_color": "#FFFFFF",
      "font_size": "L",
      "deadline": null
    }
  ]
}
```

---

#### 🔒 `POST /api/banners`
Create a static banner (attached to a video) OR dynamic banner (attached to live session).

**Request (video-level static):**
```json
{
  "video_id": 5,
  "slot": "top",
  "text": "Promo!",
  "bg_color": "#FF3D00",
  "text_color": "#FFFFFF",
  "font_size": "L"
}
```

**Request (live-session dynamic):**
```json
{
  "live_session_id": 101,
  "slot": "top",
  "text": "เหลือเวลา 02:45",
  "deadline": "2026-05-26T12:00:00Z",
  "bg_color": "#000000",
  "text_color": "#FFFFFF",
  "font_size": "M"
}
```

**Response 201:** banner object

---

#### 🔒 `PATCH /api/banners/:id`
Update an existing banner (dynamic updates).

**Request:** partial banner object

**Response 200:** updated banner

> Triggers WS `update_banner` event to relevant device(s).

---

#### 🔒 `DELETE /api/banners/:id`
Remove a banner.

**Response 204**

---

### 2.7b Billing (`/api/billing/*`) — Stripe-backed

> **Subscription model:** No free trial. Account signup is free, but `subscription.status` defaults to `pending`. User must complete checkout to reach `active`. All feature endpoints (videos upload, devices pair, lives start, banners, etc.) require `active` subscription via `requireActiveSubscription` middleware applied after `requireAuth`.

#### 🔒 `GET /api/billing/subscription`
Get current user's subscription state.

**Response 200:**
```json
{
  "subscription": {
    "tier": "starter",
    "status": "active",
    "stripe_subscription_id": "sub_...",
    "current_period_start": "2026-05-26T00:00:00Z",
    "current_period_end": "2026-06-26T00:00:00Z",
    "cancel_at_period_end": false
  }
}
```

If user has no subscription row: `{ "subscription": null }`.

Status values: `pending` | `active` | `past_due` | `canceled` | `incomplete`

---

#### 🔒 `GET /api/billing/tiers`
List available subscription tiers (Stripe products + prices).

**Response 200:**
```json
{
  "tiers": [
    { "key": "starter",  "name": "Starter",    "devices": 10,   "price_thb": 1990,  "stripe_price_id": "price_..." },
    { "key": "growth",   "name": "Growth",     "devices": 30,   "price_thb": 4990,  "stripe_price_id": "price_..." },
    { "key": "pro",      "name": "Pro",        "devices": 100,  "price_thb": 14900, "stripe_price_id": "price_..." }
  ]
}
```

Enterprise tier ไม่อยู่ใน list — แสดง "ติดต่อเรา" ใน UI

---

#### 🔒 `POST /api/billing/checkout-session`
Create Stripe Checkout session for subscribing.

**Request:** `{ "tier": "starter" }`

**Response 200:** `{ "checkout_url": "https://checkout.stripe.com/c/pay/cs_test_..." }`

Side effects:
- Create or reuse Stripe Customer (store `stripe_customer_id` in subscriptions table)
- Create Checkout Session with `success_url` + `cancel_url`
- Insert subscription row with `status='pending'` if not exists

**Errors:**
- `400 INVALID_TIER` — unknown tier
- `409 ALREADY_SUBSCRIBED` — already has active subscription (use portal to change)

---

#### 🔒 `POST /api/billing/portal-session`
Create Stripe Customer Portal session (manage subscription, payment, invoices).

**Response 200:** `{ "portal_url": "https://billing.stripe.com/p/session/..." }`

**Errors:**
- `404 NO_SUBSCRIPTION` — user has no subscription record yet

---

#### 🔒 `POST /api/billing/cancel`
Cancel subscription at period end.

**Response 200:** `{ "subscription": { ... } }` (with `cancel_at_period_end: true`)

---

#### 🔒 `POST /api/billing/sync`
Pull latest subscription state directly from Stripe API and upsert to local DB. Use when webhook isn't configured (local dev without Stripe CLI) or to manually reconcile a payment the webhook missed.

**Response 200:**
```json
{
  "subscription": { "tier": "starter", "status": "active", ... },
  "synced": true
}
```

`synced: false` if Stripe has no subscriptions for this customer (e.g. checkout not completed yet).

**Errors:**
- `404 NO_SUBSCRIPTION` — user has no subscription record (haven't started checkout)
- `404 NO_CUSTOMER` — subscription row exists but no Stripe customer (data anomaly)
- `502 STRIPE_ERROR` — Stripe API call failed

---

#### 🔓 `POST /api/billing/webhook`
Stripe webhook receiver. Verifies signature via `STRIPE_WEBHOOK_SECRET`.

**Headers:** `Stripe-Signature: t=...,v1=...`
**Request body:** raw Stripe event payload (do NOT pre-parse)

**Response 200** when handled successfully (Stripe retries on non-2xx).

Events to handle:
- `checkout.session.completed` → update subscription row to `active`, fill `stripe_subscription_id`, `current_period_end`
- `customer.subscription.updated` → sync `status`, `current_period_end`, `cancel_at_period_end`
- `customer.subscription.deleted` → set status `canceled`
- `invoice.payment_failed` → set status `past_due`
- `invoice.paid` → confirm `active`

Idempotency: dedupe by `event.id` via `stripe_events` table.

---

#### 👑 `GET /api/admin/subscriptions?status=active&limit=50`
List all subscriptions across users (admin view).

**Response 200:** paginated list with owner email + tier + status + period dates

---

#### 👑 `POST /api/admin/subscriptions/{user_id}/recheck`
Admin-callable: pull latest subscription state from Stripe API for the specified user and upsert to local DB. Use to reconcile pending subscriptions when webhook isn't configured.

**Response 200:**
```json
{
  "subscription": { "tier": "starter", "status": "active", ... },
  "synced": true
}
```

`synced: false` = Stripe has no subscriptions for this user's customer record.

**Errors:**
- `404 NO_SUBSCRIPTION` — user has no subscription record (haven't started checkout)
- `404 NO_CUSTOMER` — subscription row exists but no Stripe customer
- `502 STRIPE_ERROR` — Stripe API call failed

---

### 2.8 Commands (`/api/commands/*`)

#### 🔒 `GET /api/commands/:id`
Get command status.

**Response 200:**
```json
{
  "id": "uuid",
  "type": "start_live",
  "device_id": 11,
  "issued_at": "2026-05-26T10:30:00Z",
  "ack_at": "2026-05-26T10:30:05Z",
  "status": "ack",
  "error": null
}
```

Status values: `pending` | `ack` | `error`

---

### 2.9 Admin (`/api/admin/*`)

All endpoints require 👑 admin role — that is, a valid **`tkr_admin_session`**
cookie issued by `/api/admin/auth/login` (see §2.1b) AND the underlying user
must still have `role='admin'`. The portal cookie (`tkr_session`) is rejected
even if the user is an admin in another tab; admins must sign in to the
Backoffice explicitly.

#### 👑 `GET /api/admin/users?limit=50&offset=0&q=email`
List all users.

**Response 200:**
```json
{
  "items": [
    {
      "id": 42,
      "email": "user@example.com",
      "role": "user",
      "created_at": "...",
      "device_count": 5,
      "video_count": 12,
      "last_active_at": "2026-05-26T09:00:00Z"
    }
  ],
  "total": 100, "limit": 50, "offset": 0
}
```

---

#### 👑 `PATCH /api/admin/users/:id/role`
Promote/demote a user.

**Request:** `{ "role": "admin" }` or `{ "role": "user" }`

**Response 200:** updated user

---

#### 👑 `POST /api/admin/users/:id/reset-password`
Generate temp password for user. Admin distributes via out-of-band channel (LINE, email, in-person).

**Response 200:** `{ "temp_password": "Xy9p2Kq4" }`

Side effects:
- Replaces `users.password_hash` with bcrypt hash of temp_password
- Sets `users.must_change_password = true` — **user is forced to change password at next login**
- Invalidates all existing sessions for the user (forces re-login)

> Temp password is shown to admin **once** in this response — never retrievable again.

---

#### 👑 `DELETE /api/admin/users/:id`
Hard delete user + all their devices, videos, sessions, etc.

**Response 204**

---

#### 👑 `GET /api/admin/devices?limit=50&offset=0&status=live`
List all devices across all users.

**Response 200:**
```json
{
  "items": [
    {
      "id": 11,
      "name": "Snacks-1",
      "owner_email": "user@example.com",
      "owner_user_id": 42,
      "status": "live",
      "last_seen_at": "..."
    }
  ]
}
```

---

#### 👑 `POST /api/admin/devices/:id/disconnect`
Force-disconnect a device's WebSocket (admin intervention).

**Response 204**

---

#### 👑 `GET /api/admin/videos?limit=50&offset=0`
List all videos across all users (disk usage view).

**Response 200:**
```json
{
  "items": [
    {
      "id": 5,
      "filename": "promo.mp4",
      "owner_email": "user@example.com",
      "size_bytes": 12345678,
      "uploaded_at": "..."
    }
  ],
  "total_disk_bytes": 5000000000
}
```

---

#### 👑 `GET /api/admin/metrics`
Aggregate platform metrics.

**Response 200:**
```json
{
  "users_total": 100,
  "users_active_7d": 45,
  "devices_total": 350,
  "devices_online": 220,
  "devices_live": 180,
  "lives_24h": 1240,
  "broadcast_hours_24h": 3500,
  "disk_used_bytes": 5000000000,
  "uptime_pct_30d": 99.6
}
```

---

#### 👑 `GET /api/admin/lives?status=active&limit=50&offset=0`
List active or recent live sessions across all users.

Query params:
- `status` — `active` | `ended` | `all` (default `all`)
- `limit`, `offset` — pagination

**Response 200:**
```json
{
  "items": [
    {
      "id": 101,
      "device_id": 11,
      "device_name": "Snacks-1",
      "owner_user_id": 42,
      "owner_email": "user@example.com",
      "video_id": 5,
      "title": "Flash Sale 11AM",
      "caption": "ส่งฟรีวันนี้!",
      "pinned_sku": "SKU-2024",
      "status": "live",
      "started_at": "2026-05-26T11:00:00Z",
      "ended_at": null,
      "end_reason": null
    }
  ],
  "total": 1, "limit": 50, "offset": 0
}
```

Status values: `live` | `error` | `ended`

---

#### 👑 `POST /api/admin/lives/:id/force-stop`
Force-stop a specific live session (any user).

**Response 204**

---

## 3. WebSocket Protocol

### 3.1 Channels

| Path | Auth | Purpose |
|---|---|---|
| `/ws/device` | pair token (first) → device token (assigned by server) | mobile companion ↔ server |
| `/ws/portal` | session cookie | browser ↔ server (real-time UI updates) |

### 3.2 Message envelope

```json
{
  "id": "uuid-v4",
  "type": "...",
  "payload": { ... },
  "timestamp": "2026-05-26T10:30:00Z"
}
```

- `id` — unique per message (used for ack correlation)
- `type` — see message catalog below
- `timestamp` — sender's UTC time

### 3.3 `/ws/device` — Server → Device messages

| Type | Payload | Purpose |
|---|---|---|
| `start_live` | `{ video_url, video_id, title, caption, hashtags, pinned_sku, banners[] }` | Begin Smart Overlay broadcast |
| `stop_live` | `{}` | End current live session |
| `switch_video` | `{ video_url, video_id }` | Mid-live video change |
| `pin_product` | `{ sku }` | Pin a SKU |
| `unpin_product` | `{}` | Unpin current |
| `update_banner` | `{ slot, text, bg_color, text_color, font_size, deadline?, action: "add"\|"update"\|"remove" }` | Real-time banner change |
| `set_volume` | `{ volume }` | 0-100 audio |
| `restart_live` | `{}` | Recovery |
| `force_disconnect` | `{ reason }` | Admin-issued disconnect |
| `ping` | `{}` | Liveness check |

### 3.4 `/ws/device` — Device → Server messages

| Type | Payload | Purpose |
|---|---|---|
| `pair` | `{ token: "K7M2X9N4QH8T3WPY", device_info: {...} }` | First connection with pair token |
| `hello` | `{ device_token, app_version }` | Subsequent connections |
| `status` | `{ stream_status, current_video_id?, current_pinned_sku?, viewer_count?, fps?, battery_pct? }` | Periodic status push (~10s interval) |
| `ack` | `{ command_id, success, error_code?, error_message? }` | Acknowledge a server command |
| `error` | `{ command_id?, code, message }` | Async error report |
| `live_ended` | `{ live_session_id, reason }` | Mobile finished broadcasting on its own — fired when the configured `loop_count` passes complete, on a fatal playback error, or on a local user-stop. Server closes the `live_sessions` row, flips the device back to `idle`, and fans `live_ended` + `device_status_changed` out to the portal. `reason` is free-form; common values: `loop_completed`, `playback_error`, `device_stopped`. Omitted/empty → server defaults to `loop_completed`. |
| `device_caps` | `{ overlay_permission, notification_permission, battery_unrestricted, accessibility_enabled, tiktok_installed, app_version, android_sdk, device_model, sku_tier, ... }` | Permission + install state snapshot. Sent right after pair/hello; portal renders readiness badges + setup hints from this. |
| `pong` | `{}` | Response to ping |

### 3.5 `/ws/portal` — Server → Browser messages

Pushed to all open portal sessions of the relevant user.

| Type | Payload | Purpose |
|---|---|---|
| `device_status_changed` | `{ device_id, status, last_seen_at }` | Reflect status update |
| `live_started` | `{ live_session_id, device_id, video_id }` | New live appeared |
| `live_ended` | `{ live_session_id, device_id, end_reason }` | Live closed |
| `command_completed` | `{ command_id, status, error?: {...} }` | Earlier command finished |
| `banner_updated` | `{ banner_id, live_session_id }` | Banner change confirmed |
| `error_notice` | `{ device_id, code, message }` | Async error worth surfacing |

### 3.6 `/ws/portal` — Browser → Server messages (v1 minimal)

| Type | Payload | Purpose |
|---|---|---|
| `subscribe` | `{ device_ids: [11,12] }` | Filter updates to specific devices (optional) |
| `pong` | `{}` | Liveness response |

### 3.7 Pairing flow (sequence)

```
1. Browser:   POST /api/pair/token              → { token, qr_url }
2. Browser:   show QR
3. Mobile:    scan QR → extract token
4. Mobile:    WS connect /ws/device
5. Mobile:    send { type: "pair", payload: { token, device_info } }
6. Server:    validate token, create devices row, generate device_token
7. Server:    send { type: "paired", payload: { device_id, device_token } }
8. Server:    push to /ws/portal: { type: "device_status_changed", ... }
9. Mobile:    persist device_token for future connections
```

### 3.8 Heartbeat & reconnect

- Server sends `ping` every 30s on both `/ws/device` and `/ws/portal`
- Client must reply with `pong` within 10s
- Missed pong → connection closed
- Client reconnects with exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s cap

---

## 4. Error Codes Catalog

| HTTP | Code | When |
|---|---|---|
| 400 | INVALID_EMAIL | Email format invalid |
| 400 | WEAK_PASSWORD | Password fails validation |
| 400 | INVALID_FORMAT | Wrong file/data format |
| 400 | FILE_TOO_LARGE | Upload exceeds limit |
| 400 | DURATION_EXCEEDED | Video too long |
| 400 | NO_DEVICES_ONLINE | Can't start live, no online devices |
| 400 | INVALID_INPUT | Generic validation fail |
| 401 | UNAUTHORIZED | No/invalid session |
| 401 | INVALID_CREDENTIALS | Wrong email/password |
| 403 | FORBIDDEN | Authenticated but lacks permission |
| 403 | DEVICE_NOT_OWNED | Trying to access device owned by another user |
| 404 | NOT_FOUND | Generic |
| 404 | VIDEO_NOT_FOUND | Specific |
| 404 | DEVICE_NOT_FOUND | Specific |
| 404 | LIVE_NOT_FOUND | Specific |
| 404 | BANNER_NOT_FOUND | Specific |
| 409 | EMAIL_TAKEN | Signup with existing email |
| 409 | VIDEO_IN_USE | Can't delete; currently broadcasting |
| 409 | DEVICE_LIVE | Operation requires idle device |
| 413 | PAYLOAD_TOO_LARGE | HTTP limit hit |
| 429 | RATE_LIMITED | Too many requests |
| 500 | INTERNAL_ERROR | Server-side bug or DB error |
| 503 | UNAVAILABLE | Service temporarily down |

---

## 5. Change Management

- Any change to this contract requires PR review from Pond
- Breaking changes require version bump (add `/api/v2/...` parallel)
- Additive changes (new fields, new endpoints) — allowed without version bump
- Track changes in `## Changelog` section below

## 6. Changelog

- **2026-05-26 v1.0** — initial contract for MVP
- **2026-05-26 v1.1** — split auth into two cookies (`tkr_session` for Portal, `tkr_admin_session` for Backoffice) so both SPAs can be logged in simultaneously as different identities. Added `/api/admin/auth/{login,logout,me,change-password}`. `/api/admin/*` now requires the admin cookie (portal cookie no longer accepted).
