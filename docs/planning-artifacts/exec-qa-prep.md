# Executive Q&A Prep — TiktokRerun

**Purpose:** Anticipate questions exec จะถาม, เตรียมคำตอบที่ตรงและ confident
**Updated:** 2026-05-24

> Format: คำถาม → คำตอบสั้น → backup detail/source ในกรณีถูกเจาะ

---

## A. Technical Feasibility

### Q1. "POC ทำงานจริงๆ หรือแค่ demo ที่ scripted?"

**A:** ทำงานจริง POC validated 8 core capabilities บน production-like setup (5-10 phones, multi-hour stability test)
- Screen-share broadcast ✅
- Audio routing + volume ✅
- Remote WebSocket control ✅
- Device pairing QR ✅
- Start/stop live automation ✅
- Pin/unpin product ✅
- Switch video on-demand ✅
- Multi-device concurrent ✅

**Backup:** ดู system-overview §9 POC Findings; พร้อม live demo ในห้องประชุม

---

### Q2. "VCam Camera2 hijack — ถ้า TikTok app update แล้ว module break ทำยังไง?"

**A:** 3 layered mitigation:
1. **Modular design** — VCam LSPosed module + LSPatch shim แยกเป็น component, hook layer module เดียวที่ต้อง patch
2. **24-48hr rebuild SLA** — Pond + Claude rebuild + sign + publish APK ใหม่ ใน 1-2 วัน, customer auto-prompt ดาวน์โหลด
3. **Validated build pipeline** — Phase A-D autopilot script + APK patching/signing/upload pipeline shipped + tested

**Why VCam path beats Smart Overlay POC (deprecated):**
- Quality สูงกว่า (Camera2 hijack vs screen-share artifact)
- ไม่ต้อง root (LSPatch shim) — R3 Lite BYOD friendly
- Own-built module = no external dependency, no legal/IP risk

**Backup:** system-overview.md §9 (VCam DECIDED 2026-05-31); decision log

---

### Q3. "TikTok ที่จะ ban ลูกค้า — เรารับผิดชอบยังไง?"

**A:** Ban risk transferred ไปลูกค้าผ่าน Terms of Service ลูกค้า acknowledge ก่อนใช้ระบบ — เหมือน industry standard ของ gray-area B2B tools (TikMatrix, scraping tools, ad networks)
- เรา provide best-practice docs ลด ban risk
- Aggregate telemetry (anonymous) เพื่อ improve product
- ลูกค้า responsible สำหรับ content + account ของตัวเอง

**Backup:** PRD §13.2 Privacy, §17 Compliance; ToS draft = blocker pre-GA

---

### Q4. "Scale จาก 10 phones → 100 phones → 1,000 phones — architecture support ได้ไหม?"

**A:** Yes
- Go WebSocket gateway handle 10K+ concurrent connections บน $5 VPS
- Bottleneck แรกที่จะเจอคือ Postgres QPS (mitigate ด้วย Redis cache)
- Migration path Tier A → B ที่ 500 devices, → C ที่ 5,000 devices (ถ้ามี enterprise demand)

**Backup:** cost-analysis-gcp.md §4 (cost per device at scale)

---

### Q5. "ทำไมไม่ใช้ Magisk + VCAM แบบ phone farm จีน?"

**A:** Magisk-based VCAM ต้อง root — แต่เรา**ได้ VCAM แบบไม่ต้อง root** ผ่าน LSPatch:

**สถาปัตยกรรมที่เลือก (2026-05-31):**
- **VCam Camera2 hijack** — own-built LSPosed module
- **LSPatch shim** (non-root Xposed) แทน Magisk+LSPosed → ไม่ต้อง root device
- **R3 Lite (BYOD)** — ลูกค้าใช้ phone ตัวเองได้, ไม่ต้องส่งเครื่อง
- Concierge tier (advanced) ยังคงเสนอ rooted devices สำหรับ feature เพิ่มเติม

**Why this beats Magisk path:**
1. BYOD friendly — ลูกค้าไม่ต้อง root (ลด market friction)
2. ลด ban risk จาก SafetyNet/Play Integrity detection (no root signal)
3. Setup time ~5 นาที (download APK + scan QR + install) vs 30-60 นาที rooted setup
4. Maintenance pipeline modular — module versioning + 24-48hr rebuild SLA

**Validated:** Samsung A15 5G Android 16, end-to-end broadcast working

**Backup:** decision log 2026-05-31; system-overview.md §9

---

## B. Market & Competition

### Q6. "ใครคือลูกค้าจริง? เคยคุยกับเขาแล้วหรือยัง?"

**A:** Primary persona = **Solo TikTok Shop seller** ที่ run หลายบัญชี (อายุ 24-38, มี 3-15 TikTok Shop accounts, full-time หรือ side-hustle, คนเดียวเป็น everything)
- [⚠️ ถ้ายังไม่ได้คุยกับลูกค้าจริง → ตอบตรง: "เรามี hypothesis ที่ derived จาก competitive research; design partner phase 2-3 ราย จะ validate ใน MVP beta"]

**Backup:** PRD §2 Target User; market-research market context

---

### Q7. "TikMatrix มีอยู่แล้ว ราคา $29-149 — ลูกค้าทำไมต้องมาใช้เรา?"

**A:** TikMatrix อยู่คนละตลาด — เป็น engagement farm (watch, like, comment) ไม่ใช่ broadcast/commerce tool
- TikMatrix ✅ engagement, ❌ broadcast, ❌ commerce, ❌ web
- เรา ✅ broadcast, ✅ commerce, ✅ banner, ✅ web

ลูกค้าที่ต้องการ live commerce ops ไม่มี solution ที่ดี — เราเป็น first ใน TH ที่มี web control

**Backup:** market-research §8 (TikMatrix analysis)

---

### Q7b. "SamuraiLive ขาย 299 บาท/device เท่ากันเลย — เราจะแข่งยังไง?"

**A:** ราคาเท่ากันก็จริง แต่ขายคนละ segment + product:

**SamuraiLive:**
- App-only (ไม่มี web control)
- 1 user 1 phone (ไม่มี multi-device fleet)
- Magisk + LSPosed = **ต้อง root**
- ไม่มี multi-tenant SaaS (no admin / billing / quota)
- ไม่มี dynamic banner composition

**Reruni (เรา):**
- ✅ Web control plane — operator คุม 10-100 phones จาก laptop
- ✅ Multi-device fleet management
- ✅ **No root required** (LSPatch แทน Magisk)
- ✅ Multi-tenant SaaS (Stripe billing, admin backoffice, quota enforcement built-in)
- ✅ Dynamic banner overlay (countdown, price, promo composite)

**Positioning:** ราคาเท่ากัน → ลูกค้าตัดสินใจ on capability + scale ไม่ใช่ on price
- SamuraiLive ลูกค้า = single seller 1-3 phones, hands-on operator
- Reruni ลูกค้า = multi-account seller 5-100 phones, operator คุมจาก laptop

Reruni capture market ที่ SamuraiLive serve ไม่ได้ (ลูกค้าที่ scale เกิน 3-5 phones)

**Backup:** market-research §3 SamuraiLive entry; Slide 8 capability matrix

---

### Q8. "3-phone Wi-Fi ADB tool ในไทยขายอยู่แล้ว + SamuraiLive แล้ว — เขาจะลอกเราไหม?"

**A:** มี architectural moat ที่เลียนแบบยาก:
1. **Persistent WebSocket cloud architecture** — 3-phone tool เป็น PC desktop, SamuraiLive เป็น standalone app, ทั้งคู่ไม่มี cloud backend
2. **Web-anywhere control** — เขาต้องอยู่หน้า PC / phone
3. **Multi-tenant SaaS infrastructure** (Stripe billing + admin backoffice + onboarding wizard + quota enforcement) — เขาไม่มี
4. **Mid-live commerce control** — 3-phone tool set-and-forget
5. **Banner composition** — เขา lock content ใน video file
6. **No-root (LSPatch)** — SamuraiLive ใช้ Magisk = ต้อง root

เขาจะลอกได้แต่ต้อง rebuild ทั้ง architecture + SaaS layer = 6-12 เดือน effort เราใช้เวลานี้สร้าง brand + customer base + iterate

**Backup:** market-research §3 + Slide 8 capability matrix

---

### Q9. "ถ้า TikTok ออก partner program — เราถูก disrupt ทันที?"

**A:** Real risk ระยะกลาง (1-2 ปี) Mitigation:
1. **First-mover** customer acquisition + brand ก่อน
2. **Apply เป็น Live Provider partner** เมื่อ program เปิด
3. **Stack ของเรามี layers อื่นที่ value:** fleet management, banner, web UX — ไม่ใช่แค่ broadcast technique

ถ้า worst case: pivot ไปเป็น "Official-API partner edition" — maintain customer base

**Backup:** PRD §10 Risk register

---

## C. Financial

### Q10. "ตัวเลข server cost 5-15 บาท/device — เป็นจริงหรือ optimistic เกินไป?"

**A:** เป็นจริง ที่ Tier A (Hetzner + Cloudflare R2) เพราะ:
- Cloudflare R2 = $0 egress (eliminate biggest variable)
- Hetzner VPS handle 1000+ WebSocket easily
- Neon/Upstash serverless DB scale ตาม usage จริง

Worst case Tier B = 16 บาท/device, ยังถูกพอ — server cost ratio < 10%

**Backup:** cost-analysis-gcp.md §3 Tier A detail breakdown

---

### Q11. "Revenue 2.15M ARR ใน 12 เดือน — believable?"

**A:** เป็น realistic case (ไม่ใช่ best case) Logic:
- 80 paying Users × avg 7.5 devices × 299 บาท = 179K MRR
- Customer acquisition rate ~7 Users/เดือน หลัง launch — ไม่ aggressive
- TH solo seller / multi-account reseller market มี ~5,000-20,000 ที่ run TikTok Shop หลายบัญชี = TAM พอเพียงสำหรับ 80+ customers

Breakeven case: ~31 Users (234 devices) ที่ V1 fixed cost ~70K/month → 3-5 เดือนหลัง launch

**Backup:** cost-analysis-gcp.md §7 Revenue projection

---

### Q12. "Pricing flat 299 บาท/device — ทำไมเลือก flat?"

**A:** Match industry benchmark + simplicity:
- **Match competitor 1:1** — 3-phone tool ในไทยราคา 299/device → ลูกค้าเข้าใจราคาทันที, ไม่ต้อง educate
- **Linear scaling** — ลูกค้าเพิ่ม device 1 ตัว = +299, ไม่ต้อง jump tier
- **Onboarding friction ต่ำ** — ไม่มี "เลือก plan ไหน" ตัดสินใจ
- **Revenue ที่ scale สูงกว่า tier** — 100-device customer จ่าย 29,900 (vs 19,990 ใน Pro tier เก่า)
- Margin > 99% at scale → infra cost <1% revenue

ที่ราคา 299 flat → 80 Users × 7.5 devices ทำ ~2.15M ARR → cover cost ทันที

**Backup:** cost-analysis-gcp.md §6 pricing structure (DECIDED 2026-05-23) + presentation Slide 12

---

### Q13. "ถ้า MVP fail — เสีย ~150K บาท จะคุ้มไหม?"

**A:** Risk-adjusted คุ้มอย่างมาก — และ ~90% MVP shipped แล้ว (ไม่ใช่ pre-build risk):
- **Investment:** ~120-150K (~4 wk total × Pond solo + Claude + infra + tools), 90% spent already
- **Remaining risk:** Banner Tier 2 + design partner cycle + Stripe live mode + APK production pipeline
- **Downside:** ถ้า design partner ไม่ adopt → kill before paid GA, ใช้ไปจริง ~120K
- **Upside scenario:** 2.15M ARR ใน 12 เดือน = ROI ~930% Year 1
- **Failure modes ที่ early-detect ได้:**
  - Ban rate สูงเกินใน design partner cycle → tighten customer ToS + best-practice guide, no extra cost
  - Design partner ไม่ใช้ใน wk 4 → kill before V1 GA, used ~130K
  - VCam module breaks ตอน TikTok update → 24-48hr rebuild + customer auto-prompt update

**Key insight:** ที่ ~150K = ใช้เงิน <1 เดือนของ ARR target — risk เล็กมาก vs upside

**Backup:** PRD §17 Rollout phase-gates

---

## D. Strategic / Long-term

### Q14. "Exit strategy?"

**A:** Multiple paths in 2-3 ปี:
1. **Strategic acquirer:** Shopee, Lazada, JD Central — ต้องการ TikTok Shop integration tool
2. **Vertical SaaS roll-up:** TH e-commerce SaaS consolidation
3. **TikTok itself:** ถ้า expand into commerce tooling
4. **Self-sustaining:** 27.3M ARR Year 2, profitable, no exit needed

[ASSUMPTION: ต้อง confirm exec appetite — exit-driven vs sustained business]

**Backup:** ไม่อยู่ใน PRD แต่ implicit ใน roadmap

---

### Q15. "ทำไมไม่ build เป็น mobile-first SaaS app แทน web?"

**A:** User persona ต้องการ multi-screen workflow:
- View 50-100 devices พร้อมกัน → desktop ดีกว่า
- Type metadata (title, caption, hashtag) — keyboard ดีกว่า touch
- Mobile-responsive sufficient สำหรับ monitoring + restart on-the-go (FR-17)

Native mobile app = Phase 2+ ถ้า demand ชัด

**Backup:** PRD §4.5 FR-17

---

### Q16. "ทำไม TH-only?"

**A:** Founder-market fit + market focus:
- เราเข้าใจ TikTok ecosystem ในไทยลึก
- TH = 70M population, TikTok Shop GMV โต — meaningful TAM พอเดียว
- Localized UX (Thai language, baht, Shopee/Lazada awareness) เป็น advantage vs global tools
- TH ตลาดมีลูกค้าศักยภาพ 10K-15K ราย — ไม่ต้อง expand ต่างชาติเพื่อ scale 8M+ ARR
- International / SEA = **not in roadmap** (per 2026-05-31 direction) — focus + ship ก่อน

**Backup:** release-roadmap.md (V1-V3 plan, no V4 SEA)

---

## E. Operational

### Q17. "1 คนทำเสร็จจริงๆ หรือ? หา hire ไหม?"

**A:** MVP = solo (Pond) ครับ — ไม่ต้อง hire และ ~90% shipped แล้ว
- POC validated velocity = 5x ของ baseline (4 วัน vs คาด 2-3 wk)
- MVP shipped ~90% ใน 2 wk vs plan 8 wk = **velocity 8-10x** (proof จริง ไม่ใช่ estimate)
- Pond มี full-stack expertise + ownership + domain knowledge → ไม่ต้อง onboard

**V1 phase (Q4):** hire 1 full-stack engineer เพื่อ reduce single-person risk + เพิ่ม velocity

**ถ้าโดน push:** "ทำไมไม่ hire ตั้งแต่ MVP เพื่อเร็วขึ้น?" → ตอบ: validation phase ผมต้อง iterate กับลูกค้าจริง ไม่ต้องการ coordination overhead; AI ทำให้เราไม่ต้อง trade-off speed กับ team size

---

### Q18. "Customer support model?"

**A:** MVP phase:
- Founder + PM ทำ direct support (3 design partners)
- Slack/Line direct channel
- 4hr business-hour response SLA

V1 phase:
- 1 dedicated CS hire
- Support tool (Crisp/Intercom)
- Ticket system + docs portal
- 1hr critical / 4hr standard SLA

**Backup:** PRD §11 Operational Requirements

---

### Q19. "Legal risk — bullet-proof แล้วยัง?"

**A:** Not yet, but tractable:
- ToS draft = blocker before paid GA (Open Q #4)
- TH PDPA: standard SaaS pattern, achievable
- TH Computer Crime Act: A1 positioning + no unauthorized access design
- TikTok ToS: customer ToS shifts liability; เราเป็น tool provider เหมือน OBS/Streamlabs
- Need: legal review รอบเดียว ~50-100K บาท

**Backup:** PRD §16 Compliance and Regulatory

---

### Q20. "ถ้า Pond ออกจาก project — ใครเอา ownership ต่อ?"

**A:** [ต้องวางแผน succession]
- Document everything ใน planning-artifacts ครบ (DONE — มี 5 docs + PRD + decision log)
- Senior engineer หรือ co-founder กลายเป็น CTO
- Investor/board มี veto + influence

[ACTION: confirm succession plan ก่อน paid GA]

---

## F. Likely "Gotcha" Questions

### Q21. "เคยมี product แบบนี้ใน global market แล้วทำไมเขาไม่ทำในไทย?"

**A:** มี player แบบ engagement farm (TikMatrix) แต่:
- Live commerce ops platform จริงๆ ยังไม่มีใครทำใน scale ใดๆ
- จีนทำในวง close (ไม่ export), TH market ขนาดเล็กเกินไปสำหรับ global player
- TH-specific (TikTok Shop integration, Thai language, payment) เป็น barrier ที่ดี

---

### Q22. "ตัวเลข live commerce growth ที่ quote มาจากไหน?"

**A:** [ต้องเตรียม sources จริง]
- TikTok newsroom TH (ห้องข่าว)
- Hypotheses based on observed TikTok algorithm behavior
- ⚠️ **ถ้าไม่มีตัวเลขจาก primary source: ตอบตรง "based on industry observation + competitive research; design partners จะให้ ground-truth metrics"**

[ACTION: เตรียม 2-3 specific TikTok TH stats ก่อนเข้าห้อง]

---

### Q23. "ทำไมต้องเป็น Android only? iOS ทำไมไม่ได้?"

**A:**
- TikTok screen-share API บน iOS ต่างกัน (broadcast extension required)
- iOS App Store policy strict กว่า — likely reject
- Android = ตลาด > 70% ใน TH, ครอบคลุม majority
- iOS = TBD Phase 2+

---

### Q24. "ค่าใช้จ่าย customer-side (phone, SIM, cooling) ลูกค้ารับเอง?"

**A:** Yes
- Phones, SIM, electricity, cooling = customer responsibility (เป็น CAPEX/OPEX ของเขา)
- เราขาย software-as-a-service
- เราจัด guidance/playbook สำหรับ optimal setup
- Phase 2+: optional "TiktokRerun-ready phone" partnership กับ phone reseller

**Backup:** system-overview §8 Customer site logistics

---

### Q25. "ทำไมไม่ partner กับ TikTok officially?"

**A:** จะลอง — แต่ไม่ depend on it:
- TikTok Marketing Partner program มีอยู่
- TikTok Live Provider program (B2B) ก็มี
- เราจะ apply พร้อม build ตลาด — ถ้า approved = great, ไม่ approved = ยังอยู่ได้
- Partner status ใช้เวลา 6-12 เดือน, build product ไม่รอ

---

## Cheat Sheet — Numbers to memorize

| Metric | Number |
|---|---|
| MVP investment | **~120-150K บาท** (revised down from 200K) |
| MVP timeline | **~4 สัปดาห์ end-to-end** (~2 wk done + ~2 wk remaining) |
| MVP team | **1 (Pond solo + Claude)** |
| Pond salary | 50K/เดือน |
| Claude cost | 6.5K/เดือน |
| Server cost @ 300 devices | 1,500-3,000 บาท/เดือน |
| Server cost ratio | <1% revenue at scale |
| Pricing model | **Flat 299 บาท/device/month** (no tier) |
| Server cost per device | 1.78 บาท (hybrid GCP + R2 at 2K users) |
| V1 12-mo ARR target | 2.15M บาท (80 users × 7.5 devices × 299 × 12) |
| Year 2 ARR potential | 8.07M บาท (300 users × 7.5 × 299 × 12) |
| At-scale ARR (2K users) | 53.8M บาท |
| Breakeven | ~31 customers (3-5 เดือนหลัง launch) |
| ROI Year 1 | **~770%** (2.15M ARR / 280K investment) |
| POC velocity proof | 4 วัน vs คาด 2-3 wk (5x) |
| POC capabilities validated | 8 of 10 |
| FRs in PRD | 26 |
| Open Questions remaining | 7 (none blockers) |
| Legal review | deferred to V1 phase (~50-100K) |

---

## Reminders for the meeting

1. **Lead with the pitch (1 paragraph)** ก่อนเข้า detail
2. **ถ้าถูกถามที่ไม่รู้** — ตอบ "ดี — เป็น open question ที่จะ resolve ใน [phase X]" ไม่ใช่ทำท่ารู้
3. **อย่าโต้แย้ง** เมื่อ exec บอก "ผม concern เรื่อง X" — ฟัง, acknowledge, propose mitigation
4. **End on ask** ที่ชัดเจน — 7 decisions ใน briefing §8
5. **Backup docs ครบใน planning-artifacts/** — ถ้าถูกเจาะ deep, เปิดได้ทันที
