---
title: TiktokRerun V1 — Launch PRD
version: 1.0
status: ready for review
created: 2026-05-31
updated: 2026-05-31
---

# PRD: TiktokRerun V1 — Launch

**Theme:** "Make money work — ship to first paying customers"
**Target launch:** 2026 Q3 (Jul-Sep)
**Status of infrastructure:** 90% built — needs mobile validation + final polish
**Audience:** Executive Sponsor, Engineering, Design, GTM

> นี้คือ tactical PRD สำหรับ V1 launch — strategic roadmap V1-V3 อยู่ที่ [release-roadmap.md](release-roadmap.md)

---

## 0. Document Purpose

PRD นี้ระบุ **scope ของ V1 launch** ที่จะส่งถึง paying customers รายแรก เป้าหมาย:
1. Lock V1 feature list (in-scope / out-of-scope)
2. ระบุ open decisions ที่ต้องตัดสินก่อน launch
3. ให้ engineering + design มี single source of truth
4. ให้ exec อนุมัติ resource สำหรับช่วง MVP → launch

**Inputs ที่อ้างอิง:**
- Original PRD: `prds/prd-TiktokRerun-2026-05-24/prd.md` (vision + glossary + FRs)
- Decision log: ตัดสินทั้งหมดที่เกิดขึ้นถึง 2026-05-31
- Mobile strategy: `mobile-strategy-presentation.md`

---

## 1. V1 Vision (One Paragraph)

**TiktokRerun V1** ให้ solo TikTok Shop seller ในไทย run live commerce หลายบัญชีพร้อมกัน 24/7 จาก web dashboard ด้วย:
- Mobile companion app (Android) วางบนโทรศัพท์ที่ login TikTok seller account
- Web dashboard ที่ operator สั่ง broadcast video, ปักตะกร้าสินค้า, เปลี่ยน banner real-time
- Subscription billing 3 tier (Starter 3,990 / Growth 8,990 / Pro 19,990 บาท/เดือน)

**ลูกค้าได้:** Live ตลอด 24/7 ครอบคลุมทุกบัญชี โดยไม่ต้องจ้างคน + เปลี่ยน promo กลาง live ได้

---

## 2. Target User (V1)

### Primary Persona — "ตอง"

Solo TikTok Shop seller, อายุ 24-38, มี 3-15 บัญชี TikTok Shop หลาย category
- เริ่ม 1-3 phones, scale ถึง 10-20 phones
- คนเดียว ทำทุกอย่าง — founder + operator + customer service
- ปัจจุบันใช้ tool 3-phone PC ที่ขาด feature
- ใช้ TikTok Shop เป็นช่องทางหลัก (>60% รายได้)
- เป้า: ขยาย live coverage โดยไม่จ้างคน

### Non-Users (V1)
- Solo creator 1 บัญชี — overspec
- Enterprise compliance (SOC2, formal SLA) — defer ไม่กำหนด

---

## 3. V1 Feature List

### 3.1 Account & Billing ✅ Built

| Feature | Status | Notes |
|---|---|---|
| Email/password signup | ✅ | Portal at `/signup` |
| Email/password login | ✅ | Two-cookie session (portal/admin) |
| Forced password change after admin reset | ✅ | `/change-password` |
| Stripe subscription checkout | ✅ | 3 tiers + Stripe Checkout |
| Subscription gating (no trial) | ✅ | Feature endpoints blocked if not active |
| Admin recheck button (webhook fallback) | ✅ | Backoffice `/admin/subscriptions` |
| Self-serve billing portal | ✅ | Stripe Customer Portal |
| Cancel subscription | ✅ | Cancel at period end |

### 3.2 Device Management ✅ Built

| Feature | Status |
|---|---|
| QR pair flow | ✅ |
| Fleet view (real-time) | ✅ |
| Device status (online/offline/live/error) | ✅ |
| Group/tag devices | ✅ |
| Rename device | ✅ |
| Unpair device | ✅ |

### 3.3 Video Library ✅ Built

| Feature | Status |
|---|---|
| Upload video (multipart) | ✅ |
| List + delete | ✅ |
| Basic metadata (name, tag) | ✅ |

> **V1.5 deferral:** Duration probe, thumbnail, preview

### 3.4 Live Operations ✅ Built

| Feature | Status |
|---|---|
| Start live (multi-device select) | ✅ |
| Stop live | ✅ |
| Switch broadcast video mid-live | ✅ |
| Restart failed live | ✅ |
| Volume control per device | ✅ |
| Live status real-time (WebSocket) | ✅ |
| Live history (past sessions) | ✅ |

### 3.5 TikTok Shop Product Control ✅ Built

| Feature | Status |
|---|---|
| Pin product to live | ✅ |
| Unpin product | ✅ |
| Switch pinned product | ✅ |
| Out-of-stock warning | ⚠️ Needs TikTok Shop API integration (V1.5) |

### 3.6 Banner & Overlay ✅ Built

| Feature | Status |
|---|---|
| Static banner (attached to video) | ✅ |
| Dynamic banner (real-time from web) | ✅ |
| Countdown banner | ✅ |
| Banner editor UI | ✅ Basic — V1.5 will add template library |

### 3.7 Live Metadata ✅ Built

| Feature | Status |
|---|---|
| Live title | ✅ |
| Live caption | ✅ |
| Hashtags | ✅ |
| Companion app fills via Accessibility | ✅ |

### 3.8 Web Dashboard (Portal) ✅ Built

| Page | Status |
|---|---|
| `/login`, `/signup`, `/change-password` | ✅ |
| `/dashboard` (stats overview) | ✅ |
| `/devices` | ✅ |
| `/videos`, `/videos/:id/banners` | ✅ |
| `/live`, `/live/active`, `/history` | ✅ |
| `/subscribe`, `/billing`, `/billing/success`, `/billing/cancel` | ✅ |
| Mobile-responsive | ✅ |

### 3.9 Backoffice (Admin) ✅ Built

| Page | Status |
|---|---|
| `/admin/login` | ✅ |
| `/admin/metrics` | ✅ |
| `/admin/users` (with role/reset/delete) | ✅ |
| `/admin/devices` (with force-disconnect) | ✅ |
| `/admin/videos` (disk usage) | ✅ |
| `/admin/lives` (with force-stop) | ✅ |
| `/admin/subscriptions` (with recheck) | ✅ |

### 3.10 Mobile Companion App ⚠️ Decision Pending

**Current state:**
- Smart Overlay POC validated (no root, BYOD)
- Patched TikTok APK + VCAM tested (Samsung A15 5G) — using competitor's APK
- Ban rate baseline NOT yet measured

**V1 Decision Required:**
- **Path A:** Smart Overlay (POC original) — proven, BYOD, lower quality
- **Path B:** Patched APK + VCAM — better quality, dependency on competitor, untested ban rate
- **Path C:** Both — Smart Overlay for entry users, patched APK for advanced

See §5 Open Decisions.

### 3.11 Auth Infrastructure ✅ Built

| Feature | Status |
|---|---|
| Two-cookie session (portal vs admin) | ✅ |
| Forced password change | ✅ |
| Rate limiting on login/signup | ✅ |
| Admin role gating | ✅ |
| Stripe-scoped feature gating | ✅ |

---

## 4. V1 Out-of-Scope (Deferred)

ทุก feature ข้างล่างจะเข้า **V1.5 หรือใหม่กว่า** ไม่อยู่ใน V1 launch

> **Confidence markers:**
> - ✅ **Confirmed** — ทำได้แน่ (proven หรือ engineering ตรงๆ)
> - 🔄 **Likely** — เชื่อว่าทำได้ (mostly engineering, dependency known)
> - ❓ **Exploratory** — ยังไม่รู้, ต้อง R&D ก่อน commit

- Multi-profile rotation (TikTok native switcher + Accessibility automation) — V2 ✅
- Scheduling (time-based start/stop) — V2 ✅
- Playlist rotation (auto-switch video) — V2 ✅
- Sentry error tracking — V1.5 ✅
- Email notifications — V1.5 ✅
- Banner template library — V1.5 ✅
- Video duration probe / thumbnail — V1.5 ✅
- Mobile auto-update mechanism — V1.5 🔄
- Comment monitoring + moderation — V2 ❓ (depends on TikTok API/scrape access)
- Advanced analytics (GMV, conversion) — V2/V3 ❓ (depends on TikTok Shop API)
- Out-of-stock warning — V2 ❓ (depends on TikTok Shop API)
- Hybrid live (human takeover) — V3 ❓ (technical feasibility unclear)
- Fast snapshot account swap — V3 ❓ (only if V2 native switcher proves too slow)
- AI Comment Reply / AI Insights — V3 🔄 (depends on data sources)
- iOS support — no plan
- Public REST API — no plan
- Multi-user team / org / agency features — no plan
- International / SEA expansion — no plan

---

## 5. Open Decisions (Must Resolve Before Launch)

| # | Decision | Owner | Deadline |
|---|---|---|---|
| 1 | **Mobile path — Smart Overlay / Patched APK / Both?** | Pond | Before launch |
| 2 | **Patched APK hosting** — เราโฮสต์, ลิงค์ไปคู่แข่ง, หรือ bundle ใน companion? | Pond + Legal | Before launch (if Path B) |
| 3 | **Ban rate acceptable threshold** — กี่ %/เดือนถึงเรียกว่า production-ready? | Pond | Before launch |
| 4 | **Customer ToS draft** — ทนายร่างหรือใช้ template? | Pond + Lawyer | Before paid GA |
| 5 | **Re-patch cadence** — ใครรับผิดชอบ update เมื่อ TikTok ออกเวอร์ชั่นใหม่? | Pond | Before launch |
| 6 | **Smart Overlay maintain หรือ deprecate?** | Pond | Within V1 |
| 7 | **Auto-update mechanism** — ลูกค้า manual download หรือ automated? | Pond | V1.5 |

---

## 6. Pre-Launch Checklist

### Engineering Readiness
- [x] Backend (Go) — auth, billing, devices, lives, banners, admin endpoints, WebSocket
- [x] Portal SPA — all routes, gated by subscription
- [x] Backoffice SPA — all admin pages, recheck button
- [x] Stripe integration — checkout, webhook, portal, sync
- [x] Two-cookie session separation
- [x] Tests passing across all 3 codebases
- [ ] Mobile companion — path decided + production build
- [ ] Production deployment infrastructure (Hetzner + Cloudflare R2 + reruni.com DNS)
- [ ] Auto-deploy pipeline
- [ ] Backup strategy (daily snapshots)

### Product Readiness
- [ ] 2-3 design partners onboarded for beta
- [ ] Customer onboarding doc + video tutorial
- [ ] Pricing page (with Thai language)
- [ ] Landing page (reruni.com)
- [ ] Support channel (LINE/Email)

### Legal Readiness
- [ ] Customer Terms of Service (ban risk transfer clause)
- [ ] Privacy Policy (Thai PDPA compliant)
- [ ] Cookie consent (if needed for EU traffic)
- [ ] Refund policy

### Operational Readiness
- [ ] Stripe live keys (not test)
- [ ] Stripe webhook secret in production
- [ ] Production database backup
- [ ] Server monitoring (uptime tracking)
- [ ] Support response SLA agreed

---

## 7. Success Metrics (V1)

### Primary
- **SM-1: First 10 paying customers** — within 30 days of launch
- **SM-2: MRR ≥ 100K บาท** — within 60 days
- **SM-3: Subscription conversion** — ≥ 30% of signups complete checkout
- **SM-4: Backend uptime** — ≥ 99% measured over 30 days

### Secondary
- **SM-5: Time-to-first-live** — median ≤ 30 minutes from signup
- **SM-6: Mobile broadcast uptime** — ≥ 95% of started lives reach 1hr
- **SM-7: Ban rate per account** — ≤ 30%/month (acceptable starting baseline)
- **SM-8: Customer NPS** — ≥ 30 from beta cohort

### Counter-metrics (don't optimize)
- **SM-C1: Total broadcast hours** — using this as a goal incentivizes risky 24/7 broadcasts
- **SM-C2: Refunds processed** — reducing refunds by gating cancellations hurts customer trust

---

## 8. V1 Launch Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Mobile companion fails at scale | High | Validate ban rate before public launch + fallback Path C |
| Stripe webhook unreliable in production | Medium | Recheck button + retry logic (already built) |
| Customer brick during patched APK install | Medium | Clear waiver + supported device list |
| TikTok ToS challenge | Medium | Customer ToS shifts liability + A1 positioning |
| Single-instance backend goes down | Medium | Hetzner snapshots + DB backup + uptime monitor |
| Stripe TEST keys accidentally used in prod | Low | Deployment script gates check live key prefix |

---

## 9. V1 Resource Ask

### Team
- **Pond** — full-stack solo + product lead (founder)
- **Optional:** 1 contractor for legal/ToS review
- **Optional:** 1 contractor for video tutorial production

### Budget
- Stripe transaction fee — 2.95% + 10 บาท per transaction (built-in cost)
- Hetzner VPS — ~500 บาท/เดือน
- Cloudflare R2 — ~300 บาท/เดือน
- Domain reruni.com — ~500 บาท/year
- Legal review — 50-100K one-time
- Mobile validation (if needed) — 30-50K
- Beta customer onboarding labor — Pond time

**Total V1 phase pre-revenue cost: ~100-200K**

### Timeline
- **Weeks 1-2:** Mobile companion final decision + validation (ban rate test)
- **Weeks 3:** Production deployment + DNS setup + auto-deploy pipeline
- **Week 4:** Legal review + ToS finalization
- **Week 5-6:** Beta with 2-3 design partners
- **Week 7-8:** Iterate + launch

→ V1 GA target: **Q3 2026 (within 8 weeks)**

---

## 10. Decisions Asked from Executive

| # | Decision | Recommendation |
|---|---|---|
| 1 | **Approve V1 launch budget** (~100-200K) | ✅ Approve |
| 2 | **Mobile path — recommend Smart Overlay primary + patched APK pilot** | ✅ Hedge both initially |
| 3 | **Legal review budget** (~50-100K) | ✅ Approve |
| 4 | **Design partner selection** | 2-3 friendly TH sellers |
| 5 | **GA target Q3 2026** | ✅ Confirm |
| 6 | **Subscription gating without trial** | ✅ Keep (already decided) |
| 7 | **Two-cookie auth + admin separation** | ✅ Confirm |

---

## 11. What Happens After V1 Launch

→ See `release-roadmap.md`:
- **V1.5 (Q4 2026):** Stability + QoL — reduce ops burden
- **V2 (Q1 2027):** Multi-profile rotation ✅ + scheduling ✅ + comments ❓
- **V3 (Q2 2027):** AI features 🔄 + hybrid live ❓ (exploratory)

---

## Appendix A — Recent Major Decisions Affecting V1

| Date | Decision |
|---|---|
| 2026-05-23 | POC validated, MVP scope locked |
| 2026-05-26 | Pricing tiers: 3,990 / 8,990 / 19,990 บาท (no free trial) |
| 2026-05-26 | Persona pivot: Agency → Solo Seller |
| 2026-05-26 | Stripe integration done — 6 endpoints + webhook |
| 2026-05-26 | Forced password change after admin reset |
| 2026-05-29 | Mobile two-tier strategy proposed (BYOD + Pro) |
| 2026-05-31 | Mobile breakthrough — patched APK working w/o root (using competitor's) |
| 2026-05-31 | Two-cookie session separation deployed |

---

## Appendix B — Reference Documents

- Release Roadmap (V1-V3): `release-roadmap.md`
- Original PRD (V0/POC era): `prds/prd-TiktokRerun-2026-05-24/prd.md`
- System Overview: `system-overview.md`
- Mobile Strategy: `mobile-strategy-presentation.md`
- Tech Spec: `tech-spec.md`
- API Contract: `api-contract.md`
- Cost Analysis: `cost-analysis-gcp.md`
- Decision Log: `prds/prd-TiktokRerun-2026-05-24/.decision-log.md`
