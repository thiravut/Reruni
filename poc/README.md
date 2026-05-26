# Smart Overlay POC — Standalone Server

Fully self-contained sandbox for Smart Overlay verification gates G1–G3
(see `docs/planning-artifacts/technical-architecture-draft.md` §3.5).
No dependency on `../server` — own DB, own uploads dir, own WS hub.

```
poc/
├── main.go         # router + startup
├── db.go           # schema: videos, devices, pair_tokens (no users, no FK)
├── handlers.go     # /api/legacy/* HTTP API
├── ws.go           # /ws/device hub (query-string auth)
├── go.mod
├── web/            # dashboard (vanilla HTML/JS/CSS)
└── README.md
```

## Run

```bash
cd poc
go run .                                  # :8090, ./poc.db, ./uploads
go run . -addr :9090                      # custom port
go run . -addr :8090 -db /tmp/poc.db -uploads /tmp/poc-uploads
```

Open <http://localhost:8090/>.

## Mobile pairing

Mobile devices pair with **this** server, not with the production API in
`../server`. The QR's `url` field carries this server's host+port, and
the mobile WS client connects to `/ws/device?token=...&device_id=...`
on the same host. Make sure the host you access the dashboard from is
reachable from the phone (use the laptop's LAN IP, not `localhost`).

## What it intentionally does NOT have

- No auth, no sessions, no users — anyone on the LAN can pair a device
- No portal SPA, no billing, no admin — that all lives in `../server`
- No history, no products table, no banners — only what's needed for
  the play / start-live / overlay verification path

When the verification gates pass and Smart Overlay graduates to MVP, the
relevant pieces fold into the production server's new envelope WS protocol.

## API summary

| Endpoint                                       | Purpose                              |
|------------------------------------------------|--------------------------------------|
| `POST /api/legacy/pair`                        | Mint pair token + QR                 |
| `GET  /api/legacy/videos`                      | List videos                         |
| `POST /api/legacy/videos`                      | Upload video (multipart `video`)     |
| `DELETE /api/legacy/videos/{id}`               | Delete video                         |
| `GET  /api/legacy/devices`                     | List devices                         |
| `POST /api/legacy/devices/{id}/play`           | Send play cmd (supports `use_overlay`) |
| `POST /api/legacy/devices/{id}/start-live`     | Send start_live cmd                  |
| `GET  /uploads/{filename}`                     | Stream uploaded video                |
| `WS   /ws/device?token=…&device_id=…&name=…`   | Device companion socket              |
