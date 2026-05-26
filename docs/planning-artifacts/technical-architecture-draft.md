# Technical Architecture Draft — TiktokRerun

**สถานะ:** Draft / pre-Product Brief
**วันที่:** 2026-05-23
**Scope:** เอาเป็น input ให้ `bmad-create-architecture` ต่อ ไม่ใช่ final design

---

## 1. Product Vision (one-liner)

**Web-based control plane สำหรับจัดการ TikTok Live Commerce Operations บน device fleet (Android phones) — broadcast video + ปักตะกร้าสินค้า ควบคุมจากเว็บ**

Target customer: solo TikTok Shop seller / multi-account reseller ที่ run TikTok Shop หลายบัญชี (3-15 accounts โดยทั่วไป)

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────┐
│                  Web Dashboard                        │
│  - Login (multi-tenant)                               │
│  - Device fleet view (status: online/offline/live)   │
│  - Video library (upload, organize)                   │
│  - Assignment: video → device                         │
│  - Live control: start/stop/switch video              │
│  - Product pinning command                            │
│  - Real-time monitoring (viewer count, comments)     │
│  - Scheduling                                         │
└────────────────────┬─────────────────────────────────┘
                     │ HTTPS REST + WebSocket
                     ▼
┌──────────────────────────────────────────────────────┐
│              Backend Services                         │
│  ┌──────────────────────────────────────────────┐    │
│  │ API Gateway (auth, routing)                  │    │
│  └──────────────────────────────────────────────┘    │
│  ┌──────────────┐ ┌────────────────┐ ┌──────────┐   │
│  │ Device       │ │ Command Queue   │ │ Video    │   │
│  │ Registry     │ │ (per device)    │ │ Storage  │   │
│  │ (QR pairing) │ │                 │ │ (S3+CDN) │   │
│  └──────────────┘ └────────────────┘ └──────────┘   │
│  ┌──────────────────────────────────────────────┐    │
│  │ WebSocket Gateway (persistent device conns)  │    │
│  └──────────────────────────────────────────────┘    │
│  ┌──────────────┐ ┌────────────────┐                │
│  │ Postgres     │ │ Redis (state,  │                │
│  │ (users,      │ │ queues, pubsub)│                │
│  │ devices, etc)│ │                │                │
│  └──────────────┘ └────────────────┘                │
└────────────────────┬─────────────────────────────────┘
                     │ WSS (persistent)
                     ▼
   ┌─────────────────┼─────────────────┐
   ▼                 ▼                 ▼
[Phone 1]        [Phone 2]   ...   [Phone N]
┌────────────────────────────────────────┐
│ Each phone runs:                       │
│                                        │
│  ┌─────────────────────────────────┐  │
│  │ TikTok Native App (logged in)   │  │
│  │ - One seller account             │  │
│  │ - Starts LIVE (screen-share mode)│  │
│  │ - Manages TikTok Shop UI         │  │
│  └─────────────────────────────────┘  │
│                ▲                       │
│                │ controlled via        │
│                │ Accessibility Service │
│                ▼                       │
│  ┌─────────────────────────────────┐  │
│  │ Our Companion App (Kotlin)      │  │
│  │ - QR scanner (pair to backend)  │  │
│  │ - WebSocket client (commands)   │  │
│  │ - Video player (ExoPlayer)      │  │
│  │   plays fullscreen → TikTok     │  │
│  │   screen-share captures it      │  │
│  │ - Accessibility Service:        │  │
│  │   • start/stop TikTok Live      │  │
│  │   • tap pin product button      │  │
│  │   • switch between products     │  │
│  │ - System Alert Window (overlay) │  │
│  │ - Foreground service (keep-alive)│  │
│  │ - Heartbeat / status reporting  │  │
│  └─────────────────────────────────┘  │
└────────────────────────────────────────┘
```

---

## 3. Core Flows

### Flow A: Device Pairing
1. Web dashboard → "Add new device" → generates pairing token + QR
2. Phone opens companion app → "Pair" → scans QR
3. App connects to backend with token → registers device
4. Server creates persistent WebSocket session
5. Dashboard shows device online

### Flow B: Start Live with Video
1. User selects: device + video file → "Start Live"
2. Backend pushes command via WebSocket to device
3. App downloads video (or uses cached file)
4. App invokes Accessibility Service: open TikTok → tap "Go Live" → select "Screen Share" → confirm
5. App switches to foreground, plays video fullscreen via ExoPlayer with loop=true
6. TikTok captures the screen (including our video) and broadcasts as Live
7. App reports status to backend (live confirmed, viewer count, etc.)

### Flow C: Pin Product
1. User selects: device + product → "Pin"
2. Backend pushes pin command
3. App uses Accessibility Service to invoke TikTok pin UI
4. **Open question:** does TikTok screen-share mode have floating pin widget, or do we need overlay trick?
5. Confirmation back to backend

### Flow D: Stop Live
1. User → "Stop"
2. App: Accessibility → tap end live → return to standby

---

## 3.5 Smart Overlay Broadcast Mode (Default for MVP)

**Replaces plain screen-share** ที่ใช้ใน POC initial Architecture เปลี่ยนจาก "Companion App สลับ foreground เป็น video player" เป็น "Companion App วาด video เป็น overlay บน TikTok app"

### Concept

```
Layer stack on phone screen:
┌─────────────────────────────────────────┐
│  [Layer 3] Banner Layer                 │
│    - Static text banner                 │
│    - Countdown timer                    │
│    - Price/stock tag                    │
│    - Brand watermark                    │
│    - Dynamic content (operator-driven)  │
├─────────────────────────────────────────┤
│  [Layer 2] Video Layer                  │
│    - ExoPlayer / TextureView            │
│    - Full-screen broadcast video        │
│    - Loop                               │
├─────────────────────────────────────────┤
│  Both above rendered in single Overlay  │
│  Window (TYPE_APPLICATION_OVERLAY)      │
│  FLAG_NOT_TOUCHABLE for passthrough     │
├─────────────────────────────────────────┤
│  [Layer 1] TikTok App                   │
│    - In Live screen-share mode          │
│    - MediaProjection captures all above │
│    - Receives Accessibility taps        │
│      (for pin product / live controls)  │
└─────────────────────────────────────────┘

What viewer sees in broadcast = Layer 2 + Layer 3 composed
(TikTok app UI hidden underneath, never visible)
```

### Flow change vs plain screen-share

| Step | Plain Screen-Share (POC initial) | **Smart Overlay (MVP target)** |
|---|---|---|
| 1 | Accessibility opens TikTok → Go Live → Screen Share | (same) |
| 2 | Companion App switches to **foreground** | Companion App stays **background**, draws overlay |
| 3 | Video player UI fills phone screen | Overlay window fills screen (z-order top) |
| 4 | TikTok screen-share captures video player UI | TikTok screen-share captures overlay layers |
| 5 | **Pin product:** switch foreground to TikTok app, tap pin, switch back | **Pin product:** Accessibility taps TikTok UI underneath overlay, **no foreground switch** |
| 6 | Viewer sees flicker / UI flash during pin | **Viewer sees nothing — overlay continues covering all** |
| 7 | Banner not possible (would have to be in video file) | **Banner rendered as Layer 3 — dynamic, server-driven** |

### Key Android APIs used
- `SYSTEM_ALERT_WINDOW` permission (Settings → Display over other apps)
- `TYPE_APPLICATION_OVERLAY` window type (Android 8+)
- `LayoutParams.FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` for touch passthrough
- `TextureView` / `SurfaceView` for video rendering (hardware accelerated)
- `Canvas` / `View` for banner layer composition
- `AccessibilityService` for TikTok UI control (pin/unpin/switch)
- `ForegroundService` for keep-alive

### Banner rendering architecture

```kotlin
class OverlayWindow(context: Context) {
    private val rootView = FrameLayout(context)
    
    // Layer 1: Video
    private val videoView = TextureView(context).also {
        rootView.addView(it, MATCH_PARENT, MATCH_PARENT)
    }
    private val player = ExoPlayer.Builder(context).build()
    
    // Layer 2: Banner composition
    private val bannerLayer = BannerCompositeView(context).also {
        rootView.addView(it, MATCH_PARENT, MATCH_PARENT)
    }
    
    fun show() {
        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(rootView, params)
    }
    
    fun playVideo(file: File) {
        player.setMediaItem(MediaItem.fromUri(file.toUri()))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.play()
    }
    
    fun updateBanner(slot: BannerSlot, config: BannerConfig) {
        bannerLayer.updateSlot(slot, config)
        // Re-render via invalidate() — picks up next frame
    }
}
```

### Verification gates (POC extension, 1-2 weeks)

ต้องผ่านทั้ง 3 ก่อน commit MVP build:

1. **G1: Capture compatibility** — TikTok screen-share จับ SAW overlay จริงในทุก Android version target (10, 11, 12, 13, 14)
   - Test: เปิด live → ดูจากเครื่องอื่น → ภาพต้องเป็น overlay content, ไม่ใช่ TikTok UI
2. **G2: No block detection** — TikTok app ไม่ refuse live หรือ ban เมื่อ detect SAW overlay running
   - Test: รัน live 4 ชั่วโมง × 3 รอบ; check account status, live capability
3. **G3: Touch passthrough** — Accessibility Service tap TikTok UI ใต้ overlay ได้ (สำหรับ pin product)
   - Test: pin product 50 ครั้ง consecutive; success rate ≥ 95%

### Fallback ถ้า verification ไม่ผ่าน

- **G1 fail:** บาง Android version filter overlay → ใช้ "overlay only during pin/banner change" pattern (ปกติ plain screen-share, overlay swap-in เฉพาะตอนเปลี่ยน UI)
- **G2 fail:** TikTok block → ใช้ pre-rooted hardware (Pro tier) แทน, downgrade BYOD model
- **G3 fail:** Accessibility ไม่ผ่าน → web automation Playwright สำหรับ pin (FR-12 fallback path b)

---

## 4. Hard Technical Questions ที่ต้อง POC ก่อน

### Q4.1: ปักตะกร้าระหว่าง screen-share ทำได้ยังไงโดยไม่ให้ผู้ชมเห็น TikTok UI?
**Options:**
- (a) ตรวจสอบว่า TikTok screen-share มี floating pin widget แยก (ต้องทดสอบ TikTok app ล่าสุด)
- (b) ใช้ TikTok LIVE Center web → pin จาก backend ผ่าน browser automation (Playwright)
- (c) Dual-phone setup
- (d) Video overlay technique (system alert window แสดง video ทับ TikTok ขณะ pin)

**Recommendation POC:** ทดสอบ (a) + เตรียม fallback (b)

### Q4.2: Audio routing ตอน screen-share
- TikTok screen-share จะใช้ mic หรือ system audio?
- ถ้าใช้ mic → ต้องปิด mic + route video audio → mic input (อาจต้องใช้ Sound about app หรือ root)
- ถ้าใช้ system audio → simple, ExoPlayer เล่นเสียงปกติ

### Q4.3: Foreground service + battery + heat
- 100 phones run 8-24hr ต่อวัน → battery management critical
- ต้อง wake lock + foreground service notification
- ต้องระบายความร้อน — สำคัญสำหรับ deployment 100 เครื่อง

### Q4.4: WebSocket reliability ที่ scale
- 100 persistent connections → standard, ไม่หนัก
- แต่ต้อง handle reconnection, missed commands, command idempotency
- Backend: Go (goroutines) หรือ Node.js (cluster mode) ดี

### Q4.5: TikTok app version compatibility
- Accessibility Service พึ่ง UI structure ของ TikTok → break ทุก major update
- ต้องมี: TikTok version detection + selector update mechanism
- อาจต้อง maintain selector library (เหมือน automation framework Selenium)

### Q4.6: Detection / ban rate ที่ scale
- 100 phones same office IP = red flag
- ต้องคิด: SIM 4G/5G + IP rotation, geo distribution

---

## 5. Tech Stack Proposal

| Layer | Choice | Rationale |
|---|---|---|
| **Mobile (companion app)** | Kotlin + ExoPlayer + AccessibilityService API | native, performance, latest Android API |
| **Backend** | **Go (Fiber/Echo) + WebSocket** | concurrent connections, low memory |
| **Realtime** | gorilla/websocket หรือ centrifugo | proven |
| **Database** | PostgreSQL + Prisma/sqlc | standard relational |
| **Cache/Queue** | Redis | command queue, session state |
| **Storage** | S3-compatible (Wasabi/Cloudflare R2) + CDN | cheap video storage |
| **Frontend** | Next.js + React + tRPC + Tailwind | full-stack TS, fast dev |
| **Auth** | NextAuth / Clerk / custom JWT | multi-tenant |
| **Infra** | Docker + Hetzner / Railway initially | cheap, scale later to k8s |
| **Monitoring** | Grafana + Loki + Prometheus | self-hostable |

---

## 6. POC Scope (Phase 1, ~2 เดือน)

**Hard constraint:** ทดสอบกับ **5-10 phones** เท่านั้น ก่อน scale

### MVP features
- [x] Device pairing via QR
- [x] WebSocket connection (device ↔ backend)
- [x] Video upload + storage
- [x] **Smart Overlay broadcast mode** (replaces plain screen-share)
- [x] **Banner & Overlay Composition** (Static Tier 1 + Dynamic Tier 2)
  - Static banner: text, color, position, configured per video
  - Dynamic banner: real-time update from web (latency < 3s)
  - Countdown banner with deadline + auto-action
  - Price/stock banner (data-bound to Pinned Product)
- [x] Assign video → device
- [x] Start/stop live (via Accessibility automation)
- [x] Video plays fullscreen + TikTok screen-share captures
- [x] Status reporting (live/offline/error)
- [x] Basic web dashboard

### Out of scope (Phase 2)
- Product pinning (ต้อง POC Q4.1 แยกก่อน)
- Multi-tenant / billing
- Comment moderation
- Analytics
- Scheduling
- Hybrid (human takeover)

### Success criteria
- ✅ 5 phones broadcast loop video concurrently > 6 ชั่วโมง โดยไม่ crash
- ✅ Web operator switch video on demand < 5s latency
- ✅ Ban rate < 50% ใน 24hr test window
- ✅ Total ops setup < 30 นาที per phone (excluding TikTok login)

---

## 7. Critical Open Decisions

1. **Stream key path:** ปล่อย Q1=A (screen-share, ไม่ใช้ stream key) — confirmed
2. **Pin product mechanism:** Q4.1 ต้อง POC แยก
3. **Account ownership:** A1 positioning, A2 customer ก็ใช้ได้
4. **POC scale:** 10 phones
5. **Backend language:** Go vs Node — decide ใน architecture phase
6. **Hosting region:** ไทย (Bangkok) สำหรับ latency กับ phone fleet ในไทย
7. **Pricing model:** subscription per device, per concurrent live, or hybrid

---

## 8. Risk Register

| Risk | Severity | Mitigation |
|---|---|---|
| TikTok detects loop pattern → mass ban | สูง | hybrid mode + content variation + IP rotation |
| TikTok updates UI → Accessibility selectors break | สูง | selector versioning + auto-update mechanism |
| Phone overheating @ 100 units | กลาง | cooling rack + monitoring |
| Audio not routed correctly in screen-share | กลาง | POC test early; fallback = mute video, broadcast เงียบ |
| Legal challenge from TikTok | กลาง-สูง | A1 positioning, terms of service compliance work |
| MOD APK competitors offering "free" alternatives | กลาง | differentiate via reliability, support, web UX |
| Customer concentration risk (1 power user = 30% revenue) | กลาง | broader inbound + community-led acquisition |

---

## 9. Next Step

→ **Product Brief** (`bmad-product-brief`) เพื่อนิยาม:
- Target customer persona (Solo TikTok Shop seller with multiple accounts — per persona pivot 2026-05-26)
- Value proposition vs TikMatrix + DIY OBS approach
- Pricing tier / MVP feature gating
- Go-to-market (TH first → SEA)
- Success metrics (revenue, retention, NPS targets)

→ ตามด้วย **Architecture** (`bmad-create-architecture`) เพื่อ commit:
- Final tech stack
- Database schema
- API contracts (REST + WebSocket events)
- Deployment topology
- Security model

---

## 11. POC Smoke Test — 2026-05-23

### สิ่งที่ test (manual บนเครื่องจริง)
- Account: ≥1,000 followers, ≥18, Gaming category enabled, **ไม่ใช่ Shop seller** (จะใช้ flow affiliate pin ของร้านอื่น)
- TikTok app บน Android → + → LIVE → **Mobile Gaming** → screen-share

### Findings

| Question | Result | Implication |
|---|---|---|
| Screen-share available บน mobile? | ✅ มี แต่ **gated ไว้ใต้ Mobile Gaming tab** เท่านั้น | Live category = **Gaming** (ไม่ใช่ commerce) |
| Pin product button มีใน screen-share mode? | ✅ **เห็น** | mechanism ปักได้ |
| Pin UI โผล่บน broadcast (ผู้ชมเห็น)? | ❌ **โผล่** (verified by 2nd account) | **Q4.1 option (a) ตาย** |

### Architectural pivot — decisions committed

**D1. Broadcast mechanism = Mobile Gaming screen-share (ยังเก็บ)**
- ยอมรับ category = Gaming แม้ product positioning เป็น commerce ops
- จะโดน "Live Gaming Visibility Restricted" risk (TikTok 2026 detection) → ต้อง mitigate ด้วย content variation + human signal

**D2. Pin product mechanism = creator pin เอง บนเครื่องเดียวกัน (mobile-only, no browser)**
- Product เป็น mobile-first ไม่เพิ่ม browser dependency
- POC v0: ปล่อย **creator pin ตามปกติ** ผ่าน TikTok UI ของตัวเอง — UI leak บน broadcast = **accept** เพราะ audience คาดอยู่แล้วว่า creator navigate UI ระหว่างไลฟ์
- Our app v0 job เดียว: เล่นวิดีโอ fullscreen loop ให้ screen-share capture
- Pin automation (Accessibility Service) = เลื่อนไป v1+ หลังจาก v0 ยืนยัน core broadcast loop ทำงาน
- ❌ ทิ้ง: split-brain web pin controller, Playwright/Streamer Desktop integration, backend orchestration ใน v0

**D3. Persona pivot — affiliate creator ไม่ใช่ self-seller**
- Original positioning ใน [market-research:14-15](#) คือ "TikTok Shop seller รายเล็ก-กลาง" — แต่ที่จริง user flow คือ **creator ปักตะกร้าสินค้าของร้านอื่น (affiliate)**
- กระทบ pricing model, GTM, success metrics → ต้อง revisit ใน Product Brief

### Open questions ที่ยัง pending

| # | Question | Blocking |
|---|---|---|
| Q11.1 | ~~TikTok LIVE Center pin ใช้กับ Gaming live ได้ไหม~~ | ❌ obsolete — D2 ตัด browser path ออก |
| Q11.2 | Audio routing ตอน screen-share เป็นยังไง? | ไม่ block POC v0 |
| Q11.3 | "Live Gaming Visibility Restricted" trigger ใน loop video เท่าไหร่/เร็วแค่ไหน? | จะรู้ตอน duration test |
| Q11.4 | ~~ExoPlayer + screen-share จะ render fullscreen ได้ไหม~~ | ✅ verified 2026-05-24 — เล่นได้ broadcast ใช้งานได้ |
| **Q11.5** | **Mobile Gaming live = landscape encoder output forced — content portrait ไม่มีทาง fullscreen สำหรับผู้ชม** | ✅ confirmed 2026-05-24 (ทดสอบจริง) — เป็น **architectural blocker** สำหรับ portrait content |

### Q11.5 — Mobile Gaming aspect ratio finding (2026-05-24)

**Setup test:** วิดีโอ 9:16 portrait, phone portrait, broadcast ปกติ
**ผู้ชมเห็น:** วิดีโอตรงกลาง มีพื้นที่ดำรอบทุกด้าน (ไม่ fullscreen)
**Root cause:** TikTok Mobile Gaming live ปี 2026 บังคับ encoder output เป็น **landscape 16:9** (เพราะ category gaming = แนวนอน) — portrait content + landscape encoder = ดำรอบเสมอ, แก้ที่ app layer ไม่ได้

**3 paths ที่เปิดอยู่ตอนนี้:**

| Path | What | Trade-off |
|---|---|---|
| **A. Landscape content shift** | บังคับใช้วิดีโอ 16:9 + lock PlayerActivity เป็น landscape | ขัด TikTok norm ที่ content เป็น portrait — เปลี่ยน creative direction |
| **B. Accept letterbox v0** | บอก creator ว่าผู้ชมจะเห็น pillarbox — เป็น proof of concept ก่อน | UX แย่ ไม่ scale เป็น product จริง |
| **C. Pivot broadcast path → TikTok LIVE Studio (RTMP)** | desktop broadcaster ผ่าน Mac, ยังคง mobile-first สำหรับ pin product | ขัด D2 ที่บอก "mobile-only" แต่ Mac เป็น tooling ไม่ใช่ user surface |

**Decision ที่ต้อง commit:** ยังไม่ได้ตัดสิน รอ Pond เลือก path

### POC v0 Scope (mobile-only, single-phone)

**Goal:** ทดสอบว่า "creator เปิด Mobile Gaming live + screen-share → switch ไป our app → app เล่นวิดีโอ loop fullscreen → audience เห็นวิดีโอ" — ทำงานได้ stable นานพอใช้งานจริง (≥30 นาที)

**In scope v0:**
- Kotlin Android app เดียว
- ExoPlayer เล่นวิดีโอ fullscreen + loop
- เลือกวิดีโอจาก local storage (ยังไม่ต้อง cloud sync)
- UI: ปุ่ม "เริ่ม" / "หยุด" + intent ไปเปิด TikTok
- Foreground service + wake lock เพื่อกันโดน OS kill

**Out of scope v0:**
- Pin automation (creator pin เองทุก trigger)
- Backend / WebSocket / control plane
- Web dashboard
- Multi-device fleet
- Account management
- Audio routing manipulation (ใช้ default — verify ใน Q11.2 ทีหลัง)

**Success criteria v0:**
- ✅ App เล่นวิดีโอ fullscreen ระหว่าง TikTok screen-share active โดยไม่ถูก interrupt
- ✅ ไลฟ์ดำเนินต่อได้ ≥30 นาทีโดย app ไม่ crash / TikTok ไม่ kick
- ✅ Audience เห็นวิดีโอ (ไม่ใช่ blank/static/our-app UI)
- ✅ Audience ได้ยินเสียงวิดีโอ (ถ้า audio route ไม่ได้ — flag เป็น Q11.2 follow-up)

### Next step

1. **Verify Q11.4 ด้วย manual test (15 นาที, ไม่ต้องเขียน code)** — เปิด video player ตัวอื่นที่มีอยู่แล้ว (VLC / MX Player / built-in player) → start TikTok Mobile Gaming live + screen-share → switch ไป player → player เล่นวิดีโอ fullscreen ได้ไหมโดย screen-share จับได้ + ไลฟ์ไม่ถูก kick
2. ถ้า manual test ผ่าน → scaffold Kotlin app (รอ install Android Studio ก่อน)
3. ถ้าไม่ผ่าน → investigate restriction ก่อน build อะไร
