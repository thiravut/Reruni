# TiktokRerun — System Overview

**สำหรับ:** Executive Presentation (pre-PRD)
**วันที่:** 2026-05-24
**สถานะ:** Draft — รอ POC findings (section 9) เพื่อ finalize
**Owner:** [ทีม TiktokRerun]

---

## 1. Executive Summary

TiktokRerun คือ **web-based control plane** ที่ช่วยให้ **solo seller / reseller ที่ run TikTok Shop หลายบัญชี** จัดการ live commerce ของทุกบัญชีจากเว็บเดียว — broadcast วิดีโอ + ปักตะกร้าสินค้า + **เพิ่ม banner/promo/countdown ทับวิดีโอแบบ real-time** โดยไม่ต้องจ้างทีม ไม่ต้องเดินไปแตะเครื่อง

**VCam Camera2 hijack** (own-built LSPosed module + LSPatch shim) — Companion App ฉีดวิดีโอ MP4 เข้า Camera2 preview pipeline → TikTok เห็น "frame" จาก camera ตรงๆ ไม่ใช่ feed จริง → pin product, switch product, เปลี่ยน promo banner composite real-time, **ไม่ต้อง root device** (R3 Lite BYOD), ลูกค้าใช้ Android phone ที่รองรับ LSPatch ได้

**ปัญหาที่แก้:** TikTok Shop seller ในไทยต้องการ live presence 24/7 เพื่อ algorithm boost + sales conversion แต่ live สดเองไม่ไหว และเครื่องมือปัจจุบัน (TikMatrix, PRISM, MOD APK) ขาด web UX, ขาด broadcast capability, หรือผิด ToS

**โอกาส:** Live commerce ในไทยโต 40%+ ปี 2025-2026, TikMatrix charge $29-$149/เดือนต่อ user มีฐานลูกค้าจริง, แต่ไม่มีใครครอบคลุม web + broadcast + Shop integration พร้อมกัน

**POC status:** ✅ Validated — broadcast, audio control, remote command, device pairing, start/stop live, pin/unpin product, video switching — ทำงานครบ พร้อมเข้า MVP

**Ask:** approval ในการเข้า MVP phase (8 สัปดาห์, Pond solo + Claude, infra ~5-10K บาท/เดือน)

---

## 2. The Opportunity

### Market context
- **TikTok Shop Thailand GMV** เติบโตต่อเนื่อง, seller จำนวนมากย้ายจาก Shopee/Lazada เป็น TikTok-first
- **Live commerce penetration** สูงกว่า e-commerce ปกติ (conversion rate 5-10x)
- **ครีเอเตอร์ + seller** ต้องการ presence 24/7 แต่ live สดต้องใช้คน → bottleneck สำคัญ
- **TikTok Algorithm 2026** ให้ priority กับ live ที่ active → ไม่ live = ไม่มี distribution

### Competitive gap

| Player | สิ่งที่ทำ | สิ่งที่ขาด |
|---|---|---|
| **TikMatrix** (desktop, $29-149/mo, ตลาด global) | engagement farm, multi-account mgmt | ไม่มี broadcast, ไม่มี web, ไม่มี Shop |
| **PRISM Live Studio** (mobile, free) | live streaming + playlist | ไม่ scale, ไม่มี remote control, ไม่มี Shop |
| **OBS + LIVE Studio** (desktop) | RTMP + stream key | desktop-only, 1 broadcast/เครื่อง, ต้องการ stream key |
| **MOD APK** | unlock features | malware risk, account ban, illegal |
| **TiktokRerun (เรา)** | web + broadcast + Shop + fleet | — |

### Why now
- TikTok Shop ในไทยเข้าสู่ scale phase → ลูกค้า ready
- POC ของเราพิสูจน์ technical feasibility แล้ว
- Direct competitor (TikMatrix) แสดงให้เห็นว่า market มีจริงและจ่ายเงินจริง
- ก่อน TikTok ออก policy ที่ tighter — first-mover advantage

---

## 3. What We're Building

### Product vision
> **"Operations cockpit for TikTok Live Commerce ที่หนึ่งคนคุมร้านได้ 100 ร้านพร้อมกัน"**

### Core value proposition
1. **Centralized control** — จัดการ 100 phones จาก web เดียว
2. **Broadcast automation** — เล่นวิดีโอที่ตัดต่อไว้ใน live โดยไม่ต้องมีคน on-cam
3. **Commerce integration** — ปักตะกร้า / สลับสินค้า remotely กลาง live
4. **Dynamic Composition** — เพิ่ม/เปลี่ยน banner, countdown, price tag ทับวิดีโอ real-time จาก web (1 วิดีโอ base = 100 campaigns ไม่ต้องตัดใหม่)
5. **Operational visibility** — สถานะทุก live, สลับ video on-demand

### Customer profile (primary)
- **Solo TikTok Shop seller** ที่มีหลายบัญชี (3-15 accounts) — full-time หรือ side-hustle
- **Multi-account reseller / drop-shipper** ที่ run TikTok Shop หลาย category — คนเดียว
- **Small business owner** ที่ใช้ TikTok เป็นช่องทางหลัก, ไม่มีงบจ้างทีม

→ **Not targeting:** agency, brand with internal team, enterprise — no plan to add these segments

### Not building (สำคัญสำหรับ scope discipline)
- ❌ Fake engagement / bot viewers / fake comments
- ❌ Account creation automation / verification bypass
- ❌ Detection evasion tooling (IP rotation as a feature)
- ❌ MOD APK / TikTok app modification

---

## 4. System Architecture

### 4.1 Logical View (3-tier)

```
┌────────────────────────────────────────────────────┐
│  TIER 1: CONTROL PLANE                             │
│  Web Dashboard (Browser)                           │
│  - User UI: fleet view, video lib, commands    │
│  - Admin UI: user mgmt, billing, analytics         │
└──────────────────┬─────────────────────────────────┘
                   │ HTTPS + WSS
                   ▼
┌────────────────────────────────────────────────────┐
│  TIER 2: ORCHESTRATION                             │
│  Backend Services (Cloud)                          │
│  - API Gateway + Auth                              │
│  - Device Registry & Pairing                       │
│  - Command Router (web → device)                   │
│  - Video CDN & metadata                            │
│  - Telemetry collector                             │
│  - Storage: Postgres + Redis + S3                  │
└──────────────────┬─────────────────────────────────┘
                   │ WSS (persistent)
                   ▼
┌────────────────────────────────────────────────────┐
│  TIER 3: DATA PLANE                                │
│  Device Fleet (Android phones at customer site)    │
│  - Companion app (Kotlin)                          │
│  - Video player (foreground full-screen)           │
│  - Accessibility automation                        │
│  - TikTok native app (per-account login)           │
└────────────────────────────────────────────────────┘
```

### 4.2 Physical Topology

```
┌─────────────┐       ┌──────────────────────┐
│  User   │──────▶│  Cloud (Bangkok)     │
│  Browser    │  WSS  │  - 1 LB              │
│  (anywhere) │       │  - 2-4 API nodes     │
└─────────────┘       │  - Postgres primary  │
                      │  - Redis cluster     │
                      │  - S3 + CDN          │
                      └──────────┬───────────┘
                                 │ WSS (mTLS)
                                 │
              ┌──────────────────┴──────────────────┐
              ▼                                      ▼
       Customer Site A                       Customer Site B
       (10-100 phones)                       (10-100 phones)
       - Cooling rack                        - Cooling rack
       - Wi-Fi / SIM 4G                      - Wi-Fi / SIM 4G
       - Each phone: 1 TikTok account
       - Each phone: companion app installed
```

### 4.3 Component Responsibilities

| Component | Tech | Owns |
|---|---|---|
| **Web Dashboard** | Next.js + tRPC + Tailwind | User UX, real-time fleet state |
| **API Gateway** | Go (Fiber/Echo) | Auth, routing, rate limit |
| **Device Registry** | Postgres | Device identity, pairing tokens |
| **Command Router** | Go + Redis pub/sub | Web → device command dispatch |
| **WebSocket Gateway** | Go (gorilla/websocket) | Persistent device connections |
| **Video Service** | Go + S3 + CDN | Upload, transcode, delivery to devices |
| **Telemetry** | Go + Postgres + Grafana | Device status, live metrics, audit log |
| **Companion App** | Kotlin + ExoPlayer + AccessibilityService | Device-side execution |

---

## 5. Core User Journeys

### Journey 1: Onboard a new phone (one-time, ~3 นาที)
```
User                    Backend                Phone
   │                           │                     │
   ├─ "Add device" ───────────▶│                     │
   │  (web dashboard)          │                     │
   │                           ├─ Generate QR + token│
   │◀──── Show QR ─────────────┤                     │
   │                           │                     │
   │                           │       Scan QR ─────▶│
   │                           │                     │
   │                           │◀──── Pair request ──┤
   │                           ├──── Register ──────▶│
   │                           │                     │
   │◀──── Device online ───────┤                     │
```

### Journey 2: Start live with looped video
```
User              Backend               Phone
   │                     │                    │
   ├─ Pick device ──────▶│                    │
   ├─ Pick video         │                    │
   ├─ "Start Live" ─────▶│                    │
   │                     ├─ Cmd: start_live ─▶│
   │                     │                    ├─ Acc.Svc: open TikTok
   │                     │                    ├─ Acc.Svc: tap "Go Live"
   │                     │                    ├─ Acc.Svc: select Screen Share
   │                     │                    ├─ Switch foreground → video player
   │                     │                    ├─ Play video (loop)
   │                     │◀── Status: LIVE ───┤
   │◀── Live confirmed ──┤                    │
```

### Journey 3: Pin a product mid-stream
```
User              Backend               Phone
   │                     │                    │
   ├─ "Pin product X" ──▶│                    │
   │                     ├─ Cmd: pin(X) ─────▶│
   │                     │                    ├─ [POC-validated method]
   │                     │                    │  (will be filled per Q1 POC)
   │                     │◀── Status: pinned ─┤
   │◀── Pin confirmed ───┤                    │
```

---

## 6. Capability Pillars

| Pillar | What it does | MVP / Phase 2 |
|---|---|---|
| **Fleet Management** | Onboard, pair, status, group devices | MVP |
| **Video Broadcasting** | Upload, assign, loop, switch on-demand | MVP |
| **VCam Camera2 hijack** | Own-built LSPosed module + LSPatch shim — no root, BYOD-friendly | MVP |
| **Live Lifecycle** | Start/stop/restart live remotely | MVP |
| **TikTok Shop Control** | Pin/unpin/switch products | MVP |
| **Banner Composition** | Static + Dynamic banner, countdown, price tag | MVP |
| **Scheduling** | Time-based start/stop, playlist rotation | Phase 2 |
| **Comment Monitoring** | Real-time feed, basic moderation | Phase 2 |
| **User account + billing** | Self-serve signup, payment, subscription tier | Phase 2 |
| **Banner Library/Editor** | Templates, drag-drop, animations | Phase 2 |
| **Pre-rooted Hardware (Pro tier)** | True VCAM via bundled rooted devices | Phase 2+ |
| **Analytics** | Aggregate viewer, GMV, retention | Phase 3 |
| **Interactive Overlays** | Comment ticker, order alert, gift react | Phase 3 |
| **Hybrid Live** | Human takeover (creator joins live realtime) | Phase 3 |

---

## 7. Tech Foundation

| Layer | Choice | Why |
|---|---|---|
| Mobile | Kotlin + ExoPlayer + AccessibilityService | Native control needed |
| Backend | Go (Fiber/Echo) | 100+ concurrent WebSocket connections, low mem |
| Realtime | WSS (gorilla/websocket) | Mature, simple |
| DB | PostgreSQL | Relational, reliable |
| Cache/Queue | Redis | Command queue + pub/sub |
| Storage | S3-compatible (Cloudflare R2) + CDN | Cheap video delivery |
| Frontend | Next.js + tRPC + Tailwind | Fast dev velocity |
| Infra (POC→MVP) | GCP Cloud Run + Cloud SQL + Cloudflare R2 | Hybrid: enterprise compute + free egress storage |
| Infra (V1+) | Same hybrid stack — scale up Cloud Run/SQL tiers | Same architecture, larger instances |
| Monitoring | Grafana + Loki + Prometheus | Self-hostable, no vendor lock |

---

## 8. Operational Model

### Where things live
- **Cloud (Bangkok region):** all backend, all storage
- **Customer site:** phone fleet + Wi-Fi/4G + cooling rack
- **User:** anywhere with browser

### Who operates what
- **Customer ops team:** physical phones (charging, cleaning, TikTok login)
- **Our SaaS:** all software, monitoring, updates
- **Hybrid responsibility:** TikTok account health (we provide guidelines, customer maintains accounts)

### Update mechanism
- **Web dashboard:** standard SaaS deploy (continuous)
- **Backend:** rolling deploy, zero downtime
- **Companion app:** auto-update via in-app mechanism (we control update cadence; critical for TikTok UI changes)

### Customer site logistics (per 100-phone deployment)
- Power: ~300W (3W per phone average)
- Network: 100 Mbps minimum (1 Mbps per live stream)
- Cooling: rack + fan setup, ambient < 28°C
- SIM: 100 SIM cards if not Wi-Fi (~500 บาท/เดือน × 100 = 50K)

---

## 9. POC Findings — All Core Capabilities Validated

### ✅ Core Broadcast Pipeline
- **Screen-share live** — Companion app เล่นวิดีโอเต็มจอ + TikTok Live screen-share จับและ broadcast ได้จริง
- **Audio routing** — เสียงจากวิดีโอออกใน broadcast พร้อม volume control ปรับ remote ได้ผ่าน dashboard
- **Loop playback** — วิดีโอ loop ต่อเนื่องไม่มี gap

### ✅ Remote Control
- **WebSocket command pipeline** — Web → backend → device latency < 1s
- **Device pairing via QR** — onboard phone ใหม่ใน < 3 นาที
- **Multi-device fanout** — สั่งหลายเครื่องพร้อมกันได้

### ✅ TikTok Automation
- **Start/stop live** — Autopilot (Accessibility Service) เปิด TikTok → Go Live → Device Camera
- **Pin/unpin product** — สลับสินค้าระหว่าง live ได้จาก web, ผู้ชมเห็น product anchor update real-time
- **Switch video on-demand** — operator เปลี่ยนวิดีโอที่กำลัง broadcast ได้โดยไม่ต้องปิด live

### ✅ VCam Camera2 Hijack — DECIDED + Validated (2026-05-31)

**Production broadcast path:** Own-built LSPosed module + LSPatch shim
- Companion App stages MP4 ใน VcamContentProvider (cross-process IPC)
- LSPosed module hijack Camera2 preview pipeline → render MP4 frames เป็น OES texture บน EGL surface
- TikTok เห็น "frame" จาก camera ตรงๆ (ไม่ใช่ feed กล้องจริง) → broadcast quality สูง, ไม่มี screen-capture artifact
- **No root required** — LSPatch (non-root Xposed shim) ทำให้ R3 Lite BYOD ใช้ได้บน phone ที่ไม่ root
- **Banner layer composite** บน video preview ก่อน hand-off ให้ TikTok

**Validation status:**
- ✅ Samsung A15 5G Android 16 — looping MP4 broadcast works end-to-end
- ✅ Build pipeline operational (Phase A-D ของ autopilot scripts shipped)
- ✅ Token-gated APK download (`/api/downloads/companion-apk`) wired

**Deprecated paths:**
- ~~Smart Overlay (SAW + MediaProjection screen-share)~~ — quality + UX inferior
- ~~Patched APK using competitor's VCAM~~ — external dependency + legal risk

### 📋 Customer-Managed Risk (by ToS)

#### Account Safety / Ban Risk
- **Policy:** ลูกค้ารับ risk เรื่อง TikTok account ban เอง ผ่าน Terms of Service
- **เหตุผล:** TikTok policy เปลี่ยนแปลงตลอด, ลูกค้าควบคุมเนื้อหา/account ของตัวเอง
- **Product responsibility:**
  - ToS acknowledgement flow ตอน signup
  - Documentation: best practices ลด ban risk
  - Aggregate telemetry เพื่อ improve product (ไม่ identify ลูกค้า)
- **Legal:** Customer ToS ต้องผ่าน legal review ก่อน GA

### POC Exit Status
| # | Item | สถานะ |
|---|---|---|
| 1 | Screen-share broadcast | ✅ |
| 2 | Audio routing + volume | ✅ |
| 3 | Remote WebSocket control | ✅ |
| 4 | Device pairing (QR) | ✅ |
| 5 | Start/stop live automation | ✅ |
| 6 | Pin/unpin product | ✅ |
| 7 | Switch video on-demand | ✅ |
| 8 | Multi-device concurrent control | ✅ |
| 9 | VCam Camera2 hijack (own-built LSPosed module + LSPatch) | ✅ Validated A15 5G Android 16 |
| 10 | Banner layer composite on Camera2 preview | ⏳ V1 GA cycle (in progress) |
| ~~Ban rate baseline~~ | ~~Customer-managed via ToS + design partner cycle~~ | Design partner cycle |

**Conclusion: POC + ~90% MVP shipped; remaining = Banner Tier 2 + design partner cycle + Stripe live mode**

### Open Risks for V1 GA
- TikTok app version changes → Accessibility selectors + VCam hook may break (mitigation: selector versioning + module versioning + 24-48hr rebuild SLA)
- Scale 10 → 100 phones (mitigation: load test during design partner cycle)
- Long-run stability 24/7 operation (mitigation: instrumentation + auto-recovery)
- Banner rendering performance on Camera2 preview (mitigation: hardware-accelerated EGL render path, fps monitoring)
- LSPatch device compatibility — some Android skins / OEM camera HALs may differ (mitigation: supported-device list + diagnostics caps collector)

---

## 10. Roadmap

```
─── 2026 ──────────────────────────────────────────────────────────────▶

Q2  │ POC (5-10 phones, in-house)         ✅ Complete
    │ MVP build (2 wk actual, ~90%)        🟢 ~90% shipped
    │  • Backend + Portal + Backoffice ✅ │
    │  • Mobile companion + VCam ✅       │
    │  • Onboarding wizard + billing ✅   │
    │  • Banner Tier 2 (in progress)      │
    │  • 2-3 design partners (next 1 wk)  │
    │ ─────────────────────────────────────────
Q2 end│ V1 GA — Paid launch               │
    │  • Flat 299 บาท/device/month        │
    │  • Self-serve signup                │
    │  • Scheduling + comment monitoring (V1.5) │
    │ ─────────────────────────────────────────

─── 2027 ──────────────────────────────────────────────────────────────▶

Q1  │ V2 — Multi-profile + Scheduling     │
    │  • TikTok account rotation per dev  │
    │  • Time-based start/stop            │
Q2  │ V3 — Pro features + AI assist       │
    │  • Hybrid live (exploratory)        │
    │  • AI comment reply (exploratory)   │
```

> Note: International / SEA expansion **no longer in plan** (per 2026-05-31 direction)

---

## 11. Resource & Investment

### Team (MVP → V1) — AI-leveraged

| Role | MVP (8 wk) | V1 (3 mo) | Notes |
|---|---|---|---|
| Pond (founder + full-stack) | 1 (50K/mo) | 1 | leverages Claude as co-pilot |
| Hire: 1 full-stack engineer | – | 1 | reduces single-person risk for V1 |
| Customer Success | – | 1 | when 10+ paying Users |
| **Total MVP** | **1 FTE** | **3 FTE** | AI tools = headcount multiplier |

### Infrastructure (Hybrid: GCP compute + DB + Cloudflare R2 storage)
| Phase | Concurrent phones | Tier | Infra cost/เดือน | บาท/device |
|---|---|---|---|---|
| POC | 10 | Hybrid (GCP minimal + R2) | < 2K บาท | 200 |
| MVP | 100-300 | Hybrid (Cloud Run + Cloud SQL + R2) | 4-8K บาท | 15-30 |
| V1 launch | 500-2,000 | Hybrid (scale up) | 10-15K บาท | 8-15 |
| At scale (2,000 users × 7.5 dev) | 15,000 | Hybrid mature | ~27K บาท | ~1.78 |
| Enterprise (per customer) | varies | Tier C/D (GCP) | premium, quoted | 40-100 |

→ Full breakdown in `docs/planning-artifacts/cost-analysis-gcp.md`
→ **Cloudflare R2 + Cloudflare CDN = $0 egress** (sidesteps biggest variable cost in GCP)
→ **Server cost ratio < 10% revenue** at MVP scale = healthy SaaS economics

### Non-infra opex (all tiers)
- Email (Resend), Sentry, Linear, GitHub: ~6-12K บาท/เดือน at V1 maturity
- Customer support tool, payment processing: variable

### Customer-side cost (per 100-phone deployment)
- Phones: 100 × ~5K = 500K บาท (one-time, can use refurbished)
- SIM (if not Wi-Fi): 50K/เดือน
- Cooling rack: 30K (one-time)
- Power + space: 5-10K/เดือน

### Pricing (FINAL — DECIDED 2026-05-23)
**Flat 299 บาท/device/month — no tier**

| Devices | บาท/เดือน | บาท/ปี (20% off) |
|---|---|---|
| 1 device | 299 | 2,870 |
| 5 devices | 1,495 | 14,352 |
| 10 devices | 2,990 | 28,704 |
| 30 devices | 8,970 | 86,112 |
| 100 devices | 29,900 | 287,040 |
| 1,000 devices | 299,000 | 2,870,400 |

- **Flat 299/device/month** — match industry benchmark (3-phone tool ในตลาดไทย)
- **ไม่มี free trial** — signup → pay → use
- **20% annual discount** if pay yearly
- **No volume discount** — linear pricing keeps onboarding simple

### Revenue projection (flat 299 × avg 7.5 devices)
| Stage | Customers | Avg devices | MRR | ARR | Server cost % |
|---|---|---|---|---|---|
| V1 6-mo | 30 | 7.5 | 67K บาท | 807K บาท | ~10% |
| V1 12-mo | 80 | 7.5 | 179K บาท | 2.15M บาท | ~6% |
| Year 2 | 300 | 7.5 | 673K บาท | 8.07M บาท | ~3% |
| At scale | 2,000 | 7.5 | **4.49M บาท** | **53.8M บาท** | <1% |

**Breakeven:** ~31 customers (234 devices) ที่ V1 fixed cost ~70K/month
→ Gross margin > 99% at scale (vertical SaaS leader level)

---

## 12. Decisions Needed from Executive

| # | Decision | Recommendation | Impact |
|---|---|---|---|
| 1 | Approve MVP phase | ✅ Approve | Unlock 8-week build, **~200K บาท investment** |
| 2 | Team structure | Pond solo full-stack + Claude (50K/mo) | AI-leveraged, no hire for MVP |
| 3 | Infra budget | 5-10K/mo MVP cap | Hybrid GCP + Cloudflare R2 |
| 4 | Public positioning | Live Commerce Ops Platform (A1) | Avoid MOD APK / fraud association |
| 5 | Design partners | Onboard 2-3 friendly agencies | Validation + early revenue |
| 6 | Legal review | TikTok ToS + Thai PDPA + Computer Crime Act + **customer ToS (ban risk transfer)** | Before GA |
| 7 | Hosting region | Bangkok-first | Latency + data residency |

---

## Appendix: Related docs
- [Market Research](./market-research-tiktok-live-rerun.md)
- [Technical Architecture Draft](./technical-architecture-draft.md)
