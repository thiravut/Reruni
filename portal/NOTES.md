# Portal — Build Notes & Coordination Log

Owner: Portal agent  ·  Updated: 2026-05-26

## Status snapshot

- Scaffolded Vite + React 19 + TypeScript + Tailwind 4 + React Router 7.
- All routes per `agent-task-portal.md` §4 implemented.
- Auth, real-time WS, banner editor, devices/videos/lives CRUD wired through
  typed API client in `src/api/`.
- Will work against the live backend once `/api/auth/me`, `/api/devices`,
  `/api/videos`, `/api/lives/*`, `/api/banners`, `/api/pair/token`,
  `/api/pair/qr` and `/ws/portal` respond per `api-contract.md`.

## API gaps / clarifications surfaced to Pond

These do **not** block dev — they're observations for the API agent. Per the
brief I did not edit `api-contract.md`.

1. **WebSocket auth at dev-time over Vite proxy.** The portal assumes
   `/ws/portal` is reachable via the same origin (proxied to
   `ws://localhost:8080`). If backend rejects WS without a session cookie set
   on `localhost:5173`, we need either:
   - cookie set with `Domain=localhost` (works because proxy preserves
     `Host`), **or**
   - documented in `tech-spec.md` that the backend allows WS upgrade for
     proxied requests.

2. **Pair-QR image URL shape.** `POST /api/pair/token` returns
   `qr_url: "/api/pair/qr?token=..."`. The portal currently embeds this as
   `<img src={...} />`. Confirm the response is served as `image/png` from
   that same path with no Content-Disposition: attachment.

3. **`current_pinned_sku` and `current_video_id` on `GET /api/devices`.**
   Contract says these are optional. The UI shows the pinned SKU on the
   device list; if the backend omits them when device is not live, the
   column shows `—` which is fine.

4. **`GET /api/lives/active` no `total` field.** I treat
   `res.items.length` as the count, which matches the contract example.

5. **Switch video / pin product / banner mid-live require the device to be in
   `live` state.** The UI does not pre-validate; backend should return a
   meaningful error code (e.g., `DEVICE_NOT_LIVE`) so we can surface it.
   Suggestion: add to error catalog if not already implied by `LIVE_NOT_FOUND`.

6. **Banner deadline timezone.** Portal sends ISO UTC in `deadline`. Confirm
   server stores UTC and broadcasts as UTC; mobile companion will render in
   local.

7. **Upload progress.** `fetch()` cannot report upload progress. For the v1
   `POST /api/videos` (500 MB cap), we display an indeterminate-style
   progress bar that animates to ~95% then jumps to 100% on success. If
   real progress matters we'd need to switch to `XMLHttpRequest` — flag for
   later UX polish.

## Acceptance checklist progress

- [x] All routes render (`/login`, `/signup`, `/dashboard`, `/devices`,
  `/videos`, `/videos/:id/banners`, `/live`, `/live/active`, `/history`,
  404)
- [x] Login / signup / logout flows wired
- [x] Forms validate client-side per tech-spec §3
- [x] WebSocket connection + exponential backoff reconnect + pong response
- [x] Mobile responsive (top-bar burger menu, single-col grids at <md)
- [x] No `any` in component code (one explicit `any` in `client.ts` for the
  parsed-JSON shape — safe-isolated helper)

## Open questions for Pond (from agent-task §11)

1. Brand color preference? → defaulted to Tailwind `blue-600` aliased as
   `brand-600`. Easy to swap in `index.css`.
2. Logo / app icon? → currently a "T" tile placeholder.
3. `/history` MVP scope? → built full list + filter + pagination.
4. Sign-up invite code? → assumed open self-serve per contract §2.1.
5. ToS text? → links are placeholder anchors that do nothing.
6. Post-login redirect? → defaults to `/dashboard`; the `ProtectedRoute`
   captures `location.from` so deep links survive a login round-trip.

## Suggested next steps

- Replace placeholder logo with real asset once Pond provides.
- Add `XMLHttpRequest`-based upload for real progress percentage.
- Add `device_status_changed` optimistic patch (currently we refetch the
  device list whenever any device event arrives — fine for fleets up to ~50).
- Consider a thin Vitest smoke test for form validation + AuthContext.
- Once the API agent ships the `/ws/portal` push messages, hook
  `banner_updated` to refresh inline banner list on `/live/active`.
