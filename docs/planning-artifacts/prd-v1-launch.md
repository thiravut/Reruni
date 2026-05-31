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
- Subscription billing **flat 299 บาท/device/month** (match industry benchmark, no tier)

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
| Stripe subscription checkout | ✅ | Flat 299/device metered + Stripe Checkout |
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

### 3.10 Mobile Companion App ✅ Built (DECIDED — VCam LSPatch)

**Production path: VCam Camera2 hijack via own-built LSPosed module + LSPatch shim**

| Component | Status |
|---|---|
| Own-built VCam LSPosed module (Camera2 EGL/OES render) | ✅ Validated A15 5G Android 16 |
| LSPatch shim (non-root Xposed) | ✅ Working — no root needed |
| Companion app (Pair QR, autopilot scripts, WebSocket, foreground service) | ✅ Built |
| Autopilot scripts (JSON-driven, server-fetched) Phase A-D | ✅ Shipped |
| Audio routing (video → TikTok mic) | ✅ Working |
| Diagnostics + caps collector | ✅ Built |

**Deprecated paths (do not pursue):**
- ~~Smart Overlay (SAW + MediaProjection)~~ — quality + UX worse than VCam
- ~~Patched APK using competitor's VCAM~~ — dependency + legal risk

**Remaining:**
- Ban rate baseline via design-partner cycle (next 1-2 wk)
- APK production upload pipeline + token-gate validation in prod

### 3.11 Auth Infrastructure ✅ Built

| Feature | Status |
|---|---|
| Two-cookie session (portal vs admin) | ✅ |
| Forced password change | ✅ |
| Rate limiting on login/signup | ✅ |
| Admin role gating | ✅ |
| Stripe-scoped feature gating | ✅ |

---

## 3.12 First-Time Onboarding (DECIDED 2026-05-23)

Flat per-device pricing forces a quantity commitment at signup — V1 launches with a guided onboarding wizard that walks new users from signup to first live in one continuous flow. State persisted via `users.onboarding_step` so users can resume.

### Steps

| # | Step | Skippable | Auto-advance trigger |
|---|---|---|---|
| 1 | Signup | — | account creation |
| 2 | Welcome (quick value prop, 2-3 bullets) | no | "Next" click |
| 3 | Pick device count (slider/input × 299 = monthly total; **monthly default**, annual deferred) | no | "Continue to payment" |
| 4 | Stripe Checkout | no | webhook `checkout.session.completed` |
| 5 | Payment success confirmation | no | "Continue" click |
| 6 | Install Companion APK (**token-gated download** — requires active subscription) | **yes** | first pair attempt OR skip |
| 7 | Pair first device (QR + wait for online) | **yes** | first device `online=true` OR skip |
| 8 | Upload first video (no sample library — customer brings own content) | **yes** | first video uploaded OR skip |
| 9 | Complete (dashboard with quota badge) | — | terminal |

### Email reminders (stuck at payment step 3-4)

- Day 3 — gentle nudge
- Day 7 — second reminder
- Day 15 — final reminder

### Quota enforcement

- `paired_count < subscription.quantity` checked at `POST /api/devices/pair`
- Dashboard header always shows `paired / quota` badge
- Exceeded quota → CTA → Stripe Billing Portal to update quantity

### Pricing change mechanics

- **Upgrade mid-cycle** (10 → 12 devices day 16): Stripe prorate auto-charges 2 × 299 × (14/30) ≈ 279 บาท immediately; next cycle = 12 × 299 = 3,588 บาท
- **Downgrade mid-cycle** (10 → 7 devices): quota and monthly amount stay the same — no refund, no credit. Quota updates effective next cycle anniversary.

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
| 1 | ~~Mobile path — Smart Overlay / Patched APK / Both?~~ | — | ✅ **DECIDED 2026-05-31 — VCam LSPatch own-built** |
| 2 | **APK hosting** — token-gated download via /api/downloads/companion-apk | ✅ | Built |
| 3 | **Ban rate acceptable threshold** — กี่ %/เดือนถึงเรียกว่า production-ready? | Pond | Design-partner cycle |
| 4 | **Customer ToS draft** — ทนายร่างหรือใช้ template? | Pond + Lawyer | Before paid GA |
| 5 | **Re-patch cadence** — ใครรับผิดชอบ update VCam module เมื่อ TikTok ออกเวอร์ชั่นใหม่? | Pond | Before paid GA — 24-48hr SLA committed |
| 6 | ~~Smart Overlay maintain หรือ deprecate?~~ | — | ✅ **DECIDED 2026-05-31 — Deprecated** |
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
- [ ] Production deployment infrastructure (GCP Cloud Run + Cloud SQL + Cloudflare R2 + reruni.com DNS)
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
- **SM-2: Reach breakeven (~31 customers)** — within 90 days (flat 299 × 7.5 avg devices = ~70K MRR breakeven)
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
| VCam module breaks on TikTok app update | Medium | Module versioning + 24-48hr rebuild SLA + LSPatch shim modular |
| Ban rate higher than expected | Medium | Validate via design-partner cycle before paid GA |
| Stripe webhook unreliable in production | Medium | Recheck button + retry logic (already built) |
| Customer device incompatible with LSPatch | Low-Med | Clear supported-device list + diagnostics report |
| TikTok ToS challenge | Medium | Customer ToS shifts liability + A1 positioning |
| Single-instance backend goes down | Medium | GCP Cloud Run auto-recover + Cloud SQL HA failover + uptime monitor |
| Stripe TEST keys accidentally used in prod | Low | Deployment script gates check live key prefix |

---

## 9. V1 Resource Ask

### Team
- **Pond** — full-stack solo + product lead (founder)
- **Optional:** 1 contractor for legal/ToS review
- **Optional:** 1 contractor for video tutorial production

### Budget
- Stripe transaction fee — 2.95% + 10 บาท per transaction (built-in cost)
- GCP Cloud Run + Cloud SQL + Memorystore — ~6-8K บาท/เดือน at V1 launch scale
- Cloudflare R2 (storage + CDN) — ~500 บาท/เดือน initial (low usage)
- Domain reruni.com — 699 บาท/year
- SSL Certificate — $240/year (~8,400 บาท)
- Legal review — 50-100K one-time
- Mobile validation (if needed) — 30-50K
- Beta customer onboarding labor — Pond time

**Total V1 phase pre-revenue cost: ~120-150K** (revised down from original 200K plan based on actual velocity)

→ Full cost analysis at scale: ดู [v1-launch-presentation.md](v1-launch-presentation.md) Slide 10 (2,000 users = ~26,658 บาท/เดือน hybrid stack)

### Timeline (revised — actual + remaining)

**Plan was 8 weeks. Reality:**
- ✅ **Weeks 1-2 (done):** POC + Backend + Portal SPA + Backoffice SPA + Mobile companion + VCam module + Autopilot Phase A-D + Deployment infra + Pricing pivot + Onboarding wizard + Landing page
- ⏳ **Week 3 (in progress):** Banner Tier 2 (dynamic real-time composition) + Stripe live-mode + APK production pipeline + email Resend domain verify
- ⏳ **Week 4:** Design partner cycle (2-3 friendly TH sellers) + bug fix + legal review kick-off

→ **V1 GA target: ~4 weeks total → Q2 2026 end** (revised earlier from Q3)

---

## 10. Decisions Asked from Executive

| # | Decision | Recommendation |
|---|---|---|
| 1 | **Approve V1 GA cycle budget** (~120-150K total) | ✅ Approve — under original 200K |
| 2 | ~~Mobile path~~ | ✅ **DECIDED — VCam LSPatch own-built**, no further choice needed |
| 3 | **Legal review budget** (~50-100K) | ✅ Approve |
| 4 | **Design partner selection** | 2-3 friendly TH sellers |
| 5 | **GA target Q2 2026 end** (revised earlier from Q3) | ✅ Confirm |
| 6 | **Subscription gating without trial + flat 299/device** | ✅ Keep (decided) |
| 7 | **Two-cookie auth + admin separation** | ✅ Confirm (deployed) |

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
| 2026-05-23 | **Pricing pivot:** Tier → Flat 299 บาท/device/month (match industry benchmark) |
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
