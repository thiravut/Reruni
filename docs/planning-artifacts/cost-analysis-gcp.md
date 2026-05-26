# Infrastructure Cost Analysis — TiktokRerun

**Scope:** 200-300 devices/month sustained → 5,000+ devices at scale
**Date:** 2026-05-24
**Status:** Revised after challenge — original GCP estimate over-conservative for MVP stage

> **TL;DR:** Run MVP on **Tier A (Scrappy)** at ~1,500-3,000 บาท/เดือน Reserve Google Cloud (Tier C/D) for enterprise customers ที่ต้องการ SOC2-grade infra Pricing recommendation: **3,990 บาท/10 devices/เดือน (Starter)** — value-based, healthy 5-7% server cost ratio

---

## 1. Architecture Resource Profile

### Per-device steady-state load
| Resource | Usage |
|---|---|
| WebSocket connection | 1 persistent (heartbeat ~1KB/s, command burst ~10KB/s) |
| Video downloads | 50-200 MB per video, ~10 changes/month (cached on device after first DL) |
| Telemetry events | ~100 events/day |
| Command throughput | 5-50 commands/day per device |

### Aggregate at 300 devices
| Resource | Total |
|---|---|
| WebSocket concurrent | 300 (Go handles 10K+ on $5 VPS) |
| User actions | ~5-20 RPS peak (handful of operators) |
| DB size | 5-15 GB initial, +1 GB/month |
| Video storage total | 200 GB - 1.5 TB |
| **CDN egress** | 300-1000 GB/month ← **biggest cost variable** |

---

## 2. 4 Infrastructure Tiers Compared

| Tier | Use case | Stack | Monthly cost @ 300 devices | บาท/device |
|---|---|---|---|---|
| **A. Scrappy** | MVP, design partners | Hetzner VPS + Cloudflare R2 + Cloudflare CDN | **$30-60 (1,050-2,100 บาท)** | **3.5-7** |
| **B. Balanced** | Paid V1, mid-market | DigitalOcean managed + R2 + Cloudflare | $80-180 (2,800-6,300 บาท) | 9-21 |
| **C. GCP Standard** | Enterprise customers | Cloud Run + Cloud SQL + GCS + GCP CDN (no HA) | $255-405 (8,925-14,175 บาท) | 30-47 |
| **D. GCP HA Production** | Compliance-required customers | + HA, DR, multi-AZ | $519-825 (18,165-28,875 บาท) | 60-96 |

**Why is GCP so much more expensive?**
- GCP CDN egress: $0.08-0.12/GB ในขณะที่ Cloudflare R2 = $0 egress
- Cloud Run min-instance: $50-80/month vs $5 VPS
- Cloud SQL HA: 2x cost vs single managed Postgres
- Premium for fully-managed services + premium support tier

---

## 3. Recommended Path: Tier A → B → C/D

```
2026 Q2-Q3: POC + MVP    → Tier A (Hetzner + R2)
2026 Q4:    V1 GA        → stay Tier A unless customer demands
2027:       50+ customers → Tier B (DO managed) for stability
2027+:      Enterprise   → Tier C/D (GCP) for customers ที่ต้องการ
```

### Tier A Stack Detail (recommended MVP default)

| Service | Provider | Spec | บาท/เดือน |
|---|---|---|---|
| Backend compute | Hetzner CPX21 | 3 vCPU, 4GB RAM, 80GB SSD | ~385 |
| Database | Neon Postgres | Pro plan, 10GB | 665 |
| Cache/Queue | Upstash Redis | Pay-as-you-go ~10K req/day | 0-350 |
| Object storage | Cloudflare R2 | 500 GB | 260 |
| CDN | Cloudflare | Free tier (covers most needs) | 0 |
| DNS + SSL | Cloudflare | Free | 0 |
| Email | Resend | 3,000 emails/month free tier | 0 |
| Error tracking | Sentry | Free tier 5K events/month | 0 |
| Monitoring | Hetzner built-in + UptimeRobot | Free tier | 0 |
| Backups | Hetzner snapshots | weekly | 35 |
| Misc (domains) | | | 200 |
| **TOTAL Tier A** | | | **~1,550 บาท/เดือน** |

→ ที่ 300 devices = **~5 บาท/device/เดือน** server cost

### Tier B Stack Detail (when ready to upgrade)

| Service | Provider | Spec | บาท/เดือน |
|---|---|---|---|
| Backend compute | DigitalOcean App Platform | Pro tier | 1,750 |
| Database | DigitalOcean Managed Postgres | 2GB RAM, 60GB | 1,050 |
| Cache | DigitalOcean Managed Redis | 1GB | 525 |
| Storage | Cloudflare R2 | 1 TB | 520 |
| CDN | Cloudflare Pro | basic features | 700 |
| Misc | (same as A) | | 200 |
| **TOTAL Tier B** | | | **~4,750 บาท/เดือน** |

→ ที่ 300 devices = **~16 บาท/device/เดือน**

### Tier C/D Stack (Google Cloud — for enterprise customers)

ดู Appendix A สำหรับรายละเอียดเดิม (Cloud Run + Cloud SQL + GCS + GCP CDN)

---

## 4. Cost per Device at Different Scales

| Scale | Tier A (Scrappy) | Tier B (Balanced) | Tier C (GCP) |
|---|---|---|---|
| 50 devices | 31 บาท | 95 บาท | 200 บาท |
| 100 devices | 16 บาท | 48 บาท | 120 บาท |
| **300 devices** | **5 บาท** | **16 บาท** | **40 บาท** |
| 1,000 devices | 3 บาท | 8 บาท | 20 บาท |
| 5,000 devices | 2 บาท | 5 บาท | 12 บาท |

**Insight:** ที่ scale > 100 devices, server cost กลายเป็น noise ใน P&L — **engineering salary + customer acquisition dominate**

---

## 5. Pricing Strategy — Value-Based, Not Cost-Plus

### ❌ Why pure cost-plus (server × 1/0.30) ผิด

ถ้าใช้ logic นี้:
- Tier A @ 5 บาท × 3.3 = **16 บาท/device** = 160 บาท/10 devices
- ขาดทุนเพราะ overhead ของจริง (engineering, sales, marketing, profit) หายหมด
- Server cost เป็นแค่ ~10-20% ของ true cost ของการ run บริษัท

### ✅ Value-based pricing — เปรียบเทียบ alternatives ของลูกค้า

| Alternative | Cost/เดือน | Capability |
|---|---|---|
| Tool 3-phone Wi-Fi ADB | 1,000-2,000 บาท | broadcast only, no mid-live, no banner |
| TikMatrix engagement farm | $29-149 (1,000-5,200 บาท) | engagement only, no broadcast |
| Manual: จ้างคนคุม 10 phones | 15,000-20,000 บาท salary | full ops, สูง variable |
| **TiktokRerun (เรา)** | **?** | web + broadcast + commerce + banner = unique |

→ Sweet spot: **2,000-3,500 บาท/10 devices** (cheaper กว่า manual hire 5x, 2x ราคา cheap tool แต่ value 5x)

---

## 6. Recommended Pricing Structure (REVISED)

### 4-Tier subscription

| Tier | Devices | บาท/เดือน | บาท/device | Server cost ratio (Tier A infra) |
|---|---|---|---|---|
| **Starter** | up to 10 | **3,990** | 399 | ~5% — margin โต |
| **Growth** | up to 30 | **8,990** | 300 | ~5% |
| **Pro** | up to 100 | **19,990** | 199 | ~6% |
| **Enterprise** | 100+ | quote (120-140 บาท/device) | 120-140 | ~7% |

### Trial
- **ไม่มี free trial** — signup → choose tier → enter payment → start subscription
- Account สมัครได้ฟรี แต่ต้อง active subscription เพื่อใช้ features (pair device, upload, start live)

### Annual discount
- Pay yearly: **20% off** (industry standard)
- Reduce churn, improve cash flow

---

## 7. Revenue Projection — Revised

### Conservative (V1 6-month target)
- 30 paying Users × average 20 devices = **600 devices**
- Mix: 20 Starter + 8 Growth + 2 Pro
  - 20 × 3,990 = 79,800
  - 8 × 8,990 = 71,920
  - 2 × 19,990 = 39,980
- **MRR: ~192,000 บาท ≈ 2.3M บาท ARR**
- Infra cost (Tier A scale-up to ~3,000 บาท/เดือน) = **1.6% revenue**

### Realistic (V1 12-month target)
- 80 paying Users × average 25 devices = **2,000 devices**
- Mix: 50 Starter + 25 Growth + 5 Pro
  - 50 × 3,990 = 199,500
  - 25 × 8,990 = 224,750
  - 5 × 19,990 = 99,950
- **MRR: ~524,000 บาท ≈ 6.3M บาท ARR**
- Infra cost (Tier B ~10,000 บาท/เดือน) = **1.9% revenue**

### Optimistic (Year 2)
- 300 Users × average 30 devices = **9,000 devices**
- Mix: 150 Starter + 120 Growth + 30 Pro
  - 150 × 3,990 = 598,500
  - 120 × 8,990 = 1,078,800
  - 30 × 19,990 = 599,700
- **MRR: ~2.28M บาท ≈ 27.3M บาท ARR**
- Infra cost (still Tier B + scale ~30,000 บาท/เดือน) = **1.3% revenue**

→ Gross margin > 98% achievable — typical of vertical SaaS leaders

---

## 8. Sensitivity Analysis

ตัวแปรที่กระทบ cost มากที่สุด:

### 1. CDN egress (Tier B/C, ไม่กระทบ Tier A เพราะ Cloudflare ฟรี)
- ลูกค้าเปลี่ยน video บ่อย → egress พุ่ง
- **Mitigation:** Tier A's Cloudflare R2 has $0 egress — sidesteps this entirely

### 2. Storage growth
- ลูกค้าเก็บวิดีโอเก่าไม่ลบ → ค่า storage โต linear
- **Mitigation:** auto-archive policy (90 วันไม่ใช้ → ถูก downgrade tier หรือ ลบ)

### 3. Database query load
- User คนกด refresh บ่อย → DB QPS พุ่ง
- **Mitigation:** Redis cache aggressive, WebSocket push (ไม่ poll)

### 4. WebSocket reconnections
- เครื่อง offline ไม่ดี → reconnect spam
- **Mitigation:** exponential backoff, server-side rate limit

### 5. Engineering velocity vs infra cost trade-off
- Tier A scrappy = ต้อง engineering effort สูงกว่า (self-manage)
- Tier B/C = pay premium for managed services, save engineering time
- **At small team:** Tier A = ดีกว่าเพราะ founder time มีค่า แต่งบจำกัด
- **At larger team:** Tier B trade-off เป็น cash → time

---

## 9. ค่าใช้จ่ายเพิ่มเติม (นอก infra)

| Item | Cost/month (บาท) | When |
|---|---|---|
| Payment processing (Stripe/Omise) | 2-3% of revenue | When start charging |
| Customer support tool (Crisp/Intercom) | 1,500-3,500 | When > 10 customers |
| Sentry paid tier | 1,000-2,500 | When > 10K events/month |
| Status page (Statuspage.io) | 500-1,500 | When need uptime communication |
| Analytics (Posthog/Plausible) | 500-2,000 | Product analytics |
| Engineering tools (Linear, GitHub) | 500-1,500 | per seat |
| **Non-infra additional TOTAL** | **6,000-12,000** | At V1 maturity |

→ Total all-in opex (V1) ≈ **10,000-22,000 บาท/เดือน** (Tier B + supporting tools)
→ Still **< 5% of revenue at 80 Orgs**

---

## 10. Decisions & Recommendations

### Infrastructure
1. ✅ **Start MVP on Tier A** (Hetzner CPX21 + Cloudflare R2 + Cloudflare CDN + Neon Postgres + Upstash Redis)
2. ✅ Plan migration path to Tier B (DigitalOcean managed) ที่ 500+ devices หรือ 30+ paying Orgs
3. ✅ Reserve Tier C/D (GCP) สำหรับ enterprise customers ที่ต้องการ SOC2/compliance grade infra
4. ✅ Cloudflare R2 + CDN = **eliminate egress cost** (largest variable in original GCP estimate)

### Pricing
1. ✅ **Adopt 4-tier subscription:** Starter 3,990 / Growth 8,990 / Pro 28,990 / Enterprise quote
2. ✅ ไม่มี free trial; 20% annual discount
3. ✅ Server cost ratio **5-7%** — much healthier than 30% original target
4. ✅ Use value-based logic — compare to manual hire (15K-20K) and competitor tools (1-5K)

### Phase-gate
1. ✅ Close PRD Open Q #1 (pricing model) — DONE
2. ⚠️ Validate pricing with design partners ใน MVP beta (might adjust ±20%)
3. ⚠️ ทุก 6 เดือน review pricing vs actual cost data

---

## Appendix A: Google Cloud (Tier C/D) Detailed Breakdown — Enterprise Reference

> Original analysis preserved for reference when discussing enterprise tier with customers ที่ require GCP

### Tier C: GCP Standard (no HA)
| Service | Spec | Monthly cost |
|---|---|---|
| Cloud Run (WebSocket) | 1 vCPU, 1GB, min=1 | $50-80 |
| Cloud Run (API) | scale-to-zero | $30-50 |
| Cloud SQL Postgres (no HA) | db-g1-small + 20GB | $40-55 |
| Memorystore Redis Basic | 1 GB | $30-40 |
| Cloud Storage | 500 GB Standard | $13-20 |
| Cloud CDN | 600 GB egress APAC | $48-72 |
| Misc (egress, monitoring, etc.) | | $44-88 |
| **Tier C TOTAL** | | **$255-405** |

### Tier D: GCP HA Production
| Service | Spec change | Monthly cost |
|---|---|---|
| Cloud Run | min=2, larger | $120-200 |
| Cloud SQL HA | standby + db-custom-1-3840 + 50GB | $90-130 |
| Memorystore Redis Standard HA | 2GB | $80-110 |
| Cloud Storage | 1 TB | $26-40 |
| Cloud CDN | 1 TB | $80-120 |
| Misc + backups | | $123-225 |
| **Tier D TOTAL** | | **$519-825** |

---

## Appendix B: Pricing Comparison vs Competitors

| Product | Price (per 10 phones equivalent/month) | Capability |
|---|---|---|
| 3-phone Wi-Fi ADB tool | ~3,300 บาท (extrapolated; not multi-tenant) | broadcast only |
| TikMatrix Starter | ~3,400 บาท (engagement, not broadcast) | farm engagement |
| TikMatrix Pro | ~7,000 บาท | 20 concurrent tasks |
| TikMatrix Business | ~17,500 บาท | 100 concurrent tasks |
| **TiktokRerun Starter** | **3,990 บาท** | broadcast + commerce + banner + web |
| **TiktokRerun Growth** | **1,663 บาท/10 (8,990 ÷ 3)** | (scale discount) |

**Price-to-value:** TiktokRerun ถูกกว่า broadcast tool, capability เทียบเท่า/ดีกว่า — clear winner positioning
