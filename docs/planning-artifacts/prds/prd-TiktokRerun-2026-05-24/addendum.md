# PRD Addendum — TiktokRerun

ส่วนนี้เก็บข้อมูลที่ Pond ให้มา หรือผม inferred ที่ **belongs downstream** (architecture, solution design, ops playbook) ไม่ใช่ PRD เอง — แต่จำเป็นต้องเก็บเพื่อไม่ให้ context หาย

---

## A. Rejected Alternatives (with rationale)

### A.1 MOD APK approach
- **Rejected because:** copyright infringement (TH พ.ร.บ. ลิขสิทธิ์ + พ.ร.บ. คอมพิวเตอร์ + DMCA), TikTok ToS violation, no Play Store distribution path, no VC fundable, brand association with malware
- **Implication:** Companion App ห้าม dynamic patching / hooking ของ TikTok app

### A.2 RTMP push with TikTok Stream Key
- **Rejected because:** TikTok ออก stream key เฉพาะ creator ระดับ ≥1,000 followers ผ่าน LIVE Studio (manual flow); ไม่มี API public; stream key rotate per session
- **Could revisit if:** TikTok Live Provider Partnership program เปิด

### A.3 Virtual Camera (broadcast video as if from physical camera)
- **Rejected because:** Android stock ไม่ support; ต้อง root + Magisk module; แตก fleet scalability

### A.4 Hardware audio loopback (jack→mic for audio routing)
- **Rejected because:** POC confirm ว่า volume control ทำงานในระบบ — system audio capture สำเร็จโดยไม่ต้องการ hardware loopback

### A.5 Desktop-only management (TikMatrix model)
- **Rejected because:** ลูกค้าต้องอยู่หน้าคอม; Operator productivity ลด; web-based เป็น differentiator หลัก

---

## B. Technical Architecture (reference)

PRD ไม่ commit tech stack — แต่ architecture draft ใน `technical-architecture-draft.md` เก็บ:
- 3-tier diagram (web → backend → device fleet)
- Tech stack proposal (Kotlin / Go / Next.js / Postgres / Redis / S3)
- 4 core flows in detail
- 6 hard tech questions (Q4.1-Q4.6) ที่ POC ผ่านแล้ว 3 (audio, broadcast, pin)

`bmad-create-architecture` ใน phase ถัดไปจะ commit final stack + database schema + API contracts

---

## C. Customer Site Logistics (ops detail)

ระดับ deployment 100 phones per customer:
- Power: ~300W ที่ 3W/phone average
- Network: 100 Mbps minimum (1 Mbps/live)
- Cooling: rack + fan, ambient < 28°C
- SIM (if not Wi-Fi): ~50K บาท/เดือน × 100

→ ใส่ใน customer onboarding playbook (ไม่ใช่ PRD)

---

## D. POC Validation Detail

POC validated 8 capabilities (อ้างอิง `system-overview.md` §9):
- Screen-share broadcast ✅
- Audio routing + volume control ✅
- Remote WebSocket control ✅
- Device pairing (QR) ✅
- Start/stop live automation ✅
- Pin/unpin product ✅
- Switch video on-demand ✅
- Multi-device concurrent control ✅

Open: long-run stability, recovery from disconnect, video switching latency — ต้อง measure ใน MVP

---

## E. Market Context (deeper)

อ้างอิง `market-research-tiktok-live-rerun.md`:
- Direct competitor TikMatrix: desktop only, engagement farm, ไม่มี broadcast/web/Shop → 3 gaps สำคัญที่เราเติม
- Live Rerun แบ่ง 2 ความหมาย: official Replay (30-day) vs Loop Live (gray area) → TiktokRerun = loop live โดย customer-acknowledged
- Thai TikTok Shop seller mobile-first → ตลาด primary

---

## F. Personas — Secondary Detail

นอก primary persona (พลอย — agency ops manager) มี:
- **In-house Brand Ops Lead** — 1-3 sub-brands, ทีมในบ้าน 2-3 คน
- **Reseller Network Owner** — 5-20 บัญชี personal, gray-area dropshipping

Detail เพิ่มจะใส่ใน UX research phase (`bmad-create-ux-design`)
