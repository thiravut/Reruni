# TiktokRerun — Presentation Script
**สำหรับ Pond ใช้พรีเซนต์ exec**

**Total time:** 25-30 นาที walkthrough + 30-45 นาที Q&A
**Format:** 16 main slides + 8 appendix slides
**Language:** Thai (เปลี่ยนได้ตาม audience)

---

## วิธีใช้ doc นี้

แต่ละ slide มี 3 ส่วน:
- **[ON SLIDE]** — สิ่งที่แสดงบนจอ (bullets / charts)
- **[SAY]** — สิ่งที่พูด (script เกือบ verbatim)
- **[TIME]** — เวลาคาดหวัง

อ่าน [SAY] ไม่ต้อง verbatim — ใช้เป็น guide เปลี่ยนคำเป็นภาษาตัวเองได้

---

# 🎬 MAIN PRESENTATION (16 slides, ~25 min)

---

## Slide 1: Title
**[ON SLIDE]**
> # TiktokRerun
> ## Live Commerce Ops Platform for TikTok Shop
> Pond — May 2026

**[SAY]**
> "สวัสดีครับ วันนี้ผมจะ present project ที่ชื่อ TiktokRerun
> เป็น platform ที่ทำให้ทีม ops 1-2 คน คุม TikTok Live หลายสิบ หลายร้อยร้านได้จาก web เดียว
> Goal ของ meeting วันนี้คือขอ approval ให้เริ่ม MVP build phase 8 สัปดาห์"

**[TIME]** 30 วินาที

---

## Slide 2: The Ask (lead with the ask)
**[ON SLIDE]**
> ## What we're asking today
> - **Approve MVP build:** **~200K บาท × 8 สัปดาห์**
> - Team: **Pond solo full-stack + Claude (50K salary)**
> - Infra: 5-10K บาท/เดือน (Tier A scrappy)
> - Target: 2-3 paying design partners ที่ end of MVP
> - Legal review → deferred to V1 (before paid GA)

**[SAY]**
> "ขอตรงประเด็นก่อน — สิ่งที่ผมขอวันนี้คือ approval งบ ~200K บาท สำหรับ MVP 8 สัปดาห์
> ผม solo full-stack เอง + ใช้ Claude เป็น AI co-pilot
> เงินเดือนผม 50K + Claude 6.5K + infra + tools รวมเบ็ดเสร็จ ~200K
> Legal review จะทำตอน V1 ก่อนเปิด paid GA — MVP เราใช้ informal agreement กับ design partner ไปก่อน
> ผลลัพธ์ที่จะส่งกลับมา: paying design partner 2-3 ราย พร้อม case study
> ทำไม 1 คน + AI พอ? เพราะ POC เพิ่งพิสูจน์ว่าผมทำงาน velocity 5x ของ baseline (4 วัน vs คาด 2-3 wk)
> รายละเอียดทำไม approve ทำไมตอนนี้ ผมจะ walkthrough ใน 25 นาทีถัดไป"

**[TIME]** 1 นาที

---

## Slide 3: The Opportunity
**[ON SLIDE]**
> ## ทำไม TikTok Live Commerce ตอนนี้?
> - TikTok Shop TH เข้า scale phase — SME ย้ายจาก Shopee/Lazada
> - Live conversion สูงกว่า static feed หลายเท่า
> - TikTok algorithm prioritize creator ที่ live บ่อย
> - **"Live 24/7" กลายเป็น competitive necessity**

**[SAY]**
> "Context สั้นๆ ก่อน — TikTok Shop ในไทยช่วงปีนี้โตเร็วมาก
> Seller ระดับ SME ที่เคยขายบน Shopee/Lazada เริ่มย้ายมาเพราะ conversion rate สูงกว่า
> ที่สำคัญคือ algorithm ของ TikTok ตอนนี้ให้ priority กับร้านที่ live บ่อย
> ไม่ live = ไม่มี distribution = ไม่มียอด
> 'Live 24/7' จึงไม่ใช่ option แต่เป็น necessity"

**[TIME]** 1.5 นาที

---

## Slide 4: The Pain Point
**[ON SLIDE]**
> ## แต่ "live 24/7" บีบ seller รายเล็ก-กลางอย่างหนัก
>
> | Solution วันนี้ | Gap |
> |---|---|
> | จ้างคนคุม | 15-20K/คน × หลายร้าน = แพง |
> | 3-phone Wi-Fi tool | ไม่มี web, no mid-live control, max 3 |
> | TikMatrix | engagement farm, ไม่ broadcast |
> | OBS desktop | 1 device/PC, ต้อง stream key |
> | MOD APK | illegal, malware, ban risk |

**[SAY]**
> "ตลาดมี solution แต่ทุกตัวขาด:
> จ้างคน — แพงเกินสำหรับร้านระดับ SME
> Tool 3-phone ในไทยที่ขายกันอยู่ — broadcast ได้แต่คุมระหว่าง live ไม่ได้, ไม่มี web, scale ไม่เกิน 3 เครื่อง
> TikMatrix global — focus engagement ไม่ใช่ broadcast/commerce
> OBS + LIVE Studio — pro setup แต่ 1 device/PC, ต้อง follower 1,000 ขึ้นไป
> MOD APK — ผิดกฎหมาย, malware, account ban
>
> ช่องว่าง: **Live Commerce Ops Platform — broadcast + commerce control + multi-device + web** — ยังไม่มีใครเล่น"

**[TIME]** 2 นาที

---

## Slide 5: Our Solution
**[ON SLIDE]**
> ## TiktokRerun — 5 capabilities ที่แก้ตรงจุด
> 1. **Web control plane** — คุม 100+ phones จาก web anywhere
> 2. **Smart Overlay broadcast** — video + banner เป็น overlay บน TikTok
> 3. **Mid-live commerce** — pin/unpin/switch product real-time
> 4. **Dynamic Banner** ⭐ — countdown, price, promo เปลี่ยนจาก web
> 5. **Persistent connection** — fleet always reachable

**[SAY]**
> "TiktokRerun แก้ด้วย 5 capability หลัก:
> หนึ่ง — Web control plane: ทีม ops คนเดียวคุมโทรศัพท์ร้อยเครื่องจาก browser anywhere ได้
> สอง — Smart Overlay: เทคนิคที่ทำให้ video และ banner ของเรา broadcast เหมือนเป็น camera live โดยไม่ต้อง root เครื่อง
> สาม — Mid-live commerce: เปลี่ยนสินค้าที่ปักตะกร้าระหว่าง live ได้จาก web — competitor tool ทำไม่ได้
> สี่ — Dynamic Banner — นี่คือ killer feature ของเรา — เปลี่ยน promo, countdown, ราคา ทับ video real-time โดยไม่ต้องตัดวิดีโอใหม่
> ห้า — Persistent WebSocket — เครื่อง reachable ตลอด ไม่ขาดเมื่อเครื่องย้ายเครือข่าย"

**[TIME]** 2.5 นาที

---

## Slide 6: How It Works
**[ON SLIDE]**
> ## Customer workflow
> ```
> 1. สมัครเว็บ → ยอมรับ ToS → เลือก tier → ใส่ payment (Stripe)
> 2. ติดตั้ง APK บน Android phone
> 3. Scan QR pair → device online
> 4. Upload video + ตั้ง title/caption/hashtag/pin product
> 5. Start Live → phone broadcast เอง
> 6. ระหว่าง live: switch video/product/banner จาก web
> ```

**[SAY]**
> "Workflow ของลูกค้าเรียบมาก:
> สมัครผ่านเว็บ → ติดตั้ง APK → scan QR → upload video → กด start
> เครื่องเริ่ม live เอง broadcast วิดีโอเป็น loop
> ระหว่าง live operator สามารถเปลี่ยน video, ปักตะกร้า, อัปเดต banner ได้จาก web ตลอด
> ไม่ต้องสัมผัสเครื่อง"

**[TIME]** 1.5 นาที

---

## Slide 7: Architecture & Technical Moat
**[ON SLIDE]**
> ## ทำไมเราเลียนแบบยาก
>
> | Capability | คู่แข่ง 3-phone | TikMatrix | TiktokRerun |
> |---|---|---|---|
> | Web-anywhere | ❌ | ❌ | ✅ |
> | Mid-live control | ❌ | ❌ | ✅ |
> | Switch product live | ❌ | ❌ | ✅ |
> | Dynamic Banner | ❌ | ❌ | ✅ |
> | Scale 100+ phones | ❌ | ✅ | ✅ |
> | TikTok Shop integration | ❌ | ❌ | ✅ |

**[SAY]**
> "Architecture ของเราต่างจาก commodity tools:
> ตัว 3-phone ในไทยเป็น PC desktop tool ที่ลูกค้าต้องอยู่หน้าเครื่อง
> เมื่อโทรศัพท์ย้ายไป 5G เขาเสีย connection ทันที = pin product ไม่ได้, restart ไม่ได้
> ของเรา WebSocket persistent — เครื่อง reachable ตลอด คุมจาก web anywhere
> นี่เป็น fundamental capability ที่เลียนแบบไม่ได้ในระยะสั้น — ต้อง rebuild ทั้ง architecture = 6-12 เดือน effort"

**[TIME]** 2 นาที

---

## Slide 8: POC Validated
**[ON SLIDE]**
> ## POC: 8 of 10 capabilities validated ✅
>
> - ✅ Screen-share broadcast
> - ✅ Audio routing + volume control
> - ✅ Remote WebSocket control
> - ✅ Device pairing via QR
> - ✅ Start/stop live automation
> - ✅ Pin/unpin product
> - ✅ Switch video on-demand
> - ✅ Multi-device concurrent control
> - 🟡 Smart Overlay verification (POC extension 1-2 wk)
> - 🟡 Banner rendering at 30fps stable

**[SAY]**
> "POC ของเราพิสูจน์ technical feasibility แล้ว 8 จาก 10 capability หลัก
> ที่เหลือ 2 ข้อ — Smart Overlay verification และ Banner rendering ที่ 30fps — เป็น POC extension อีก 1-2 สัปดาห์
> มี fallback path ถ้าไม่ผ่าน gate
> เพราะฉะนั้น technical risk หลักถูกขจัดแล้ว — เราไม่ได้ขาย idea ที่ไม่รู้ทำได้หรือเปล่า"

**[TIME]** 1.5 นาที

---

## Slide 9: Live Demo
**[ON SLIDE]**
> ## Live Demo (5 นาที)
>
> 1. Pair phone via QR
> 2. Upload video + set live metadata
> 3. Start live
> 4. Pin product mid-live
> 5. Switch video without restart

**[SAY]**
> "ขอ 5 นาทีโชว์จริง — มีเครื่อง phone กับ laptop พร้อมแล้ว
> [เปิด web dashboard]
> [กด Add device → QR ปรากฏ]
> [scan ด้วย phone → device online ใน 5 วินาที]
> [upload วิดีโอ → ตั้ง title 'Demo Live'] 
> [กด Start Live → 15 วินาทีต่อมา TikTok live online]
> [pin SKU จาก dropdown → 5 วินาทีต่อมาผู้ชมเห็น anchor update]
> [switch video → smooth transition]"

**[TIME]** 5-7 นาที (รวม live demo)

> ⚠️ **PREP:** ต้องซ้อม demo ให้ smooth ก่อน — ถ้าเครื่องค้าง = lose credibility
> ⚠️ **BACKUP:** มี video recording ของ demo เป็น backup ถ้า live ใช้ไม่ได้

---

## Slide 10: Business Model
**[ON SLIDE]**
> ## Pricing — 4-tier subscription
>
> | Tier | Devices | บาท/เดือน |
> |---|---|---|
> | **Starter** | up to 10 | **3,990** |
> | Growth | up to 30 | 8,990 |
> | Pro | up to 100 | 28,990 |
> | Enterprise | 100+ | quote |
>
> - ไม่มี free trial — pay upfront
> - 20% annual discount

**[SAY]**
> "Pricing เรา 4-tier subscription
> Starter 3,990 บาท สำหรับ 10 phones — entry point ที่ลูกค้ารายเล็กเข้าถึงได้
> ถูกกว่าจ้างคน 10 เท่า แพงกว่า tool พื้นฐานเล็กน้อยแต่ value 5 เท่า
> ไม่มี free trial — ลูกค้าจ่ายตั้งแต่เริ่ม → revenue ตั้งแต่ day 1 + filter serious customers"

**[TIME]** 1.5 นาที

---

## Slide 11: Unit Economics
**[ON SLIDE]**
> ## Server cost = 5-15 บาท/device (Tier A)
>
> | Stack | บาท/เดือน @ 300 devices | บาท/device |
> |---|---|---|
> | **Tier A (Hetzner + Cloudflare R2)** | **1,500-3,000** | **5-15** |
> | Tier B (DO managed) | 5-15K | 16-50 |
> | Tier C (GCP enterprise) | 18-29K | 60-145 |
>
> **Server cost ratio: 3-7% revenue → gross margin > 90%**

**[SAY]**
> "ตัวเลขสำคัญ — server cost
> ที่ Tier A scrappy stack: Hetzner + Cloudflare R2 — เราจ่าย $0 egress = ตัด cost ที่ใหญ่ที่สุดออก
> ผลลัพธ์: server cost 5-15 บาท/device → server cost ratio แค่ 3-7% ของ revenue
> Gross margin > 90% — vertical SaaS leader level
> เก็บไว้ reinvest ใน product, customer acquisition, หรือ profit"

**[TIME]** 1.5 นาที

---

## Slide 12: Revenue Projection
**[ON SLIDE]**
> ## Revenue trajectory
>
> | Stage | Users | Devices | MRR | ARR |
> |---|---|---|---|---|
> | V1 6-mo | 30 | 600 | 192K | 2.3M |
> | **V1 12-mo** | **80** | **2,000** | **524K** | **6.3M** |
> | Year 2 | 300 | 9,000 | 2.28M | 27.3M |
>
> **Payback: ~1 เดือนหลัง paid GA (ที่ ~200K investment + ~50-100K legal V1)**

**[SAY]**
> "Revenue projection — realistic case (ไม่ใช่ best case)
> ปีแรกหลัง launch: 80 Users จ่ายเงิน = 6.3 ล้าน ARR
> ที่ investment เริ่มต้น 200K — payback แค่ 1 เดือนหลัง GA
> ปี 2: 300 Users = 27.3 ล้าน ARR
> ที่ราคา 3,990 บาท/Starter customer แค่ Starter 100 รายก็ได้ 2.4M ARR ครอบคลุม cost หลายเท่า"

**[TIME]** 1.5 นาที

---

## Slide 13: Roadmap
**[ON SLIDE]**
> ```
> 2026 Q2 │ POC                          ✅ Complete
> 2026 Q3 │ V1 MVP build                ◀── ขอ approval ที่นี่
> 2026 Q4 │ V1.5 Stability + QoL
> 2027 Q1 │ V2 Multi-profile + Scheduling
> 2027 Q2 │ V3 AI + Hybrid (exploratory)
> ```

**[SAY]**
> "Roadmap 12 เดือน
> POC เสร็จแล้ว
> Q3 ปีนี้ — MVP build — ที่ผมขอ approval วันนี้
> Q4 — V1.5 stability + quality of life
> ปี 2027 — V2 multi-profile rotation, V3 AI features (exploratory)
> ไม่มี SEA expansion ในแผนแล้ว"

**[TIME]** 1 นาที

---

## Slide 14: Top Risks
**[ON SLIDE]**
> ## Risk register
>
> | Risk | Severity | Mitigation |
> |---|---|---|
> | TikTok UI update break Accessibility | High | Selector versioning + 24-48hr patch SLA |
> | Mass account ban → churn | High | Customer ToS shifts liability + best-practice docs |
> | Smart Overlay verify fail | Medium | POC extension + fallback to plain screen-share |
> | Legal challenge from TikTok | Medium | A1 positioning, no public ToS bypass claim |

**[SAY]**
> "Risk หลัก 4 ข้อ — ทั้งหมดมี mitigation ที่ pragmatic:
> TikTok update UI — เรามี selector versioning + patch SLA 24-48 ชม.
> Account ban — ลูกค้ารับผ่าน ToS เหมือน industry standard
> Smart Overlay fail — POC extension ก่อน commit + fallback path
> Legal — A1 positioning + legal review ก่อน paid GA
> ทั้งหมดเป็น manageable risks ไม่ใช่ existential threats"

**[TIME]** 2 นาที

---

## Slide 15: 7 Decisions Asked
**[ON SLIDE]**
> ## ขอ approval 7 ข้อ
>
> 1. **MVP phase** (~200K × 8 สัปดาห์) ⭐
> 2. Team: Pond solo + Claude (50K salary)
> 3. Infra budget 5-10K/mo cap (Tier A scrappy)
> 4. Positioning: Live Commerce Ops Platform
> 5. Design partners 2-3 friendly agencies
> 6. Legal review deferred to V1 phase (~50-100K, before paid GA)
> 7. Hosting Bangkok-first

**[SAY]**
> "สรุปสิ่งที่ขอ — 7 decisions:
> ใหญ่สุดคือข้อ 1 — approve MVP build ~200K บาท × 8 สัปดาห์
> ผมเป็น solo full-stack + Claude — รัน lean ที่สุด
> Legal review เลื่อนไป V1 ก่อน paid GA (~50-100K)
> ทั้งหมดอยู่ใน executive briefing ที่ผมส่งให้ก่อนหน้านี้
> ถ้ามีข้อไหนต้องเจาะ ผม walk through ได้"

**[TIME]** 1.5 นาที

---

## Slide 16: TL;DR & Next Steps
**[ON SLIDE]**
> ## TL;DR
> > Approve ~200K × 8 weeks (Pond solo + Claude) → MVP → 2-3 design partners → 6.3M ARR ใน 12 เดือน
>
> ## Next steps
> - **ถ้า approved วันนี้:** kick off ภายในสัปดาห์, hiring start
> - **Open Q&A**

**[SAY]**
> "สรุป — TiktokRerun คือ Live Commerce Ops Platform ที่ตลาดยังว่าง
> POC พิสูจน์ velocity แล้ว (4 วัน vs 2-3 wk), unit economics ดี, margin > 90%, risk manageable
> ขอ approve ~200K × 8 สัปดาห์ (solo + Claude) → ส่ง MVP + 2-3 design partners → ตั้งเป้า 6.3M ARR ใน 12 เดือน
> ROI Year 1 = 1,500-2,000%+
> Open สำหรับคำถามครับ"

**[TIME]** 1 นาที + Q&A 30-45 min

---

# 📎 APPENDIX SLIDES (สำหรับ Q&A เมื่อถูกเจาะ)

> เก็บไว้พร้อมเปิดถ้า exec ถามเจาะ — ไม่ต้องแสดงใน main flow

---

## A1: Architecture Deep Dive
**[ON SLIDE]**
> ## 3-tier architecture
>
> ```
> [Web Dashboard]
>      ↓ HTTPS + WSS
> [Backend: Go + Postgres + Redis + S3]
>      ↓ WSS persistent
> [100+ Android phones, BYOD]
> ```
>
> - WebSocket-always-connected = key moat
> - Companion App: Kotlin + ExoPlayer + Accessibility
> - Smart Overlay: SAW + MediaProjection capture

**[เมื่อใช้]** ถ้า Engineering Lead ถาม "architecture ดีไง"

---

## A2: Smart Overlay Technical Detail
**[ON SLIDE]**
> ## Smart Overlay — how it works
>
> ```
> Layer 3: Banner (countdown, price, promo)
> Layer 2: Video (ExoPlayer)
> ─────────────────────────────────
> Layer 1: TikTok app (screen-share mode)
> ```
>
> - TikTok MediaProjection captures all layers
> - Touch passthrough → Accessibility can tap TikTok UI
> - No root, no MOD APK

**[เมื่อใช้]** ถ้าถาม "เทคนิคทำงานยังไง" หรือ "ทำไมไม่ใช้ VCAM"

---

## A3: Competitor Deep Comparison
**[ON SLIDE]**
> ## Capability matrix
>
> | | 3-phone tool | TikMatrix | OBS+LIVE | MOD APK | **เรา** |
> |---|---|---|---|---|---|
> | Web | ❌ | ❌ | ❌ | ❌ | ✅ |
> | Mid-live | ❌ | ❌ | partial | ❌ | ✅ |
> | Multi-device | 3 | 100+ | 1 | 1 | 100+ |
> | Banner | ❌ | ❌ | static | ❌ | ✅ dynamic |
> | TikTok Shop | ❌ | ❌ | ❌ | partial | ✅ |
> | Legal | ✅ | gray | ✅ | ❌ | ✅ |

**[เมื่อใช้]** ถ้าถาม "เปรียบเทียบกับ X อย่างไร"

---

## A4: Cost Detail — Tier A Breakdown
**[ON SLIDE]**
> ## Tier A monthly cost @ 300 devices
>
> | Service | บาท |
> |---|---|
> | Hetzner CPX21 backend | 385 |
> | Neon Postgres Pro | 665 |
> | Upstash Redis | 0-350 |
> | Cloudflare R2 storage | 260 |
> | Cloudflare CDN | 0 (free tier) |
> | Resend, Sentry, etc. | 0 (free tiers) |
> | Backup + misc | 235 |
> | **TOTAL** | **1,550** |

**[เมื่อใช้]** ถ้าถาม "ตัวเลข cost ที่ละเอียดกว่านี้"

---

## A5: Unit Economics Scenario Analysis
**[ON SLIDE]**
> ## ที่ scale ต่างๆ
>
> | Devices | Tier A cost | บาท/device | Revenue (Starter avg) | Margin |
> |---|---|---|---|---|
> | 100 | 1,500 | 15 | 19,900 | 92% |
> | 300 | 3,000 | 10 | 59,700 | 95% |
> | 1,000 | 8,000 | 8 | 199,000 | 96% |
> | 5,000 | 35,000 | 7 | 995,000 | 96% |

**[เมื่อใช้]** ถ้าถาม "ที่ scale ใหญ่ economics เป็นไง"

---

## A6: Customer Acquisition Strategy
**[ON SLIDE]**
> ## GTM motion
>
> 1. **MVP phase:** Hand-picked 2-3 design partners (free/discounted)
> 2. **V1 GA:** Community-led — Facebook group ของ TikTok seller, multi-account reseller community
> 3. **V1+:** Referral program, content marketing (YouTube/TikTok)
> 4. **Year 2:** Sales-assisted for Enterprise tier (5-10K phones)

**[เมื่อใช้]** ถ้าถาม "หาลูกค้ายังไง"

---

## A7: Team Plan Detail
**[ON SLIDE]**
> ## Hiring roadmap (AI-leveraged)
>
> | Role | MVP (8 wk) | V1 (3 mo) | Year 2 |
> |---|---|---|---|
> | Pond (full-stack + product + founder) | 1 | 1 | 1 |
> | Hire: full-stack engineer | – | 1 | 2 |
> | Customer Success | – | 1 | 2-3 |
> | Sales | – | – | 1-2 |
> | DevOps (when scale demands) | – | – | 1 |
> | QA (when scale demands) | – | 0.5 | 1 |
> | **Total FTE** | **1** | **3-4** | **7-9** |
>
> AI tools = headcount multiplier — Claude/Cursor/agents = each FTE ~3x productivity ของยุคก่อน

**[เมื่อใช้]** ถ้าถาม "headcount plan" หรือ "ทำไม 1 คนพอ"

---

## A8: Worst-Case Scenarios
**[ON SLIDE]**
> ## ถ้าทุกอย่างพัง
>
> | Scenario | Impact | Mitigation |
> |---|---|---|
> | Smart Overlay verification fail | Delay 2-4 wk, fall back to plain screen-share | already designed |
> | TikTok mass ban เครื่อง design partner | Lose 1-2 partners | replace, document for new partners |
> | Engineer ลาออก mid-MVP | 1-month delay | contractor backfill plan |
> | TikTok ออก partner program ปิดประตู | Pivot to official-API edition | 6-month adjustment |
> | Revenue ไม่ถึง 30 Users ใน 12 เดือน | Extend runway, slow hiring | keep cost low (Tier A infra) |

**[เมื่อใช้]** ถ้าถาม "ถ้า X เกิดขึ้นจะทำยังไง"

---

# 🎯 EXECUTION CHECKLIST

## วันก่อน meeting
- [ ] ซ้อม walk-through 2 รอบ (timing 25 min)
- [ ] ซ้อม demo จน smooth (no errors)
- [ ] เตรียม backup recording ของ demo
- [ ] อ่าน exec-qa-prep.md (จำตัวเลข)
- [ ] ใส่ presentation script ลง phone (backup teleprompter)

## ก่อนเข้าห้อง 1 ชม.
- [ ] Test laptop + projector
- [ ] Test phone + Wi-Fi/4G
- [ ] เปิดทุก artifact backup
- [ ] หายใจลึกๆ 5 ครั้ง

## ในห้อง
- [ ] Slide 1 — Title, intro 30s
- [ ] Slide 2 — Ask first (lead with the ask)
- [ ] Walk Slides 3-15 (~20 min)
- [ ] Slide 9 — Live demo (5-7 min)
- [ ] Slide 16 — TL;DR + Open Q&A
- [ ] **ใช้ Appendix เมื่อถูกเจาะ**

## ระหว่าง Q&A
- [ ] ฟังคำถามให้จบก่อนตอบ
- [ ] ถ้าไม่รู้ — "ดี เป็น open question, จะ resolve ที่ phase X"
- [ ] อย่าโต้แย้ง concern — ฟัง, acknowledge, propose mitigation
- [ ] เก็บคำถามที่ตอบไม่ได้ลงโน้ต → follow-up

## หลัง meeting
- [ ] เขียน summary ทันที (ขณะ context ยัง fresh)
- [ ] ส่ง thank-you email พร้อม recap + action items
- [ ] Update decision log
- [ ] ถ้า approved: kick off ภายในสัปดาห์
