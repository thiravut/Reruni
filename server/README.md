# TiktokRerun — Server (v2 Layer 1)

Web dashboard + Go backend ที่ควบคุม mobile fleet ผ่าน WebSocket commands

> **Layer 1 scope:** อัพโหลด video → web ส่งคำสั่งให้ device download + เล่น TikTok Live ยังต้อง start manual บน phone ตอน Layer 2 จะมี Accessibility Service automate ส่วนนั้น

---

## Quick Start

```bash
# build + run (Mac dev)
cd server
go mod tidy
go run .
# server listens on http://localhost:8080
```

เปิด browser ไปที่ http://localhost:8080 → จะเห็น dashboard

### Custom flags

```bash
go run . -addr :9000 -uploads /path/to/uploads -db /path/to/rerun.db
```

---

## ขั้นตอนใช้งาน (with mobile app)

1. **Server** — `go run .` บน Mac (เครื่องเดียวกับที่ Android phone อยู่ใน Wi-Fi เดียวกัน) จด IP ของ Mac เช่น `192.168.1.100`
2. **Browser** — เปิด `http://192.168.1.100:8080` (หรือ localhost ถ้าใช้ Mac เครื่องเดียวกัน)
3. กด **"+ สร้าง Pair Token"** → token จะปรากฏ
4. **Mobile app** → tap "⚙ ตั้งค่า Server" → ใส่ URL + paste token + ตั้งชื่อ device → กด "บันทึก + เชื่อมต่อ"
5. Device จะปรากฏใน dashboard เป็น **ONLINE**
6. **อัพโหลดวิดีโอ** ใน dashboard → เลือก device + วิดีโอ → กด **Play ▶**
7. Phone download → เล่น fullscreen
8. ใน phone: start TikTok Live → Mobile Gaming → Screen Share (manual ตอนนี้)

---

## โครงสร้าง

```
server/
├── main.go        # entry point + route table
├── db.go          # SQLite schema + models
├── handlers.go    # REST handlers (upload, list, pair, play)
├── ws.go          # WebSocket gateway + connection registry
├── go.mod
├── uploads/       # video files (gitignored)
├── rerun.db       # SQLite (gitignored)
└── web/
    ├── index.html
    ├── style.css
    └── app.js
```

---

## API

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/videos` | multipart `video=<file>` | `{id, name, filename, url, ...}` |
| GET | `/api/videos` | — | `[{...}]` |
| DELETE | `/api/videos/{id}` | — | 204 |
| GET | `/api/devices` | — | `[{id, name, online, ...}]` |
| POST | `/api/pair` | — | `{token}` (single-use) |
| POST | `/api/devices/{id}/play` | `{video_id}` | `{status: "sent"}` |
| WS | `/ws/device?token=X&name=Y&device_id=Z` | — | bidirectional JSON |

### WebSocket protocol (server → device)

```json
// after handshake
{ "type": "welcome", "device_id": "abc12345" }

// when web triggers play
{ "type": "play", "video_id": 7, "name": "demo.mp4", "url": "/uploads/20260524-014505-aabbccdd.mp4" }
```

Device prepends server origin to relative URLs (`/uploads/...`) when downloading.

---

## Deploy (later)

ตอนนี้ run บน Mac (localhost) สำหรับ POC พอ ตอน Production:

- Cross-compile: `GOOS=linux GOARCH=amd64 go build -o tiktokrerun-server`
- scp binary + `web/` ไป AlmaLinux server
- รัน behind nginx/Apache reverse proxy (CWP)
- หรือ direct bind พอร์ตสูง (8443) + Let's Encrypt cert
- ต้อง open ports ใน firewalld + adjust SELinux booleans

---

## Roadmap

- **Layer 1 (current)** — web ↔ backend ↔ device download + play
- **Layer 2** — Accessibility Service บน device auto-start TikTok Live + Screen Share
- **Layer 3** — Multi-tenant + auth (เปลี่ยน pair token เป็น account-based)
- **Layer 4** — Scheduled playback, per-device playlist, analytics
