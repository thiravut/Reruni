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

### Q2. "Smart Overlay technique — ถ้า TikTok ตรวจเจอแล้ว block ทำยังไง?"

**A:** 3 layered mitigation:
1. **POC extension** (1-2 wks) ทดสอบ G1-G3 verification gates ก่อน commit MVP build
2. **Fallback path:** plain screen-share + overlay-only-during-pin (degraded UX แต่ functional)
3. **Selector versioning** + 24-48hr patch SLA สำหรับ TikTok UI update

**Backup:** technical-architecture-draft.md §3.5 (Smart Overlay verification + fallback)

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

### Q5. "ทำไมไม่ใช้ Virtual Camera แบบ VCAM/Magisk ที่ phone farm จีนใช้?"

**A:** เคยพิจารณา rejected:
1. **ต้องการ rooted phones** → ลูกค้า BYOD ยาก → ตลาดแคบ
2. **SafetyNet/Play Integrity** ของ TikTok 2026 ตรวจ root → ban risk สูงกว่า
3. **Setup time 30-60 นาที per phone** vs 5 นาที ของเรา
4. **Maintenance load สูงมาก** (Android × LSPosed × VCAM × TikTok version matrix)

Smart Overlay ของเราได้ quality เท่ากันโดยไม่ต้อง root → BYOD friendly

**Backup:** addendum.md §A.1 (rejected alternatives); decision log

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

ลูกค้าที่ต้องการ live commerce ops ไม่มี solution ที่ดี — เราเป็น first ในตลาด

**Backup:** market-research §8 (TikMatrix analysis)

---

### Q8. "3-phone Wi-Fi ADB tool ในไทยขายอยู่แล้ว — เขาจะลอกเราไหม?"

**A:** มี architectural moat ที่เลียนแบบยาก:
1. **Persistent WebSocket cloud architecture** — เขาเป็น PC desktop tool, ไม่มี cloud backend
2. **Web-anywhere control** — เขาต้องอยู่หน้า PC
3. **Mid-live commerce control** — เขา set-and-forget, เปลี่ยนระหว่าง live ไม่ได้
4. **Banner composition** — เขา lock content ใน video file

เขาจะลอกได้แต่ต้อง rebuild ทั้ง architecture = 6-12 เดือน effort เราใช้เวลานี้สร้าง brand + customer base + iterate

**Backup:** market-research §9 capability gap table

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

### Q11. "Revenue 6.3M ARR ใน 12 เดือน — believable?"

**A:** เป็น realistic case (ไม่ใช่ best case) Logic:
- 80 paying Users × avg 25 devices × 200 บาท = 400K MRR
- Customer acquisition rate ~7 Users/เดือน หลัง launch — ไม่ aggressive
- TH solo seller / multi-account reseller market มี ~5,000-20,000 ที่ run TikTok Shop หลายบัญชี = TAM พอเพียงสำหรับ 80 customers

Conservative case: 30 Users, 2.3M ARR ก็ยัง breakeven (server cost ต่ำมาก)

**Backup:** cost-analysis-gcp.md §7 Revenue projection

---

### Q12. "Pricing 3,990 บาท/10 devices — ไม่ถูกเกินไปหรือ?"

**A:** Sweet spot — ไม่ถูกเกิน, ไม่แพงเกิน:
- ถูกกว่า manual hire 10x (15-20K vs 3,990)
- แพงกว่า 3-phone tool 2x (1,000 vs 3,990) → justify ด้วย value 5x (web + banner + scale)
- Margin > 90% → flex ปรับ ±20% หลัง design partner feedback ได้

ที่ราคา 3,990 → 80 Users Starter ก็ทำ ~1.9M ARR ได้ → cover cost ทันที

**Backup:** cost-analysis-gcp.md §6 pricing structure + §App.B competitor comparison

---

### Q13. "ถ้า MVP fail — เสีย ~200K บาท จะคุ้มไหม?"

**A:** Risk-adjusted คุ้มอย่างมาก:
- **Investment:** ~200K (8 wk × Pond solo + Claude + infra + tools)
- **Downside:** เสีย dev cost — แต่ผมยังได้ learning + reuse code สำหรับ project อื่น
- **Upside scenario:** 6.3M ARR ใน 12 เดือน = ROI 2,000% Year 1
- **Failure modes ที่ early-detect ได้:**
  - Smart Overlay verification fail ใน wk 2 → pivot to plain screen-share, cost แค่ ~50K
  - Design partner ไม่ใช้ใน wk 6 → kill before V1 GA, เสียครึ่งเดียว (~100K)

**Key insight:** ที่ ~200K = ใช้เงิน 1 เดือนของ ARR target — risk เล็กมาก vs upside

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
- TH ตลาดมีลูกค้าศักยภาพ 10K-15K ราย — ไม่ต้อง expand ต่างชาติเพื่อ scale 27M ARR
- International / SEA = **not in roadmap** (per 2026-05-31 direction) — focus + ship ก่อน

**Backup:** release-roadmap.md (V1-V3 plan, no V4 SEA)

---

## E. Operational

### Q17. "1 คนทำเสร็จจริงๆ หรือ? หา hire ไหม?"

**A:** MVP = solo (Pond) ครับ — ไม่ต้อง hire
- POC validated velocity ของผม + Claude = 5x ของ baseline (4 วัน vs คาด 2-3 wk)
- MVP scope ~8-10x ของ POC → 6-8 wk timeline
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
| MVP investment | **~200K บาท** |
| MVP timeline | **8 สัปดาห์** |
| MVP team | **1 (Pond solo + Claude)** |
| Pond salary | 50K/เดือน |
| Claude cost | 6.5K/เดือน |
| Server cost @ 300 devices | 1,500-3,000 บาท/เดือน |
| Server cost ratio | 3-7% revenue |
| Starter pricing | 3,990 บาท / 10 devices |
| Server cost per device | 5-15 บาท (Tier A) |
| V1 12-mo ARR target | 6.3M บาท |
| Year 2 ARR potential | 27.3M บาท |
| Payback | **~1 เดือนหลัง paid GA** |
| ROI Year 1 | **1,500-2,000%+** |
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
