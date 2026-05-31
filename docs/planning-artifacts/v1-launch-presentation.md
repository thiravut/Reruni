# V1 Launch — Executive Presentation

**Date:** 2026-05-31
**Owner:** Pond
**Audience:** Executive Sponsor + Product/Engineering Lead
**Total time:** 32-38 นาที walkthrough + 20-30 นาที Q&A
**Format:** 19 main slides + 4 appendix
**Source:** ตรงกับ [prd-v1-launch.md](prd-v1-launch.md) + [release-roadmap.md](release-roadmap.md)

> Format note: แต่ละ slide มี **[ON SLIDE]** (สิ่งที่แสดง) + **[SAY]** (script) + **[TIME]**

---

# 🎬 MAIN PRESENTATION (19 slides, ~35 min)

---

## Slide 1: Title

**[ON SLIDE]**
> # TiktokRerun V1
> ## Launch Approval Request
> Pond — 2026-05-31

**[SAY]**
> "สวัสดีครับ วันนี้ขอ approval สำหรับ V1 launch ของ TiktokRerun
> Infrastructure 90% เสร็จแล้ว — ที่ขอวันนี้คือ green light ให้ ship ถึงลูกค้าจริงใน Q3 ปีนี้"

**[TIME]** 30 วินาที

---

## Slide 2: The Ask (Lead with it)

**[ON SLIDE]**
> ## Approve V1 Launch
> - Budget: **~100-200K บาท** (final polish + mobile + legal)
> - Timeline: **8 weeks → Q3 2026 GA**
> - Team: **Pond solo + Claude**
> - Outcome: **First 10 paying customers within 30 days of launch**

**[SAY]**
> "ขอตรง — V1 launch ต้องการ 100-200K บาท × 8 สัปดาห์
> ผม solo + Claude ตามเดิม
> Outcome: paying customers 10 รายแรกใน 30 วันหลัง launch
> รายละเอียดต่อไป"

**[TIME]** 1 นาที

---

## Slide 3: Why Now — POC Done, Code Built

**[ON SLIDE]**
> ## เราอยู่ไหนตอนนี้
>
> | Component | Status |
> |---|---|
> | POC (broadcast + pin + audio) | ✅ Validated |
> | API Backend (Go) | ✅ Built + tested |
> | Portal SPA | ✅ Built |
> | Backoffice SPA | ✅ Built |
> | Stripe Billing | ✅ Integrated (3 tiers) |
> | Two-cookie sessions | ✅ Deployed |
> | Mobile companion | ⚠️ Decision pending |
> | Production deploy | ⏳ Pending domain reruni.com |

**[SAY]**
> "Infrastructure 90% ของ V1 build เสร็จแล้ว
> Backend, Portal, Backoffice, Stripe billing, session security — ทำงานครบ
> เหลือแค่: mobile path decision + production deploy"

**[TIME]** 1.5 นาที

---

## Slide 4: Customer Profile — Solo Seller "ตอง"

**[ON SLIDE]**
> ## Primary Persona
>
> **ตอง — Solo TikTok Shop Seller**
> - อายุ 24-38, ทำ TikTok Shop full-time
> - มี 3-15 บัญชี TikTok Shop หลาย category
> - เริ่ม 1-3 phones, scale ถึง 10-20
> - คนเดียวเป็นทุกอย่าง — ไม่มีงบจ้างทีม
>
> **ไม่ใช่ V1 target:**
> - Agency / team / multi-user — no plan
> - Enterprise compliance — no plan

**[SAY]**
> "ลูกค้าหลัก = solo seller รัน TikTok Shop หลายบัญชี
> 'ตอง' อายุ 24-38 ใช้ TikTok Shop เป็น income หลัก
> มี 3-15 accounts, 1-3 phones เริ่มต้น
> คนเดียวต้องการ leverage AI/automation — ตรงกับ pain ของเขา
>
> สิ่งที่ตัด — agency, multi-user team ไม่อยู่ในแผน
> ไม่ใช่ deferred — ตัดถาวร focus solo seller"

**[TIME]** 2 นาที

---

## Slide 5: Portal (Web) — สิ่งที่ลูกค้าทำได้

**[ON SLIDE]**
> ## Portal Web App — `app.reruni.com`
>
> ### 🔐 บัญชี + การชำระเงิน
> - Signup → เลือก subscription tier → จ่ายผ่าน Stripe (no trial)
> - Login + forced password change หลัง admin reset
> - Billing portal: ดู invoice, เปลี่ยน payment, cancel subscription
>
> ### 📱 จัดการ Devices (โทรศัพท์)
> - กด "Add device" → QR code (อายุ 5 นาที) → scan ที่มือถือ
> - ดูสถานะ fleet real-time: online/offline/live/error
> - Group devices ตาม category (Snacks, Beauty, ...)
> - Rename / unpair device
>
> ### 🎥 Video Library
> - Upload วิดีโอ mp4 (≤ 500MB, ≤ 60 min)
> - List + delete videos
> - แนบ banner กับ video (template)
>
> ### 📡 Live Operations
> - เลือก devices หลายตัว + video + กด "Start Live"
> - ระหว่าง live: switch video, restart, ปรับเสียง — real-time
> - ตั้ง Live Title / Caption / Hashtags ก่อน start
> - หยุด live single หรือ batch
>
> ### 🛒 TikTok Shop Control
> - Pin product (SKU) ระหว่าง live
> - Unpin / Switch product ตามเวลา promo
> - แสดง warning ถ้า SKU out-of-stock
>
> ### 🎨 Banner Composition
> - **Static banner:** ตั้งไว้ก่อน, render ตลอด live
> - **Dynamic banner:** เปลี่ยนจาก web real-time (≤ 3 วินาที)
> - Countdown banner สำหรับ flash sale
>
> ### 📊 Live History
> - ดู past lives (last 90 days)
> - Filter ตาม device + วันที่
>
> ### 📱 Mobile responsive
> - เปิดบนมือถือดู status + restart live ได้ขณะนอกบ้าน

**[SAY]**
> "Portal — ที่ลูกค้าใช้ทุกวันบนเว็บ
> ทุก feature ที่เห็นในจอ — ทำงานได้แล้วในของจริง
>
> เริ่มจากซ้ายบน: account + billing ผ่าน Stripe
> ตรงกลาง: จัดการ phones, videos, lives, banners
> ขวาล่าง: history + mobile-responsive layout
>
> Highlight: Dynamic Banner — เปลี่ยน promo กลาง live ได้
> = killer feature ที่คู่แข่งทำไม่ได้"

**[TIME]** 3 นาที

---

## Slide 6: Mobile Companion App — สิ่งที่รันบนโทรศัพท์

**[ON SLIDE]**
> ## TiktokRerun Companion App (Android)
>
> ### 🔌 Pairing (one-time setup)
> - ลูกค้า login TikTok บนโทรศัพท์เครื่องนั้นก่อน
> - เปิด companion app → scan QR จาก portal → pair สำเร็จ
> - Device token เก็บไว้ใน app — ไม่ต้อง re-pair
>
> ### 📡 Persistent Connection
> - WebSocket connection กับ backend ตลอด (auto-reconnect)
> - Heartbeat ทุก 30 วินาที
> - แม้เครื่องอยู่ 4G/5G — ยัง reachable จาก web
>
> ### 🎬 Broadcast Execution (V1 — Path A: Smart Overlay)
> - รับ command "start_live" จาก backend
> - Accessibility Service: เปิด TikTok → Go Live → Screen Share
> - วาด video เป็น overlay เต็มจอ (SAW)
> - TikTok screen-share จับ overlay → broadcast
> - Banner layer วาดบน video — เปลี่ยน real-time ตาม command
>
> ### 🛒 TikTok Shop Control
> - รับ command "pin_product" → Accessibility tap UI ของ TikTok
> - Switch product / unpin ผ่าน UI flow ปกติ
>
> ### ⚙️ Background Operation
> - Foreground service — broadcast ต่อเนื่องแม้หน้าจอปิด
> - Audio system routing ส่งเสียง video เข้า TikTok mic
> - Auto-recover เมื่อ connection หาย
>
> ### 📊 Diagnostics
> - In-app diagnostics view: ดู status, capabilities, error logs
> - Report capabilities กลับ backend (CapsCollector)

**[SAY]**
> "Mobile companion — สิ่งที่ติดตั้งบนโทรศัพท์ลูกค้า
>
> Setup ครั้งเดียว: scan QR → pair → ไม่ต้องแตะอีก
>
> หลังจากนั้น app ทำงาน background ตลอด:
> - รับ command จาก web ผ่าน WebSocket
> - ใช้ Accessibility Service เป็น 'มือ' ที่กดปุ่ม TikTok แทนลูกค้า
> - วาด video + banner เป็น overlay → TikTok broadcast ออกไป
>
> Diagnostics view ช่วย support team debug ได้เมื่อมีปัญหา
>
> นี่คือ V1 path A — Smart Overlay
> หาก choose Path B (Patched APK) flow จะต่างกัน — แต่ portal เห็นเหมือนกัน"

**[TIME]** 3 นาที

---

## Slide 7: Backoffice (Admin) — สิ่งที่ทีม TiktokRerun ใช้

**[ON SLIDE]**
> ## Backoffice — `backoffice.reruni.com`
>
> ### 👤 User Management
> - ดู users ทั้งหมด: email, role, devices count, last active
> - Search/filter, Promote/demote role (admin/user)
> - Reset password → ลูกค้าถูกบังคับเปลี่ยนตอน login ครั้งถัดไป
> - Delete user (cascade ลบ devices + videos + subs)
>
> ### 💳 Subscription Management
> - ดู subscriptions ทั้งหมด across users
> - Filter ตาม status (active/pending/past_due/canceled)
> - **Recheck button** — pull จาก Stripe ทันที (fallback เมื่อ webhook พลาด)
> - Sortable + paginated table
>
> ### 📱 Device Monitoring
> - ดู devices ทั้งระบบ + status + last_seen
> - Filter ตาม status, owner email
> - Force-disconnect device (admin intervention)
>
> ### 🎥 Video Storage Tracking
> - List videos ทั้งหมด + disk usage
> - Sort by size สำหรับหา disk hog
> - Display total disk consumed
>
> ### 📡 Live Sessions Oversight
> - ดู active + recent lives (across users)
> - Force-stop live (admin intervention)
> - Track end_reason (user_stop / error / admin_force)
>
> ### 📊 Metrics Dashboard
> - Total users + active 7-day
> - Total devices + online + live
> - Lives 24h + broadcast hours
> - Disk used total
> - Auto-refresh every 30s

**[SAY]**
> "Backoffice — เครื่องมือของทีมเรา (Pond + future support staff)
>
> ครอบคลุม operations ทั้งหมด:
> - จัดการ user accounts
> - ดู subscriptions + recheck เมื่อ Stripe webhook พลาด
> - Monitor devices + lives ทั่วระบบ
> - Force actions เมื่อต้อง intervene
>
> Auto-refresh metrics ทุก 30s = ดูสุขภาพระบบ live ได้"

**[TIME]** 2 นาที

---

## Slide 8: Competitor Comparison — ทำไมลูกค้าเลือกเรา

**[ON SLIDE]**
> ## TikTok Live Commerce Tools — Market Landscape
>
> ### Capability Matrix
>
> | Capability | คู่แข่ง 3-phone PC (TH) | TikMatrix (global) | **TiktokRerun (เรา)** |
> |---|---|---|---|
> | Web-based control | ❌ | ❌ | ✅ |
> | Mid-live control (เปลี่ยน video/SKU ระหว่าง live) | ❌ | ❌ | ✅ |
> | Pin product real-time | ❌ | ❌ | ✅ |
> | **Dynamic banner overlay** | ❌ | ❌ | ✅ |
> | TikTok Shop integration | ❌ | ❌ | ✅ |
> | Multi-account rotation (V2) | ❌ | ❌ | ✅ |
> | Persistent cloud connection | ❌ (PC dies = phone หาย) | ❌ | ✅ |
> | Scale 100+ phones | ❌ (max 3) | ✅ | ✅ |
> | BYOD (ไม่ต้อง root) | ✅ | ❌ ต้อง root | ✅ |
> | Multi-tenant SaaS (web account) | ❌ | ❌ | ✅ |
>
> ### Pricing Reference (ตลาด TH)
>
> | Tool | ราคา | Note |
> |---|---|---|
> | 3-phone PC tool (Thai vendor) | **~299 บาท/device/month** | Industry reference price |
> | TikMatrix Pro | $59-149/month (1,650-4,200 บาท) | per account tier, ไม่ใช่ per device |
> | Manual hire (1 operator คุม 3 phones) | ~15,000-20,000 บาท/month | ราคา salary |
>
> ### Why Customer Choose Us
> 1. **Mid-live control** — เปลี่ยน promo/banner/SKU กลาง live (คู่แข่งไม่มี)
> 2. **Cloud-based** — คุมจาก anywhere, ไม่ต้องอยู่หน้าคอม
> 3. **No root required** — ลูกค้าใช้ phone อะไรก็ได้
> 4. **TikTok Shop ready** — เพิ่ม commerce features
> 5. **Multi-profile rotation (V2)** — 1 phone × 3 accounts = 3x effective value

**[SAY]**
> "ตลาด TikTok live commerce tools ในไทยมี 2 player หลัก:
>
> 1. 3-phone PC tool ของไทย — ราคา **299 บาท/device/เดือน** = industry reference
>    ข้อจำกัด: max 3 phones, ไม่มี web, ไม่มี mid-live control
>
> 2. TikMatrix global — engagement farm, ไม่ใช่ commerce tool
>    ต้อง root, ไม่มี TikTok Shop integration
>
> เรา **ครอบคลุมทุก capability ที่คู่แข่งมี** + เพิ่ม:
> - Web-based control (จาก anywhere)
> - Mid-live control (killer feature)
> - Dynamic banner overlay
> - TikTok Shop integration
> - Multi-profile rotation (V2)
>
> ราคา industry reference = 299 บาท/device/เดือน
> ผมเอามาเป็น benchmark ตอน plan pricing — slide ถัดไป"

**[TIME]** 2.5 นาที

---

## Slide 9: Mobile Path Decision

**[ON SLIDE]**
> ## Mobile Path — 3 Options
>
> | Path | Pros | Cons |
> |---|---|---|
> | **A. Smart Overlay** (POC original) | ไม่ root, BYOD, proven | quality กลาง, screen capture artifact |
> | **B. Patched APK + VCAM** | quality สูง, no root | ใช้คู่แข่ง APK (dependency + legal risk) |
> | **C. Both** — Smart Overlay default + Patched APK pilot | Hedge | Maintain 2 paths |
>
> **Open questions:**
> - Ban rate ยังไม่ทดสอบ
> - APK hosting strategy

**[SAY]**
> "Mobile mit 3 ทางเลือก:
> A — Smart Overlay POC ที่ validated แล้ว — proven, ไม่ root, แต่ quality กลาง
> B — Patched APK + VCAM ที่เราทำงานได้แล้วบน Samsung A15 — quality ดีกว่า แต่ใช้ APK ของคู่แข่ง
> C — Hedge ทั้งคู่
>
> ที่ต้องตัดสินใจก่อน launch:
> - Path ไหน
> - APK hosting ทางไหน (legal risk)
> - Ban rate threshold ที่ acceptable"

**[TIME]** 2 นาที

---

## Slide 10: Business Model — Flat Pricing 299/Device

**[ON SLIDE]**
> ## Flat Pricing — 299 บาท/device/month
>
> ### หลักการ
> - **เริ่มต้นที่ 299 บาท/device/month** (industry benchmark — ราคาคู่แข่ง 3-phone tool)
> - **ไม่มี free trial** — signup → ใส่ payment → ใช้
> - **ไม่มี tier** — จ่ายตาม devices ที่ใช้จริง
> - **Annual discount 20%** (commit yearly)
>
> ### ตัวอย่างราคา
>
> | Devices | บาท/เดือน | บาท/ปี (annual -20%) |
> |---|---|---|
> | 1 device | 299 | 2,870 |
> | 5 devices | 1,495 | 14,352 |
> | 10 devices | 2,990 | 28,704 |
> | 30 devices | 8,970 | 86,112 |
> | 100 devices | 29,900 | 287,040 |
>
> ### Why Flat Pricing
> - **Match competitor** — ลูกค้าเข้าใจราคาทันที (เทียบกับ 3-phone tool ที่ 299)
> - **Simple sales pitch** — ไม่ต้องอธิบาย tier
> - **Aligned with usage** — ลูกค้าจ่ายเท่าที่ใช้, ไม่มี waste
> - **Encourages scaling** — เพิ่ม devices ได้ทันทีไม่ต้องเปลี่ยน tier
>
> ## Revenue Projection (flat 299 model)
>
> | Stage | Customers | Avg devices | MRR (บาท) | ARR (บาท) |
> |---|---|---|---|---|
> | V1 6-mo | 40 | 7 | 84K | **~1M** |
> | V1 12-mo | 130 | 10 | 388K | **~4.7M** |
> | Year 2 | 500 | 15 | 2.24M | **~26.9M** |
> | At scale (2,000 users target) | 2,000 | 7.5 | 4.49M | **~53.8M** |

**[SAY]**
> "Pricing — เลือก **flat 299 บาท/device** ตาม industry reference จากคู่แข่ง 3-phone tool ในไทย
>
> หลักการ:
> - ราคาเดียว 299 ทุก device
> - ไม่มี tier ซับซ้อน
> - ลูกค้าจ่ายตามที่ใช้จริง
> - Annual commit ลด 20%
>
> ตัวอย่าง:
> - 1 phone = 299
> - 10 phones = 2,990 (ถูกกว่า Starter เดิม 25%)
> - 100 phones = 29,900 (แพงกว่า Pro เดิม 50%)
>
> เหตุผลเลือก flat:
> 1. Match competitor → ลูกค้าตัดสินใจเร็ว
> 2. Sales pitch ง่าย ไม่ต้องอธิบาย tier
> 3. Scale ได้ smooth (เพิ่ม device ไม่ต้องเปลี่ยน plan)
>
> Revenue projection:
> - 12 เดือนหลัง launch: 130 customers × avg 10 devices = 4.7M ARR
> - Year 2: 500 customers × 15 devices = 27M ARR
> - At 2,000-user target: 53.8M ARR"

**[TIME]** 2 นาที

---

## Slide 11: Server + Storage Cost at Scale (2,000 users)

**[ON SLIDE]**
> ## ต้นทุน Infrastructure ที่ 2,000 users
>
> ### Assumptions
> - **2,000 paying users**
> - Average **5-10 devices/user** → ~**15,000 total devices**
> - Average video library: 3 GB/user
> - Active broadcasting daily
>
> ### Monthly Cost Breakdown (Hybrid: GCP compute + Cloudflare R2 storage)
>
> | Component | Provider | Cost/เดือน |
> |---|---|---|
> | **Compute** — Backend Go + WebSocket | Cloud Run (min 2 instances, 4 vCPU, 8GB) | ~7,000 บาท |
> | **Database** — Postgres HA (~20GB, 100K QPS peak) | Cloud SQL (db-custom-2-8192 + standby) | ~7,500 บาท |
> | **Cache/Queue** — Redis 2GB HA | Memorystore Standard | ~3,500 บาท |
> | **Object Storage** — 6 TB video files | **Cloudflare R2** ($0.015/GB) | ~3,200 บาท |
> | **CDN egress** — 22 TB/month video downloads | **Cloudflare R2 = $0 egress!** | 0 บาท |
> | **R2 operations** (write/read API) | Cloudflare R2 | ~1,500 บาท |
> | **Email** (transactional + notifications) | Resend Pro | ~1,000 บาท |
> | **Domain** — reruni.com (699 บาท/ปี) | Domain registrar | ~58 บาท |
> | **SSL Certificate** ($240/ปี × 35 บาท) | SSL provider | ~700 บาท |
> | **Operations + Monitoring** (Cloud Logging, Monitoring) | GCP | ~1,500 บาท |
> | **Cloud Armor** (basic DDoS/WAF) | GCP | ~700 บาท |
> | **TOTAL** | | **~26,658 บาท/เดือน** |
>
> ### Architecture Split
>
> ```
> [GCP]                              [Cloudflare]
> ├─ Cloud Run (Go backend)          ├─ R2 (video storage 6 TB)
> ├─ Cloud SQL (Postgres HA)         └─ R2 egress (FREE)
> ├─ Memorystore (Redis HA)             ↓
> ├─ Cloud Logging + Monitoring         → ลูกค้า / phones
> └─ Cloud Armor (WAF)
> ```
>
> ### Cost per Unit
>
> | Metric | Value |
> |---|---|
> | บาท/device/เดือน | **~1.78 บาท** |
> | บาท/user/เดือน | **~13.33 บาท** |
> | บาท/GB storage | 0.53 บาท |
>
> ### Ratio vs Revenue
>
> Revenue (flat 299/device): 2,000 users × 7.5 devices × 299 = **~4.49M บาท MRR** = 53.8M ARR
>
> | Item | Amount | % of Revenue |
> |---|---|---|
> | Infrastructure cost | 26,658 บาท | **0.59%** |
> | Gross margin | 4.49M - 26,658 | **>99.4%** |
>
> ### 💡 Why Hybrid Stack
>
> | Layer | Choice | Reason |
> |---|---|---|
> | **Compute** | GCP Cloud Run | Reliable + enterprise-grade + Pond preference |
> | **Database** | GCP Cloud SQL HA | Managed Postgres + HA failover |
> | **Storage + CDN** | **Cloudflare R2** | **$0 egress** ที่ scale 22 TB/month = save ~62K บาท/เดือน |
>
> Pure GCP: ~90,200 บาท | Hybrid (this plan): **~26,658 บาท** | Save: **~63,500/เดือน**

**[SAY]**
> "ต้นทุน infrastructure ที่ scale 2,000 users — **hybrid stack** (GCP + Cloudflare R2):
>
> 2,000 users × avg 7.5 devices = 15,000 devices ทั้งระบบ
>
> Architecture แยกฝั่ง:
> - **GCP** — Cloud Run (Go backend), Cloud SQL (Postgres HA), Memorystore (Redis HA)
> - **Cloudflare R2** — video storage 6 TB + CDN egress (free!)
>
> Cost รวม ~26,658 บาท/เดือน
> = 1.78 บาท/device/เดือน
> = 13.33 บาท/user/เดือน
>
> **Key insight: ทำไม hybrid:**
> - GCP เป็น enterprise-grade compute + DB (Pond preference)
> - แต่ CDN egress 22 TB/month ที่ GCP = ~62,000 บาท
> - **Cloudflare R2 = $0 egress** — save 62K/เดือน
> - = ดีกว่า pure GCP ถึง 63,500 บาท/เดือน
>
> เทียบ revenue 8M MRR → infra เพียง **0.33%**
> Gross margin **>99.6%**
>
> = SaaS-grade margin + enterprise reliability + cost optimization"

**[TIME]** 2 นาที

---

## Slide 12: Breakeven @ Flat 299 บาท/Device/Month

**[ON SLIDE]**
> ## Breakeven — Flat 299 บาท/device/month (DECIDED PRICING)
>
> > **Pricing model:** Flat 299 บาท/device/month ทุก customer, ทุก device
> > **Reference:** Match industry benchmark จาก 3-phone tool ในตลาดไทย
>
> ### Fixed Monthly Costs
>
> | Item | บาท/เดือน |
> |---|---|
> | Pond salary (founder) | 50,000 |
> | Claude (AI tools) | 6,500 |
> | Operational tools (Linear, GitHub, Sentry, etc.) | 5,000 |
> | Infrastructure (Hybrid GCP + R2) | 5,000-27,000 (scale-dependent) |
> | Misc + contingency | 3,000 |
> | **Total fixed (V1 launch)** | **~70,000 บาท** |
> | **Total fixed (at scale, 2,000 users)** | **~92,000 บาท** |
>
> ### Breakeven Math
>
> **Revenue per device:** 299 บาท/month — flat, ไม่มี tier
>
> | Phase | Fixed cost | Breakeven devices | Avg devices/customer | **Breakeven customers** |
> |---|---|---|---|---|
> | V1 launch (lean infra) | 70,000 | 234 devices | 7.5 | **~31 customers** |
> | At scale (full infra) | 92,000 | 308 devices | 7.5 | **~41 customers** |
>
> ### Time-to-Breakeven Projection
>
> | Customer acquisition rate | Months to breakeven |
> |---|---|
> | 5 customers/month (slow) | ~6 months |
> | **7-10 customers/month (realistic)** | **~3-5 months** ⭐ |
> | 15 customers/month (aggressive) | ~2 months |
>
> ### Margin Analysis (Post-breakeven)
>
> | Customers | MRR (299 × 7.5 × N) | Cost | **Profit margin** |
> |---|---|---|---|
> | 50 customers (375 devices) | 112,125 | 80,000 | 29% |
> | 100 customers (750 devices) | 224,250 | 85,000 | 62% |
> | 500 customers (3,750 devices) | 1.12M | 90,000 | 92% |
> | 2,000 customers (15,000 devices) | **4.49M** | 92,000 | **97.9%** |
>
> ### ✅ Why Flat 299 Beats Tier Pricing
>
> | Aspect | Flat 299 | Tier Pricing |
> |---|---|---|
> | Onboarding friction | ต่ำ — ลูกค้าเข้าใจทันที | สูง — ต้องเลือก tier |
> | Upgrade path | linear (เพิ่ม device) | discrete (jump tier) |
> | Pricing transparency | ✅ ทุก customer เห็นราคาเดียวกัน | ❌ ต้องคิดเลข tier |
> | Match competitor | ✅ ตรงกับ 3-phone tool | ❌ ต่าง model |
> | Revenue at scale (2K users) | 4.49M MRR | ~3M MRR (estimated) |
>
> → **DECISION: Flat 299 เป็น pricing model สำหรับ V1 launch**

**[SAY]**
> "Pricing model ที่เลือก — **flat 299 บาท/device/month** ทุก customer, ทุก device
> ราคานี้ match กับ 3-phone tool ในตลาดไทยที่ลูกค้าเทียบ
>
> Fixed cost monthly ~70K ที่ V1 launch (รวม salary + tools + infra)
> = **breakeven ที่ 31 customers** หรือ 234 devices
>
> ที่ acquisition rate 7-10 customers/month → **breakeven 3-5 เดือนหลัง launch**
>
> Profit margin หลัง breakeven scale ไปเร็ว:
> - 100 customers = 62% margin
> - 500 customers = 92% margin
> - 2,000 customers = 97.9% margin
>
> เหตุผลที่เลือก flat แทน tier:
> 1. Onboarding friction ต่ำ — ลูกค้าเข้าใจทันที (1 phone = 299, 10 phones = 2,990)
> 2. Match ราคาคู่แข่ง 1:1 — ไม่ต้อง educate ราคา
> 3. Upgrade เป็น linear — ลูกค้าเพิ่ม device 1 ตัว, ไม่ต้อง jump tier
> 4. Revenue ที่ scale สูงกว่า — ลูกค้า 100-device จ่าย 29,900 แทน 19,990
>
> → Pricing **decided** — ไม่ใช่ analysis, ไม่ใช่ stress-test"

**[TIME]** 3 นาที

---

## Slide 13: Roadmap Overview — V1 ↦ V3

**[ON SLIDE]**
> ```
> 2026 Q3 │ V1   — Launch (paying customers)
> 2026 Q4 │ V1.5 — Stability + QoL
> 2027 Q1 │ V2   — Multi-profile + Scheduling ✅
> 2027 Q2 │ V3   — AI + Hybrid Live (exploratory)
> ```
>
> **Tagged with confidence:**
> - ✅ Confirmed
> - 🔄 Likely
> - ❓ Exploratory
>
> **Removed:**
> - ❌ SEA expansion (focus TH first)
> - ❌ Multi-user team / agency

**[SAY]**
> "Roadmap 4 versions ใน 12 เดือน
> V1 ตอนนี้ → V1.5 stability → V2 multi-profile rotation → V3 AI features
>
> ทุก feature tag ด้วย confidence: confirmed / likely / exploratory
> สำคัญ — V3 หลายอันยัง exploratory ไม่ promise ทำได้แน่
>
> ที่ตัดออกถาวร: SEA expansion + agency/multi-user
> Focus TH solo seller ให้ดีก่อน scale"

**[TIME]** 2 นาที

---

## Slide 14: V1.5 — Stability + Quality of Life (Q4 2026)

**[ON SLIDE]**
> ## V1.5 — ลูกค้าได้อะไรเพิ่ม
>
> ### 🎥 Video Library — ดีขึ้นเยอะ
> - **Duration probe** — แสดงความยาวคลิปทุกตัว
> - **Thumbnail** — preview ของวิดีโอ
> - **Video preview** — เล่นในหน้า library ก่อน assign
>
> ### 🎨 Banner — ทำเร็วกว่า
> - **Template library** — preset designs สำเร็จรูป
> - เลือก preset → แก้ text → ใช้ได้เลย
>
> ### 📧 Notifications
> - Email แจ้ง: live ended, payment failed, account suspension
> - ลูกค้าไม่ต้องเช็คเองตลอด
>
> ### 💳 Billing — ทางเลือกใหม่
> - **Annual billing + 20% discount** — จ่ายปีละครั้งประหยัด
> - Auto-prorate เมื่อเปลี่ยน tier
>
> ### 📊 Backoffice — Ops ง่ายกว่า
> - **Server-side sort** ใน admin tables
> - **CSV export** users / lives / subscriptions
> - **Audit log** — ดูใครทำอะไร
>
> ### 📡 Live History — ตามได้แม่น
> - **True total count** (เดิม return page size)
> - Advanced filters + CSV export
>
> ### 📱 Mobile — รุ่นรองรับเพิ่ม
> - Auto-update companion app
> - Re-patch SLA 24-48hr per TikTok release
> - Better OEM compatibility (Xiaomi, Oppo, Realme — เดิม test แค่ Samsung)
>
> ### 🐛 Reliability
> - **Sentry error tracking** — แก้ bug เร็วกว่า
> - Structured logging + alerting

**[SAY]**
> "V1.5 = ลด ops burden + เพิ่ม polish
>
> ลูกค้าเห็น: thumbnail, template banner, email noti, annual discount, รุ่นมือถือเพิ่ม
>
> ทีม Pond เห็น: Sentry, audit log, sort/export — ดูแลระบบง่ายขึ้น
>
> ทั้งหมด ✅ Confirmed — ทำได้แน่ ไม่มี R&D risk"

**[TIME]** 2 นาที

---

## Slide 15: V2 — Multi-profile Rotation + Scheduling (Q1 2027)

**[ON SLIDE]**
> ## V2 — Ban-resistant + Auto-pilot
>
> ### 🔄 Multi-profile Rotation ✅
> **1 device รองรับหลาย TikTok accounts**
>
> Workflow:
> 1. ลูกค้า login หลาย accounts ใน TikTok (native switcher อยู่แล้ว 5+ accounts/app)
> 2. Web dashboard: label accounts + ตั้ง rotation schedule
> 3. Companion app: ใช้ Accessibility กด switch ตามเวลา
>
> ผลลัพธ์:
> - 1 phone × 3 accounts = effective 3x value
> - บัญชี A โดน ban → auto-skip ใช้ B ต่อ (capacity -33% ไม่ใช่ -100%)
>
> ### ⏰ Scheduling ✅
> - Time-based start/stop (เริ่ม 09:00 หยุด 23:00 อัตโนมัติ)
> - Playlist rotation — auto-switch video ตามรอบ
> - Recurring schedule รายวัน/รายสัปดาห์
>
> ### 📊 Analytics (in-system)
> - Live hours per account
> - Viewer count history
> - Per-profile ban detection — auto-skip
>
> ### 💬 Comment Monitoring ❓
> - Real-time comment feed (ถ้าเข้า TikTok comment API ได้)
> - Keyword alerts, basic moderation
> - **Feasibility: TikTok API ไม่ public — ต้อง R&D scraping**
>
> ### 📈 GMV Analytics ❓
> - Conversion / sales per account
> - **Feasibility: ขึ้นกับ TikTok Shop API access**
>
> ### 🎓 Onboarding
> - Self-serve tutorial + video walkthrough
> - In-app tour สำหรับ feature ใหม่

**[SAY]**
> "V2 = ban-resistant + auto-pilot
>
> Killer feature คือ multi-profile rotation —
> 1 phone รองรับ 3-5 บัญชี TikTok หมุนใช้
> = ลด ban impact + เพิ่ม effective capacity 3x
>
> TikTok app มี native switcher อยู่แล้ว — เราแค่ automate ผ่าน Accessibility
> = ✅ Confirmed feasibility
>
> Scheduling ✅ — ตั้งเวลา start/stop, playlist rotation
>
> Comments + GMV analytics — flag ❓
> ขึ้นกับ TikTok API access ที่เราต้องสำรวจ
> ไม่ promise — ถ้าทำได้ดี, ถ้าไม่ได้ skip"

**[TIME]** 2.5 นาที

---

## Slide 16: V3 — AI Assist + Pro Features (Q2 2027 — Exploratory)

**[ON SLIDE]**
> ## V3 — AI Moat + Pro Tier
>
> ### 🔄 Likely (มี data + LLM API ทำได้)
> - **AI Comment Reply** — generate ตอบ comment ลูกค้า → operator approve
> - **AI Insights** — "Best performing video" / "When to switch product"
> - **Smart Scheduling** — algorithm หา live times ที่ดีสุดจาก past data
> - **Auto Banner** — AI generate banner copy จาก video content
> - **Fraud Detection** — spot suspicious activity (bot patterns)
>
> ### ✅ Confirmed (engineering)
> - **Advanced Pin** — multi-product rotation, auto-pin ตาม timestamp video
>
> ### ❓ Exploratory (ยังไม่รู้ทำได้ไหม)
> - **Hybrid Live** — creator join live real-time, takeover broadcast
>   - feasibility: ยังไม่ชัด mid-stream control handoff
> - **Fast snapshot account swap** (sub-10s, vs V2 ที่ 10-30s)
>   - requires root / custom ROM
>   - **อาจไม่ทำ** ถ้า V2 switcher เร็วพอที่ scale
> - **AI features dependency** — Comment reply / GMV insights ต้อง V2 ❓ ผ่านก่อน
>
> ### 💼 Pro Tier (สมมุติฐาน)
> - V3 features = upgrade tier ใหม่ 29,990 บาท/เดือน (Pro Plus)
> - หรือ included ใน Pro 19,990 — TBD per market research

**[SAY]**
> "V3 = AI moat + Pro features — แต่ honest framing:
>
> ส่วนใหญ่เป็น ❓ Exploratory + 🔄 Likely
> = ไม่ promise ทำได้ครบ
>
> สิ่งที่แน่ใจ:
> - AI Comment Reply, AI Insights — ทำได้ถ้ามี data source
> - Advanced Pin — engineering ตรงไปตรงมา
>
> สิ่งที่ไม่แน่ใจ:
> - Hybrid Live — technical feasibility unclear
> - Fast account swap — depends mobile path + may not be needed
>
> Pro tier pricing 29,990 บาท ถ้า features คุ้ม — validate ใน V2 customer feedback ก่อน
>
> V3 = bet หลายทาง → research-driven, not commitment-driven"

**[TIME]** 2.5 นาที

---

## Slide 17: Top Risks

**[ON SLIDE]**
> | Risk | Severity | Mitigation |
> |---|---|---|
> | Mobile fails at scale | High | Validate ban rate 2 wk before public launch |
> | Stripe webhook unreliable | Medium | Recheck button built (workaround) |
> | TikTok ToS challenge | Medium | Customer ToS shifts liability |
> | Production deploy delay | Medium | reruni.com + Hetzner ready to provision |
> | Patched APK legal exposure | Medium | Path A (Smart Overlay) as safer alternative |

**[SAY]**
> "5 risks หลัก — ทุกอันมี mitigation:
> Mobile = validate ก่อน scale
> Stripe = recheck button รองรับ
> Legal = customer ToS shift liability
> Deploy = infrastructure plan แล้ว
>
> ไม่มี risk ที่ block launch ตอนนี้"

**[TIME]** 1.5 นาที

---

## Slide 18: 7 Decisions Asked

**[ON SLIDE]**
> | # | Decision | Recommendation |
> |---|---|---|
> | 1 | Approve V1 launch ~100-200K | ✅ Approve |
> | 2 | Mobile path | Path C (hedge — Smart Overlay primary + Patched APK pilot) |
> | 3 | Legal review budget ~50-100K | ✅ Approve |
> | 4 | Design partner selection | 2-3 friendly TH sellers |
> | 5 | GA target Q3 2026 | ✅ Confirm |
> | 6 | Subscription gating without trial | ✅ Keep (decided) |
> | 7 | Two-cookie auth + admin separation | ✅ Confirm (deployed) |

**[SAY]**
> "7 decisions ที่ขอวันนี้:
> ใหญ่สุดคือ #1 — approve V1 launch 100-200K
> #2 — mobile path ผมแนะนำ hedge ทั้งคู่
> #3 — legal review สำหรับ ToS + PDPA
> ที่เหลือ confirm decisions ที่เคยทำไว้แล้ว"

**[TIME]** 1.5 นาที

---

## Slide 19: TL;DR

**[ON SLIDE]**
> ## TL;DR
>
> > **Approve V1 launch ~100-200K บาท × 8 สัปดาห์**
> > - Infrastructure 90% built — มี code จริง ทำงานได้
> > - First 10 paying customers within 30 วันหลัง GA
> > - 6.3M ARR target ใน 12 เดือน
> > - Mobile path = primary risk → mitigation planned
> > - No SEA, no multi-user — focus TH solo seller

**[SAY]**
> "สรุป — Approve 200K × 8 สัปดาห์
> Infrastructure ที่ build ไว้ → ดีพอ launch
> Risk หลักคือ mobile — มี plan รับมือ
> Focus TH solo seller — ไม่ขยายตลาดอื่นใน V1
>
> Open สำหรับคำถามครับ"

**[TIME]** 1 นาที + Q&A 20-30 min

---

# 📎 APPENDIX (4 slides — เก็บไว้สำหรับ Q&A)

---

## A1: Technical Architecture (3-tier)

**[ON SLIDE]**
> ```
> [Web (Portal + Backoffice)]
>      ↓ HTTPS + WSS
> [Backend Go + Postgres + Cloudflare R2]
>      ↓ WSS persistent + REST
> [Android phones with companion app]
> ```
>
> - Auth: 2 separate cookies (portal vs admin)
> - Billing: Stripe + webhook + admin recheck
> - Storage: Cloudflare R2 ($0 egress)
> - Compute: Hetzner CPX21 (~1.5K บาท/mo at MVP scale)

**[เมื่อใช้]** Q: "Tech stack คือ?"

---

## A2: Stripe Integration Detail

**[ON SLIDE]**
> ## Done
> - ✅ Checkout session
> - ✅ Customer portal (self-serve management)
> - ✅ Webhook (signature verify + idempotent)
> - ✅ Admin recheck (fallback when webhook miss)
> - ✅ Subscription gating (no trial, must active)
>
> ## V1.5 add
> - Annual billing + 20% discount
> - Email payment notifications

**[เมื่อใช้]** Q: "Billing ทำงานยังไง?"

---

## A3: V2 Multi-profile Rotation (Confirmed)

**[ON SLIDE]**
> ## Why Confirmed
> - TikTok app มี **built-in account switcher** อยู่แล้ว
> - 5+ accounts/app เก็บได้ native
> - Customer setup: login multiple accounts ล่วงหน้า
> - V2 = automate switch ผ่าน Accessibility Service (ที่เราใช้ V1 อยู่แล้ว)
>
> ## Customer benefit
> - 1 phone effective × 3 accounts = 3x value
> - Per-account ban impact = -33% capacity (not -100%)
> - Justify Pro tier or future Pro+ tier (29,990)

**[เมื่อใช้]** Q: "V2 multi-profile ทำได้แน่หรือ?"

---

## A4: V3 Exploratory Features

**[ON SLIDE]**
> ## ❓ Exploratory (อาจทำไม่ได้)
> - **Hybrid Live** — technical feasibility unclear
> - **Fast snapshot account swap** — requires root/ROM
> - **Comments access** — TikTok API ไม่ public, scraping fragile
> - **GMV analytics** — TikTok Shop API access unknown
>
> ## 🔄 Likely (ทำได้ ถ้า data source แก้ได้)
> - AI Comment Reply
> - AI Insights
> - Smart Scheduling
>
> → **V3 = bet หลายทาง, ขึ้นกับ R&D outcomes**

**[เมื่อใช้]** Q: "V3 AI/Hybrid ทำได้แน่ไหม?"

---

# 🎯 EXECUTION CHECKLIST

## 1-2 วันก่อน meeting
- [ ] อ่าน script 2 รอบ + adjust ภาษาให้เป็นธรรมชาติ
- [ ] เตรียม **live demo** (signup → checkout → dashboard)
- [ ] เปิด docs ทั้ง 6 ไฟล์ (briefing, PRD, roadmap, etc.)

## วัน meeting
- [ ] Lead with **Slide 2 (The Ask)** ตรงประเด็น
- [ ] Walk 19 slides (~35 min)
- [ ] **Slide 6 (mobile decision)** = expect deep questions
- [ ] **Slide 10 (decisions)** = end with concrete asks
- [ ] **Use Appendix** เมื่อถูกถามเจาะ

## ระหว่าง Q&A
- ถ้าไม่รู้ → "open question, จะ resolve ที่ phase X"
- อย่าโต้แย้ง concern — listen + propose mitigation
- ใช้ [exec-qa-prep.md](exec-qa-prep.md) ที่เคยทำไว้สำหรับคำถาม common

## หลัง meeting
- [ ] ส่ง thank-you + recap email
- [ ] Update decision log
- [ ] ถ้า approved: kick off พรุ่งนี้
