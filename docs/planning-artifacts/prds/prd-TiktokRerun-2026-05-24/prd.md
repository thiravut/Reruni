---
title: TiktokRerun
status: final
created: 2026-05-24
updated: 2026-05-24
---

# PRD: TiktokRerun
*Working title — confirm.*

## 0. Document Purpose

PRD นี้สำหรับ **executive sponsor, engineering lead, design lead, และ go-to-market lead** ที่จะตัดสินใจ approve MVP build phase ของ TiktokRerun — แพลตฟอร์มควบคุม TikTok Live Commerce บน device fleet ผ่าน web dashboard

เอกสารใช้ **Glossary-anchored vocabulary** (ทุก term นิยามครั้งเดียวใน §3) **features grouped** (§4) กับ **FR-N ที่ stable** ให้ downstream (architecture, epics, stories) อ้างอิงต่อได้ และ `[ASSUMPTION]` tags inline สำหรับสิ่งที่ผม inferred ต้องการให้ confirm

**Inputs ที่ PRD นี้ build ต่อจาก** (อยู่ใน `docs/planning-artifacts/`):
- `system-overview.md` — exec-level system design + POC validation status
- `market-research-tiktok-live-rerun.md` — market, competitor, TikTok policy landscape
- `technical-architecture-draft.md` — engineering tech draft + hard questions

PRD นี้ **ไม่ทำซ้ำ** เนื้อหา technical architecture (อยู่ใน addendum.md และ tech draft) แต่จะอ้างอิงเมื่อจำเป็น

---

## 1. Vision

**TiktokRerun คือ operations cockpit ที่ทำให้ทีมเล็กจัดการ TikTok Live Commerce ในระดับร้อยร้านได้ จากหน้าเว็บเดียว พร้อม dynamic content composition — เปลี่ยน promo, ราคา, countdown กลาง live ได้โดยไม่ต้องตัดวิดีโอใหม่**

ปัจจุบัน TikTok Shop กลายเป็นช่องทางขายหลักของ seller ไทย — algorithm ให้ priority กับร้านที่ live บ่อย ผู้ซื้อ convert บน live เร็วกว่า static feed หลายเท่า แต่ "การ live สดตลอดเวลา" บีบ seller รายเล็ก-กลางอย่างหนัก: ไม่มีคน on-cam 24/7, ไม่มีกำลังจ้างทีม, ขาดเครื่องมือ orchestrate ระดับ scale ตลาดมีทางออกแบบเถื่อน (MOD APK, manual phone farms, OBS desktop ที่ไม่ scale) และมี commodity broadcast tools (ADB Wi-Fi 3-phone tools) ที่ "set-and-forget" ไม่สามารถควบคุมระหว่าง live ได้ — pin product เปลี่ยนไม่ได้, สลับวิดีโอไม่ได้, เปลี่ยน promo ไม่ได้

TiktokRerun แก้ด้วย 3 capability หลักที่ตลาดยังไม่มี:
1. **Persistent web control** — operator คนเดียวคุม fleet 100+ phones จาก web anywhere, real-time mid-live (vs commodity tools ที่ขาดการเชื่อมต่อหลัง live เริ่ม)
2. **Smart Overlay broadcast** — Companion App วาดวิดีโอเป็น overlay บน TikTok screen-share → TikTok UI ซ่อนอยู่ใต้ overlay ตลอด → pin product, switch product ทำได้โดยไม่ flicker
3. **Dynamic Banner composition** — render banner, countdown, price, sticker ทับ video real-time จาก web → 1 วิดีโอ base ใช้ run 100 campaigns ได้ ไม่ต้องตัดใหม่

ทั้งหมดทำงานบน flow native ของ TikTok (screen-share live) ไม่ใช่การ mod app, ไม่ต้อง root device, ลูกค้าใช้ Android phone อะไรก็ได้

ผลลัพธ์: solo seller / reseller ที่ run TikTok Shop หลายบัญชี สามารถ live commerce ครอบคลุมทุกบัญชีตลอด 24/7 จาก laptop เครื่องเดียว — ไม่ต้องจ้างทีม, ไม่ต้องเดินไปแตะโทรศัพท์ทีละเครื่อง, react ต่อ promo opportunity ใน seconds

---

## 2. Target User

### 2.1 Primary Persona

**"ตอง" — Solo TikTok Shop Seller ที่ run หลายบัญชี** [ASSUMPTION: ชื่อ/รายละเอียดสมมติ — Pond confirm หรือ replace ด้วย customer จริงเมื่อ design partner conversation เริ่ม]

- อายุ 24-38, ทำ TikTok Shop full-time (อาจ side-hustle จากงานประจำหรือ solo entrepreneur)
- มี **3-15 TikTok Shop accounts** สำหรับ category หลายกลุ่ม (เช่น gadgets, beauty, snacks, fashion)
- เริ่มต้น 1-3 phones, scale ถึง 10-20 phones เมื่อ business โต
- **คนเดียว** เป็น everything: founder + product picker + live operator + customer service
- ปัจจุบันใช้ manual: เดินจิ้มทีละเครื่อง, ใช้ tool 3-phone PC ของ TH ที่ขาด feature, ตื่นกลางดึกเช็คเครื่อง
- ใช้ TikTok Shop เป็นช่องทางหลัก (>60% ของรายได้) → uptime สำคัญมาก
- Goal: **ขยาย live coverage โดยไม่ต้องจ้างคน** เพราะ margin ไม่พอจ่ายเงินเดือน

### 2.2 Jobs To Be Done

- **เริ่ม live หลายเครื่องพร้อมกันโดยไม่ต้องเดินไปกดทุกเครื่อง**
- **เปลี่ยนวิดีโอที่กำลัง broadcast ได้ภายในไม่กี่วินาที** (เช่น เริ่ม flash sale)
- **ปักตะกร้าสินค้าให้ตรงกับสิ่งที่กำลังพูดถึงในวิดีโอ** โดยไม่ต้องสัมผัสเครื่อง
- **เปลี่ยน promo/countdown/ราคา ทับวิดีโอ** โดยไม่ต้องตัดวิดีโอใหม่
- **มองเห็นสถานะทุกเครื่องในที่เดียว** — live/offline/error
- **กลับมาทำงานเร็วเมื่อเครื่องพัง** (reconnect, restart, swap)
- **ตื่นมา ดูสถานะตอนเช้า** — เห็นว่ากลางคืนมีปัญหาไหม, broadcast hours, sales

### 2.3 Non-Users (V1)

- **Solo creator / 1 บัญชี** — ROI ต่ำ (Starter tier 10 devices = overspec); ใช้ TikTok app ปกติพอ
- **Brand ที่ทำ live สดด้วยคนจริงเป็นหลัก** (แฟชั่น influencer) — ไม่ต้องการ pre-recorded broadcast
- **Agency ขนาดใหญ่ที่ต้องการ multi-user / team permission** — defer ไป Phase 2+ (validate demand ก่อน)
- **บริษัทที่ต้องการ compliance ระดับ enterprise** (SOC2, ban-free guarantee) — TiktokRerun ทำ best-effort เท่านั้น

### 2.4 Key User Journeys

- **UJ-1. ตอง onboard เครื่องใหม่เพิ่มเข้าระบบ**
  ตองซื้อ phone ใหม่มา 3 เครื่อง วางที่บ้าน Login TikTok account ใหม่ของ category "snacks" เปิด companion app, กด "Pair" บน web dashboard → web แสดง QR, scan ด้วย companion app → 3 วินาทีต่อมา web แสดง "Device #11 online" Pair ทั้ง 3 เครื่องใน 3 นาที **Edge case:** เครื่องที่ Wi-Fi เสีย ขึ้นเตือนใน dashboard ทันที ตองเดินไปแก้

- **UJ-2. ตอง start live ทุกบัญชีเช้านี้**
  เวลา 09:55, ตองเปิด dashboard เห็น 8 devices online เลือกทุกเครื่อง, assign video promo เช้าตามที่ตัดต่อไว้, กด "Start Live" → web ยิง command พร้อมกันทั้ง 8 เครื่อง → ภายใน 10 วินาที 7 เครื่องขึ้น LIVE, 1 เครื่อง retry ต่ออีก 20 วินาที ทั้งหมดเข้าสู่สถานะ broadcasting ตองเปิด TikTok บนมือถือเข้าดู live หนึ่งบัญชี → เห็นภาพและได้ยินเสียงตามวิดีโอ ⚡ **Edge case:** เครื่อง #3 ขึ้น error "TikTok app ต้องอัปเดต" → dashboard ระบุชัด, ตองอัปเดตทีหลัง

- **UJ-3. ตอง pin สินค้าใหม่กลาง live**
  วิดีโอเล่นถึงช่วงที่ presenter พูดถึงสินค้า SKU-2024 ตองเลือก devices ที่ broadcast วิดีโอ category นั้น (4 เครื่อง), search "SKU-2024" ใน product catalog, กด "Pin to all selected" → ภายใน 5 วินาที ทุก live ของ 4 เครื่องนั้น product anchor เปลี่ยนเป็น SKU-2024 ผู้ชมที่ดูอยู่เห็น anchor update แบบ real-time **Edge case:** สินค้า out of stock → dashboard บอก "1 device: SKU unavailable" → ตองเลือก backup SKU แทน

- **UJ-4. ตอง switch วิดีโอกลาง live (ไม่ต้องปิด live)**
  ตอนบ่ายตองเพิ่ง edit วิดีโอ promo ตัวใหม่เสร็จ Upload เข้าระบบ, เลือก devices ที่ broadcast วิดีโอเก่าอยู่, "Switch video without restarting live" → 5 วินาทีต่อมา ทุกเครื่องเปลี่ยนวิดีโอ ผู้ชมเห็น content เปลี่ยน, live ไม่ขาด ไม่เสีย viewer momentum

- **UJ-5. ตอง monitor ตอนกลางคืน**
  เวลา 22:00 ตองนอน, เปิด dashboard บนมือถือ → เห็น 6 devices LIVE, 1 OFFLINE (battery หมด), 1 ERROR (live ค้าง 10 นาที) ส่งคำสั่ง "Restart live" ให้เครื่อง error → 8 วินาทีต่อมา device กลับมา LIVE ปกติ เครื่อง offline ตองเดินไปเสียบชาร์จ

---

## 3. Glossary

- **User** — บุคคล 1 คน ที่ signup เข้า TiktokRerun และเป็นเจ้าของ Devices, Videos, Live Sessions ทั้งหมดที่ pair / upload / start ภายใต้ account นั้น 1 User = 1 billing entity
- **Device** — Android phone ที่ติดตั้ง Companion App และ pair กับ User account แล้ว 1 Device = 1 TikTok seller account (logged in via TikTok app บนเครื่องนั้น)
- **Companion App** — Android Kotlin app ที่ TiktokRerun พัฒนา รันบน Device คุม video playback + TikTok automation
- **Group** — subset ของ Devices ของ User ที่ tag ไว้ (เช่น "Snacks", "Beauty", "Night shift") — สำหรับ batch commands
- **Broadcast Video** — ไฟล์วิดีโอ (mp4) ที่ User upload เข้าระบบ ใช้เป็น content ของ Live Session
- **Live Session** — instance หนึ่งของการ broadcast บน Device หนึ่ง เริ่มเมื่อ User กด Start Live, จบเมื่อ User กด Stop หรือ TikTok บังคับให้จบ
- **Pinned Product** — สินค้าจาก TikTok Shop ที่ User pin ไว้บน Live Session ผู้ชมคลิก buy ได้ทันที
- **Command** — instruction ที่ User ส่งจาก web dashboard ไปยัง Device(s) ผ่าน Backend (เช่น start_live, switch_video, pin_product, stop)
- **Stream Status** — สถานะปัจจุบันของ Device: `idle`, `pairing`, `live`, `error`, `offline`
- **Pairing** — กระบวนการเชื่อม Device ใหม่เข้ากับ User account ผ่าน QR code scan
- **Backend** — cloud services ของ TiktokRerun ที่ทำหน้าที่ orchestrate Devices, store data, serve dashboard
- **TikTok App** — application ของ TikTok เอง (ไม่ใช่ของเรา) ที่ติดตั้งอยู่บน Device ใช้ login TikTok account + ทำ screen-share live
- **Screen-Share Live** — โหมด live ของ TikTok app ที่ broadcast หน้าจอ phone แทนกล้อง — Companion App วาด Overlay → TikTok screen-share จับ broadcast
- **Overlay** — full-screen window ที่ Companion App วาดทับบน TikTok App ผ่าน Android SYSTEM_ALERT_WINDOW Overlay ประกอบด้วย Video Layer + Banner Layer; MediaProjection ของ TikTok screen-share จับ Overlay ทั้งหมด
- **Banner** — graphic element (ข้อความ, icon, countdown, price tag, brand watermark) ที่ render บน Banner Layer ของ Overlay; Static Banner กำหนดครั้งเดียวต่อ Broadcast Video; Dynamic Banner update real-time จาก User command
- **Banner Slot** — ตำแหน่งบน Overlay ที่ Banner ถูกวาง (top, bottom, top-left, top-right, bottom-left, bottom-right, center)
- **Composition** — ภาพรวมของสิ่งที่ผู้ชมเห็น = Broadcast Video + Banner ทั้งหมด รวมกัน
- **Live Title** — ชื่อ live ที่แสดงบนสุดของ TikTok live ระหว่าง broadcast (set ที่ TikTok app ตอน Go Live setup)
- **Live Caption** — คำอธิบายของ Live Session ที่แสดงให้ผู้ชม (TikTok-facing metadata)
- **Live Hashtag** — แท็ก # ที่ผูกกับ Live Session ช่วย discoverability ใน TikTok feed
- **Live Metadata** — กลุ่มของ Live Title + Live Caption + Live Hashtag ที่ User ตั้งจาก web และ Companion App ใส่ใน TikTok app ผ่าน Accessibility Service ก่อน Start Live Session
- **Admin** — ทีม TiktokRerun ที่ login เข้า Backoffice เพื่อ monitor platform, support users, intervene เมื่อจำเป็น

---

## 4. Features

### 4.1 Device Pairing & Fleet Management

**Description:** User เพิ่ม Device ใหม่เข้า account ผ่าน QR code, จัดกลุ่ม, ดู Stream Status real-time, ลบ/replace Device ได้ Realizes UJ-1, UJ-5 ใช้ Glossary terms ตรงทุกคำ

**Functional Requirements:**

#### FR-1: Pair Device via QR Code

User สามารถ pair Device ใหม่เข้า Fleet ผ่าน QR code ที่ generate จาก web dashboard

**Consequences (testable):**
- Web dashboard generates pairing QR ที่ encode pairing token (one-time use, expires in 5 minutes)
- Companion App มี QR scanner; scan แล้วเชื่อม WebSocket กับ Backend ใน < 3 วินาที
- Pairing สำเร็จ → Device ปรากฏใน Fleet view ภายใน 5 วินาที (Stream Status = `idle`)
- Pairing token หมดอายุ → scan ขึ้น error "Token expired, generate new QR"

#### FR-2: View Fleet Status

User สามารถดูสถานะของทุก Device ใน Fleet พร้อมกันจาก dashboard

**Consequences (testable):**
- Fleet view แสดง: Device ID, Stream Status, current Broadcast Video, current Pinned Product, last heartbeat timestamp, signal strength [ASSUMPTION: signal strength = WebSocket ping latency]
- Stream Status update real-time (< 2 วินาที latency จาก state change บน Device ถึง dashboard)
- Device offline > 60 วินาที → Stream Status เป็น `offline`, แสดง warning visual

#### FR-3: Group Devices

User สามารถจัด Devices เป็น Group ตาม tag (เช่น "Brand A", "Night shift") เพื่อ batch command ในภายหลัง

**Consequences (testable):**
- User สร้าง/แก้ไข/ลบ Group ได้
- 1 Device ขึ้น Group ได้หลาย Group พร้อมกัน
- Group ใช้เป็น target ของ batch Command ได้ใน FR-5, FR-7, FR-9

#### FR-4: Remove / Replace Device

User สามารถลบ Device ออกจาก Fleet หรือ replace ด้วย Device ใหม่ (โดย transfer Group membership ให้)

**Consequences (testable):**
- Remove → Device หยุด receive Commands ทันที, ถูก mark `unpaired` ใน DB
- Replace → ระบบ generate QR ใหม่, Group membership ของ Device เก่าโอนไป Device ใหม่อัตโนมัติ

**Feature-specific NFRs:**
- Fleet view ต้อง render < 1 วินาที สำหรับ Fleet ขนาดถึง 200 Devices

---

### 4.2 Broadcast Video Library

**Description:** User upload, organize, และ assign วิดีโอที่จะใช้ broadcast Realizes UJ-2, UJ-4

#### FR-5: Upload Broadcast Video

User สามารถ upload ไฟล์ mp4/mov ผ่าน web dashboard เก็บใน Backend storage

**Consequences (testable):**
- Support format: mp4 (h.264 + AAC), max size 500 MB, max duration 60 นาที [ASSUMPTION]
- Upload progress แสดง progress bar real-time
- Upload เสร็จ → ระบบ transcode เป็น mobile-optimized profile (720p, 30fps target) [ASSUMPTION]
- Transcoded video พร้อมใช้ภายใน 2× ความยาววิดีโอ
- User ตั้งชื่อ + tag video ได้

#### FR-6: Organize Video Library

User สามารถสร้าง folder, tag, search video ใน library

**Consequences (testable):**
- Library view filter ได้: by tag, by folder, by upload date, by file name (text search)
- ลบ video ที่ใช้ใน Live Session ปัจจุบัน → block + แจ้งเตือน "Video is in use by N Live Sessions"

---

### 4.3 Live Session Control

**Description:** Core feature — User สั่ง Device เริ่ม/หยุด/restart Live Session, สลับ Broadcast Video, monitor live state Realizes UJ-2, UJ-4, UJ-5

#### FR-7: Start Live Session

User สั่ง 1+ Devices ให้เริ่ม Live Session ด้วย Broadcast Video ที่เลือก

**Consequences (testable):**
- รองรับ target: single Device, multiple selected Devices, หรือ Group
- Backend ส่ง `start_live` Command ผ่าน WebSocket → Companion App execute:
  - Accessibility Service เปิด TikTok App → "Go Live" → "Screen Share" → confirm
  - Companion App สลับเป็น foreground → เล่น Broadcast Video เต็มจอ loop
  - รายงาน Stream Status = `live` กลับ Backend
- User เห็น confirmation per Device ภายใน 15 วินาทีต่อเครื่อง (median); 90th percentile < 30 วินาที
- Failure mode: TikTok app ขึ้น dialog ขัด (เช่น "Update required") → Stream Status = `error`, แสดง error message ที่ dashboard

#### FR-8: Stop Live Session

User สั่ง Device(s) ให้หยุด Live Session

**Consequences (testable):**
- Companion App ใช้ Accessibility Service กด "End Live" บน TikTok app
- กลับ Stream Status = `idle` ภายใน 5 วินาที

#### FR-9: Switch Broadcast Video (without restarting Live)

User เปลี่ยน Broadcast Video ของ Live Session ที่กำลังรันอยู่ โดยไม่ต้องหยุด live

**Consequences (testable):**
- Companion App หยุดเล่นวิดีโอเดิม → เริ่มเล่นวิดีโอใหม่ภายใน 5 วินาที
- TikTok Live ไม่ถูก interrupt (ไม่เสีย viewer)
- Transition smooth — ไม่มี black screen นานเกิน 2 วินาที [ASSUMPTION]

#### FR-10: Restart Failed Live

User สั่ง restart Device ที่ Stream Status เป็น `error`

**Consequences (testable):**
- Backend ส่ง `restart_live` Command → Companion App stop + clean state + retry start_live ด้วย Broadcast Video เดิม
- Recovery time < 30 วินาที สำหรับ recoverable error
- Unrecoverable error (เช่น TikTok account banned) → Stream Status = `error`, ระบุ reason

#### FR-11: Audio Volume Control

User ปรับ volume ของ Broadcast Video per Device ได้จาก dashboard

**Consequences (testable):**
- Volume slider 0-100%
- Apply real-time (< 3 วินาที)
- Setting persist per Device

---

### 4.4 TikTok Shop Product Control

**Description:** User pin/unpin/switch สินค้าจาก TikTok Shop บน Live Session ผ่าน dashboard Realizes UJ-3

#### FR-12: Pin Product to Live Session

User pin TikTok Shop product (ระบุด้วย SKU/product link) ไว้บน Live Session

**Consequences (testable):**
- User ค้นหาสินค้าโดย SKU หรือ product link
- Pin command → Companion App ใช้ Accessibility Service ทำ pin action บน TikTok app
- ผู้ชม Live เห็น product anchor update ภายใน 5 วินาที
- รองรับ batch pin: pin product เดียวกันให้ Devices หลายตัวพร้อมกัน

#### FR-13: Unpin / Switch Pinned Product

User unpin product ปัจจุบัน หรือ switch ไป product ใหม่

**Consequences (testable):**
- Unpin: anchor หายจาก Live ภายใน 5 วินาที
- Switch: เปลี่ยน anchor เป็น product ใหม่ภายใน 5 วินาที (ไม่ต้อง unpin ก่อน)

#### FR-14: Out-of-Stock Detection

ระบบรายงานเมื่อ product ที่จะ pin หรือ pin อยู่กลายเป็น out-of-stock

**Consequences (testable):**
- Pin attempt + product OOS → Command failed, แสดง reason "SKU unavailable"
- Pinned product OOS ขณะ live → dashboard แสดง warning, User เลือก action (auto-unpin / switch / ignore) [ASSUMPTION: ต้อง confirm flow นี้]

**Feature-specific NFRs:**
- Pin action ต้อง idempotent (same Command ส่งซ้ำ → state ไม่เพี้ยน)

---

### 4.5 Web Dashboard

**Description:** UI หลักของ User — fleet view, video library, command panel, status monitoring

#### FR-15: User Authentication

User login ด้วย email + password เพื่อเข้าถึง devices + videos ของตน

**Consequences (testable):**
- Email/password auth
- Session timeout 24 ชั่วโมง [ASSUMPTION]
- Wrong credentials 5 ครั้ง → lockout 15 นาที

#### FR-16: Real-time Fleet Dashboard

User เห็น fleet status update real-time

**Consequences (testable):**
- Push-based update via WebSocket (Backend → browser)
- Latency change-to-display < 2 วินาที
- Reconnect automatically on connection loss

#### FR-17: Mobile-Responsive Dashboard

Dashboard ใช้งานได้บนมือถือ — User monitor + restart live ได้จากที่ไหนก็ได้

**Consequences (testable):**
- Responsive layout for screen ≥ 360px width
- Core actions (view status, restart live, stop live) ใช้งานได้บน mobile

**Notes:** [NOTE FOR PM] dashboard ที่ desktop เป็น primary; mobile = monitoring only ใน MVP — full control บน mobile = Phase 2

---

### 4.6 Onboarding (Customer-side)

**Description:** วิธีลูกค้าใหม่ setup ตัวเอง — สมัคร, ติดตั้ง Companion App บน phones, pair, login TikTok

#### FR-18: Self-Service User Signup

ลูกค้าใหม่สมัคร account ได้เอง — verify email, ตั้ง password, ยอมรับ Terms of Service (รวม **ban risk acknowledgement clause**)

**Consequences (testable):**
- Signup flow มี ToS acceptance checkbox + scroll-to-accept (ไม่ใช่ pre-checked)
- ToS acceptance event log per user (timestamp, IP, version of ToS)
- หลัง signup → ลูกค้าต้องเลือก subscription tier + ใส่ payment method (Stripe) ก่อนจึงใช้ feature ใดๆ ได้
- **ไม่มี free trial** — signup เปิด account แต่ตั้ง subscription_status = "pending"; require active subscription เพื่อ pair device, upload video, start live

#### FR-19: Companion App Distribution

ลูกค้า download Companion App ผ่าน link (sideload APK เริ่มต้น; Play Store later) [ASSUMPTION: Play Store policy อาจไม่อนุญาต — ต้อง legal review]

**Consequences (testable):**
- APK ใหม่ทุก release มี version + signature check
- In-app update mechanism — Companion App auto-check และ download update ใหม่

---

### 4.7 Live Metadata Configuration

**Description:** User ตั้ง Live Title, Live Caption, และ Live Hashtag จาก web dashboard — Companion App กรอกค่าเหล่านี้ใน TikTok app ผ่าน Accessibility Service ก่อนเริ่ม Live Session อัตโนมัติ Realizes vision item #5 (กำหนดรายละเอียดจากหน้าเว็บ)

**Functional Requirements:**

#### FR-20: Set Live Title

User กำหนด Live Title ผูกกับ Broadcast Video หรือ override ต่อ Live Session

**Consequences (testable):**
- Title field: utf-8, max 100 chars [ASSUMPTION: TikTok live title limit — ต้อง verify]
- Default: ใช้ชื่อ Broadcast Video; Override per Live Session ได้
- Companion App ใส่ Title ในหน้า "Go Live" setup ของ TikTok ผ่าน Accessibility ก่อนกด Start
- หาก field ไม่พบ (TikTok UI เปลี่ยน) → ใช้ default value, log warning

#### FR-21: Set Live Caption

User กำหนด Live Caption (คำอธิบายของ live)

**Consequences (testable):**
- Caption field: utf-8, max 500 chars [ASSUMPTION: ต้อง verify limit]
- Optional field (Live Session สามารถ run โดยไม่มี Caption)
- Companion App ใส่ Caption ใน TikTok Go Live setup ผ่าน Accessibility

#### FR-22: Set Live Hashtag

User กำหนด Hashtags (1-10 tags) ผูกกับ Live Session

**Consequences (testable):**
- Input: comma-separated list, validate format (#word, no space)
- Companion App ใส่ Hashtags ใน TikTok caption/title field ตาม TikTok convention
- Hashtag templates: reusable across videos (เช่น "TH-flash-sale" = "#แฟลชเซลล์ #ส่งฟรี #โปรโมชั่น")

**Feature-specific NFRs:**
- Metadata setup ต้องเสร็จก่อน start_live timeout ที่ FR-7 (รวมแล้วยัง < 30s p90)
- หาก TikTok UI ไม่รับ metadata field → live ยังเริ่มได้ (graceful degrade)

**Notes:** [NOTE FOR PM] ทั้ง 3 fields นี้คือ TikTok-facing — ไม่ใช่ internal label; ต่างจาก video filename ที่ใช้ใน Library

---

### 4.8 Banner & Overlay Composition

**Description:** User เพิ่ม Banner (text, color, position, optional countdown) ลงบน Composition ของ Live Session — สามารถตั้ง Static Banner ก่อน live, หรือ update Dynamic Banner ระหว่าง live real-time จาก web Banner render บน Overlay layer เดียวกันกับ Broadcast Video, ผู้ชมเห็นเป็นส่วนหนึ่งของ broadcast Realizes new commerce-critical UJ (flash sale, countdown, price drop, urgency messaging)

**Functional Requirements:**

#### FR-23: Static Banner per Broadcast Video

User กำหนด Banner (1 หรือมากกว่า) ผูกกับ Broadcast Video ใน Video Library Banner จะ render อัตโนมัติทุกครั้งที่ video นี้ถูก broadcast

**Consequences (testable):**
- Banner editor มี field: text (utf-8, max 80 chars), background color (hex), text color, Banner Slot position, font size (S/M/L)
- ผูก Banner ได้ถึง 4 Banners ต่อ 1 Broadcast Video [ASSUMPTION: 4 = upper limit ของ readability บน mobile screen]
- เมื่อ Live Session เริ่มด้วย video นั้น → Banner ทุกตัวปรากฏบน Overlay ตามตำแหน่งที่ตั้งไว้
- Banner เปลี่ยน → apply ต่อ Live Session ถัดไป (ไม่ retroactive)

#### FR-24: Dynamic Banner — Real-time Update

User สั่ง update Banner ระหว่าง Live Session ที่กำลังรันอยู่ การเปลี่ยนแปลงเห็นใน broadcast ภายใน 3 วินาที

**Consequences (testable):**
- Backend ส่ง `update_banner` Command ผ่าน WebSocket → Companion App update Banner Layer real-time
- รองรับ operation: add, update, remove Banner ที่ specific Banner Slot
- รองรับ batch update: เปลี่ยน Banner ของ Devices หลายตัวพร้อมกัน
- Latency operator-action → viewer-see < 3 วินาที (95th percentile)
- ระหว่าง update ไม่มี frame drop ที่ผู้ชมสังเกตเห็น

#### FR-25: Countdown Banner

User ตั้ง Banner แบบ countdown — แสดง timer นับถอยหลังถึง deadline ที่กำหนด

**Consequences (testable):**
- User ระบุ: deadline timestamp + text template (เช่น "Flash Sale เหลือ {hh:mm:ss}")
- Countdown render real-time ทุกวินาที บน Overlay
- เมื่อถึง deadline → trigger configurable action: hide banner / replace with "หมดเวลา" / persist

#### FR-26: Price / Stock Banner (data-bound)

User ตั้ง Banner ที่ผูกกับข้อมูล Pinned Product — แสดงราคา, stock, discount % automatically

**Consequences (testable):**
- Banner template: `"฿{price} เหลือ {stock} ชิ้น"` — placeholder จะถูก fill จาก Pinned Product ปัจจุบัน
- เมื่อ Pin Product เปลี่ยน → Banner update content อัตโนมัติ
- Pinned Product OOS → Banner เปลี่ยน text เป็น "หมด!" หรือ hide ตาม config

**Feature-specific NFRs:**
- Banner rendering ต้องไม่ทำให้ video frame drop ลดต่ำกว่า 28fps (target 30fps)
- Banner update Command ต้อง idempotent (เหมือน FR-12 Pin)
- Banner ต้อง legible ที่ resolution 720p (text ≥ 16sp)

**Notes:** [NOTE FOR PM] Banner = killer differentiator vs commodity broadcast tools — สำคัญต่อ marketing pitch ของ "เปลี่ยน promo กลาง live ได้"

---

## 5. Non-Goals (Explicit)

- **ไม่ build เครื่องมือ fake engagement** — bot viewers, fake comments, fake likes ไม่ใช่ feature และจะไม่ถูกพัฒนา
- **ไม่ build TikTok account creation / verification bypass** — ลูกค้าต้องสมัคร TikTok account ตามขั้นตอนของ TikTok เอง
- **ไม่ modify TikTok app** — ใช้ official TikTok app เท่านั้น, ไม่ใช่ MOD APK
- **ไม่มี detection-evasion features ที่ public-facing** — IP rotation/fingerprint spoofing ไม่ใช่ marketable feature (อาจมี backend mitigation แต่ไม่ promote เป็น "ปลอด ban")
- **ไม่ guarantee TikTok account uptime** — ลูกค้าเข้าใจและยอมรับว่าบัญชีอาจถูก ban โดย TikTok ตามนโยบายของ TikTok
- **ไม่ support iOS ใน V1** — Android เท่านั้น (TikTok screen-share API ใน iOS ต่างกัน, deferred)
- **ไม่ support live ที่ใช้กล้องสด** — TiktokRerun สำหรับ pre-recorded broadcast เท่านั้น (hybrid live = Phase 3)
- **ไม่ทำ comment moderation ใน MVP** — ตอบ comment ด้วย AI / human = Phase 2-3
- **ไม่มี analytics เชิงลึก ใน MVP** — แค่ basic status; full GMV/retention analytics = Phase 3
- **ไม่ขยายตลาดต่างประเทศ ใน V1** — TH-first; SEA expansion = 2027

---

## 6. MVP Scope

### 6.1 In Scope

- FR-1 ถึง FR-26 (ทั้งหมดข้างบน, รวม Live Metadata + Banner & Overlay Composition)
- **Smart Overlay broadcast mode** (Companion App + SAW overlay + Accessibility) — verified ผ่าน POC extension
- รองรับ **ถึง 100 Devices ต่อ User account**
- Web dashboard (desktop primary, mobile read+restart)
- Companion App (Android เท่านั้น)
- Static + Dynamic Banner (Tier 1 + Tier 2)
- Beta with 2-3 design partner Orgs

### 6.2 Out of Scope for MVP

- **Scheduling** (time-based start/stop, playlist rotation) — Phase 2 [NOTE FOR PM: emotional ask จากลูกค้า; revisit ถ้า MVP เสร็จเร็ว]
- **Comment monitoring & moderation** — Phase 2
- ~~Multi-user roles + team permissions~~ — **ตัดออกจากแผน** (ลูกค้า solo seller, ไม่ใช่ team product) — revisit เฉพาะถ้ามี enterprise demand ชัดเจน
- **Billing & subscription management** — MVP ใช้ manual invoicing; self-serve billing = Phase 2
- **Banner library / drag-drop editor (Tier 3)** — Phase 2; MVP มีแค่ basic banner form
- **Interactive overlays (comment ticker, order alert, gift react — Tier 4)** — Phase 3
- **Multi-layer composition (PiP, transitions, particles — Tier 5)** — Phase 3
- **Analytics dashboards (GMV, retention, conversion)** — Phase 3
- **Hybrid Live (human takeover)** — Phase 3
- **AI comment reply** — Phase 3
- **Pre-rooted hardware "Pro" tier (true VCAM)** — Phase 2+, optional upgrade path
- **International (SEA)** — 2027
- **iOS support** — TBD

---

## 7. Success Metrics

**Primary**
- **SM-1: Active Devices per User** — median Devices broadcasting live ≥ 4 ชั่วโมง/วัน, target median 30 Devices/User ภายใน 3 เดือนหลัง onboarding Validates FR-1, FR-7
- **SM-2: Command Success Rate** — % ของ Commands (start_live, switch_video, pin_product) ที่ execute สำเร็จภายใน timeout target Target ≥ 95% Validates FR-7, FR-9, FR-12
- **SM-3: User Retention (3-month)** — % ของ paying Users ที่ยังใช้งานหลัง 3 เดือนแรก Target ≥ 70% Validates overall product value

**Secondary**
- **SM-4: Time-to-First-Live** — เวลาเฉลี่ยจาก signup ถึง Live Session แรก Target < 30 นาที Validates FR-1, FR-18, FR-19
- **SM-5: User Productivity** — User คนเดียวคุม Devices ได้กี่เครื่อง simultaneous Target ≥ 50 Devices Validates FR-2, FR-7
- **SM-6: NPS** — Net Promoter Score จาก Users Target ≥ 30 ใน MVP, ≥ 50 ใน V1
- **SM-7: Banner Adoption** — % ของ Live Sessions ที่ใช้ Banner ≥ 1 ตัว Target ≥ 60% ใน 3 เดือนแรก (signal ว่า feature นี้สำคัญต่อ workflow จริง) Validates FR-23, FR-24
- **SM-8: Banner Update Frequency** — median count ของ Dynamic Banner updates per Live Session ของ Users ที่ใช้ Target ≥ 3 (พิสูจน์ว่า real-time = ของจริง ไม่ใช่ static disguised) Validates FR-24
- **SM-9: Live Metadata Completion** — % ของ Live Sessions ที่ตั้ง Title + Caption + Hashtag ครบ Target ≥ 80% (signal ว่า metadata config ใช้งานจริง) Validates FR-20, FR-21, FR-22

**Counter-metrics (do not optimize)**
- **SM-C1: Average Live Session Length** — ห้ามใช้ "ชั่วโมง live ทั้งหมด" เป็น proxy ของ success เพราะลูกค้าอาจ broadcast 24/7 = ban เร็ว → ดู uptime แบบ healthy เท่านั้น (counterbalance SM-1)
- **SM-C2: Customer-reported account bans per month** — track เป็น operational metric แต่ **อย่า incentivize ให้ลด** ด้วยการ disable broadcast (เพราะนั่นทำลาย product value); ใช้เป็น signal ในการสอน best practice ลูกค้า (counterbalance ที่อยากตอบ "ลด ban rate = product success")

---

## 8. Open Questions

1. ~~**Pricing model:** subscription per Device flat หรือ tier-based?~~ → **CLOSED 2026-05-24:** 4-tier (Starter 3,990 / Growth 8,990 / Pro 28,990 / Enterprise quote) per `docs/planning-artifacts/cost-analysis-gcp.md` §6. Validate ±20% range กับ design partners ใน MVP beta
2. ~~**Trial duration:**~~ → **CLOSED 2026-05-26:** ไม่มี free trial; signup → choose tier → pay → use
3. **Play Store distribution:** APK distribution เป็น primary channel หรือ Play Store ได้ (legal/policy เคยปฏิเสธ tool ที่ใกล้เคียง — ต้อง legal review)
4. **Customer ToS draft:** ใครเป็นคนร่าง? — ทนายภายนอก / ทนายใน [NOTE FOR PM: blocker ก่อน paid GA]
5. **OOS auto-handling default:** เมื่อ pinned product OOS, default action = auto-unpin หรือ keep + warn?
6. **TikTok app version compatibility:** ระบบจะ pin TikTok app version ที่ test แล้ว หรือให้ลูกค้าใช้ version ใหม่สุดเสมอ? trade-off ระหว่าง stability กับ feature completeness
7. **Multi-account login per Device:** 1 Device = 1 TikTok account ตลอด หรือ swap account ได้?
8. **Backup strategy เมื่อ TikTok screen-share API เปลี่ยน:** fallback path ไหน (RTMP partner program?)

---

## 9. Stakeholders and Approvals *(Adapt-In: Enterprise)*

| Stakeholder | Role | Approval needed for |
|---|---|---|
| Executive Sponsor | Approve MVP build phase, budget | Investment decision, headcount, brand positioning (A1) |
| Engineering Lead | Tech feasibility sign-off | Tech stack, infra spend, MVP scope |
| Design Lead | UX direction | Dashboard design, onboarding flow |
| Go-to-market Lead | Pricing + sales strategy | Pricing tier, target segment, design partner selection |
| Legal Counsel | ToS + policy review | Customer ToS, Play Store distribution, Computer Crime Act compliance |
| Operations Lead | Customer onboarding readiness | Support model, SLA commitments |

---

## 10. Risk and Mitigations *(Adapt-In: Enterprise)*

| Risk | Severity | Likelihood | Mitigation |
|---|---|---|---|
| TikTok updates Accessibility-blocking measures or detects automation | High | Medium | Selector versioning + 24-48hr patch response SLA; pin TikTok app version |
| TikTok Live Screen-Share API behavior changes | High | Medium | Multi-method fallback (try alternative flow); monitoring + canary devices |
| Mass account bans cause customer churn | High | Medium-High | Best-practice docs; ban-rate telemetry (aggregate); ToS clarity |
| Pin Product mechanism breaks per TikTok UI update | Medium | High | Fallback to web automation (Playwright in cloud); UI selector library |
| Companion App distribution blocked by Play Store | Medium | Medium | Sideload APK + signed update mechanism; legal review for Play Store path |
| Customer site logistics (phone heat, power) | Medium | Medium | Cooling guidelines; ops playbook; remote diagnostic |
| Backend WebSocket scale (1000+ concurrent connections) | Low | Low | Go concurrency model handles; horizontal scale path documented |
| Legal challenge from TikTok | Medium | Low-Medium | A1 positioning; no public claim of ToS bypass; legal review pre-GA |
| Loss of key engineer | Medium | Low | Document architecture; pair on hard subsystems |

---

## 11. Operational Requirements *(Adapt-In: Enterprise)*

- **Uptime SLA (Backend):** 99.5% measured monthly (MVP); 99.9% target for V1
- **Command latency:** 95th percentile < 5s for start_live, < 3s for switch_video, < 3s for pin_product (measured Backend-to-Device)
- **Support response:** Business hours email response < 4hr; critical (multiple Devices down) < 1hr
- **Incident escalation:** Pager on-call rotation for Backend; not for individual Device issues
- **Update cadence:** Backend rolling deploys (zero downtime); Companion App max 1 mandatory update per month
- **Data retention:** User action log 90 วัน; Live Session metadata 12 เดือน; Broadcast Video files = ตามที่ลูกค้าเลือก (default 90 วัน, delete on demand)

---

## 12. Integration and Dependencies *(Adapt-In: Enterprise)*

- **TikTok Mobile App (Android)** — runtime dependency บน Device; Companion App ใช้ Accessibility Service กับ TikTok app
- **TikTok Shop** — product catalog integration ระดับ best-effort (search/pin); ไม่มี official API → ใช้ TikTok app UI automation
- **Cloud Storage (S3-compatible)** — Broadcast Video storage + CDN; assume Cloudflare R2 + CDN [ASSUMPTION]
- **Payment Processor** (Phase 2) — Stripe หรือ Omise [ASSUMPTION]; MVP = manual invoicing
- **Email Provider** — transactional email (signup, alerts); assume Resend หรือ Postmark [ASSUMPTION]
- **Monitoring** — Grafana + Loki + Prometheus, self-hosted

---

## 13. Constraints and Guardrails *(Adapt-In: Cross-cutting)*

### 13.1 Safety
- **No automated content creation** — ระบบ broadcast วิดีโอที่ลูกค้า upload เท่านั้น ไม่ generate/modify content
- **No automated audience deception** — ห้าม build feature ที่สร้าง engagement ปลอม

### 13.2 Privacy (TH PDPA)
- User data: email, password (hashed), action log
- Customer-uploaded videos: ไม่ access โดยทีม TiktokRerun ยกเว้นเพื่อ debug ด้วยความยินยอม
- TikTok account credentials: **ไม่ store** — ลูกค้า login บน TikTok app ของ Device โดยตรง, Companion App ไม่ touch credentials
- Aggregate telemetry: anonymize, ไม่เชื่อมโยงกับ TikTok account ID
- Data residency: Backend hosted Bangkok region (Hetzner / Cloudflare BKK edge) [ASSUMPTION]

### 13.3 Cost (per User)
- Backend cost per Device target **< 20 บาท/เดือน @ 300 devices** (achievable on Tier A: Hetzner + Cloudflare R2 + Neon Postgres) → ที่ pricing 399 บาท/device Starter tier = **server cost ratio < 10%**, gross margin > 90%
- Migration trigger: ที่ 500+ devices total หรือ 30+ paying Users → Tier B (DigitalOcean managed) for stability tradeoff
- Reference: full breakdown ใน `docs/planning-artifacts/cost-analysis-gcp.md`

---

## 14. Why Now *(Adapt-In: Cross-cutting)*

- **TikTok Shop Thailand** เข้าสู่ phase scale — seller ระดับ SME ย้ายจาก marketplace เดิม
- **Live commerce convert rate** สูงกว่า static feed ทำให้ "live presence 24/7" เป็น competitive necessity
- **Commodity broadcast tools (3-phone ADB Wi-Fi)** กำลังโตในไทย — validate demand แต่เป็น set-and-forget ที่ขาด mid-live control + commerce features → ตลาด "step-up tier" ยังว่าง
- **Adjacent competitor (TikMatrix)** validate ว่า market enterprise มีจริงและจ่ายเงิน $29-$149/เดือน — เราเล่นตลาดข้างเคียง (broadcast + commerce vs engagement farm)
- **POC validated** — technical feasibility ของ Smart Overlay broadcast + dynamic banner พิสูจน์แล้ว, technical risk หลักลด
- **No competitor has Dynamic Banner composition** — ทุก commodity tool lock content ใน video file; เราเป็น first ที่แยก content base กับ promo layer
- **First-mover advantage** — ก่อน TikTok ออก policy ที่ tighter หรือ partner program ที่ปิดประตู

---

## 15. Platform *(Adapt-In: Consumer/branded)*

- **V1 platforms:** Web dashboard (desktop primary, mobile-responsive); Companion App (Android 10+)
- **V2 considerations:** Native desktop app (Electron) สำหรับ heavy operators; iOS Companion App (เมื่อ TikTok iOS screen-share API พิสูจน์ได้)

---

## 16. Compliance and Regulatory *(Adapt-In: Regulated)*

- **TH Personal Data Protection Act (PDPA)** — DPO designation, consent flows, breach notification process
- **TH Computer Crime Act B.E. 2550** — ensure ระบบไม่ตีความเป็น unauthorized access ของ third-party system (TikTok); legal opinion required
- **TikTok Terms of Service** — best-effort compliance ใน product positioning; explicit acknowledge ใน customer ToS ว่าลูกค้าเป็นผู้รับผิดชอบการใช้งานที่อาจกระทบ TikTok ToS
- **Tax/Revenue (TH)** — VAT registration เมื่อ revenue > threshold

[NOTE FOR PM: ทั้งหมดข้างต้นต้อง legal review รอบเดียวก่อน paid GA]

---

## 17. Rollout and Change Management *(Adapt-In: Enterprise)*

### Phase 0: POC (Complete)
- 5-10 phones in-house validation ✅

### Phase 1: MVP (Q3 2026, **~8 สัปดาห์** — AI-leveraged solo execution)
- Build out FR-1 to FR-19
- Onboard 2-3 design partners (friendly agencies)
- Free / heavily discounted access in exchange for feedback + case study
- Success gate: SM-3 ≥ 50% retention, SM-2 ≥ 90% command success, all design partners willing to be referenceable

### Phase 2: V1 GA (Q4 2026)
- Billing, scheduling, comment monitoring
- Self-serve signup + paid subscription open
- Marketing push to TH solo seller / multi-account reseller segment

### Phase 3: Scale + International (2027)
- Analytics, hybrid live, AI assist
- Vietnam / Indonesia / Philippines expansion

### Change communication
- **Internal:** weekly demo to exec sponsor; biweekly all-hands product update
- **Customer:** in-app changelog; email for breaking changes ≥ 30 วัน ล่วงหน้า
- **Companion App update:** prompt at next idle; force-update only for security-critical patches

---

## 18. Data Governance *(Adapt-In: Enterprise)*

- **Classification:**
  - User account data (config, device list) — confidential, encryption at rest
  - Broadcast Videos — confidential, encryption at rest, customer-deletable
  - Action logs — internal, retention 90 วัน
  - Aggregate telemetry — internal, anonymized, no expiration
- **Residency:** Bangkok region (TH data stays in TH)
- **Backup:** Backend DB daily snapshot + 7-day rolling
- **Right to deletion (PDPA):** delete User account + all associated data within 30 วัน of request

---

## 19. Assumptions Index

ทุก `[ASSUMPTION]` tag ใน document นี้ — รอ confirm จาก Pond ก่อน finalize:

- **§2.1** Primary persona profile (ตอง — solo TikTok seller) — รายละเอียดสมมติ ต้องปรับตาม design partner conversations
- **§4.2 FR-5** Video format: mp4 (h.264 + AAC), max 500 MB, max 60 min — ต้อง confirm
- **§4.2 FR-5** Transcoded target: 720p, 30fps — ต้อง confirm
- **§4.3 FR-9** Video switch ขั้นต่ำ black screen 2 วินาที — ต้องวัดจริง
- **§4.4 FR-14** OOS auto-handling default — ต้อง confirm flow
- **§4.5 FR-15** Session timeout 24 ชั่วโมง — ต้อง confirm
- ~~§4.6 FR-18 Trial duration~~ — CLOSED: no trial
- **§4.6 FR-19** Play Store distribution อาจไม่ได้ — ต้อง legal review
- **§4.1 FR-2** Signal strength = WebSocket ping latency — ต้อง confirm metric definition
- **§12** Cloudflare R2, Stripe/Omise, Resend/Postmark — vendor choices ต้อง confirm
- **§13.2** Backend hosted Bangkok region — ต้อง confirm vendor
- **§4.7 FR-20** Live Title char limit 100 — ต้อง verify TikTok actual limit
- **§4.7 FR-21** Live Caption char limit 500 — ต้อง verify TikTok actual limit
- **§4.8 FR-23** Banner upper limit 4 ตัวต่อ Broadcast Video — ต้อง verify จาก UX testing
- **§4.8 FR-25** Countdown end action (hide / replace / persist) — ต้อง confirm default behavior
- **§4.8 NFR** Smart Overlay verification (TikTok screen-share จับ SAW overlay, ไม่ block when overlay running) — ต้อง POC extension verify ก่อน commit MVP build
- **§4.8 FR-26** Banner data binding (price/stock from TikTok Shop) — ต้อง confirm data source availability (TikTok Shop API access)

---

## 20. Open Items for Phase-Gate Decision

ก่อน exec approve MVP, ตอบ:
1. Stakeholders & Approvals (§9) — แต่ละ role มี owner หรือยัง?
2. Customer ToS draft owner (Open Q #4)
3. Pricing model (Open Q #1) — ต้อง agree ก่อน design partner conversation
4. Play Store path (Open Q #3 / §4.6 FR-19) — sideload only หรือพยายาม Play Store?
5. Smart Overlay POC extension (§4.8 NFR) — go/no-go ก่อน MVP build start
6. TikTok Shop data API access (§4.8 FR-26) — ถ้าไม่มี access, Price/Stock Banner = manual entry only
