# TiktokRerun — Release Roadmap

**Date:** 2026-06-01
**Owner:** Pond
**Audience:** Executive Sponsor + Product Team

> สรุปแผนการปล่อยแต่ละเวอร์ชั่น (V1 → V3) + features ต่อ version
> V1 มี PRD แยกที่ละเอียดกว่า ใน `prd-v1-launch.md`
>
> **Status markers:**
> - ✅ **Done** — shipped + working
> - 🟡 **In progress** — partially built, finalize within current version
> - 📅 **Planned** — engineering known, no POC needed
> - 🔄 **Likely** — needs engineering but feasibility clear
> - ❓ **POC required** — technical unknowns, must validate before commit

---

## 📅 Timeline Overview

```
2026-06 wk 1-2 │ V1   — Launch (paying customers, MVP ~done)
2026-06 wk 3   │ V1.5 — CAPTCHA + Banner Tier 2 + Stability
2026-07 wk 1-2 │ V2   — Scheduling + Playlist auto-switch + Stability Hardening
2026-07 wk 3 → 2026-08 wk 1 │ V3 — Compliance + Live AI moderation (POC-gated)
2026-08 wk 2-3 │ V4   — AI Content Creation (POC-gated)

V1 + V1.5 (MVP) = ~8 วันรวมจาก today (Pond's call, supported by 5-10x velocity proof)
V2 + V3 + V4 total = ~5-6 wk additional → all 4 versions wrap by ~late August 2026
```

---

## 🚀 V1 — Launch (June 2026, wk 1-2)

**Theme:** "Make money work — first paying customers"

**Status:** ~90% shipped (vs plan 8 wk → actual 2 wk = 8-10x velocity); ~2 wk remaining for V1 GA

### In-scope Features

| Feature | Status | Notes |
|---|---|---|
| **Web control plane** (Portal SPA — dashboard, devices, videos, lives, billing, onboarding wizard) | ✅ Done | Full operator workflows shipped |
| **QR pair flow** | ✅ Done | `/api/pair/token` + `/api/pair/qr` + Companion app scan + Pair token TTL |
| **Payment + billing** | ✅ Done | Stripe flat 299/device metered + quantity update + webhook + recheck + Customer Portal + onboarding wizard + quota enforcement |
| **VCam patch (mobile broadcast)** | ✅ Done | Own-built LSPosed module + LSPatch shim, validated Samsung A15 5G Android 16, no root |
| **Pin product mid-live** | ✅ Done | POC validated + Autopilot Accessibility script (Phase A-D shipped) |
| **Video library — multi-upload + select** | ✅ Done | Upload many videos |
| **Playlist (single combined file via ffmpeg-concat)** | ✅ Done | Operator เลือกหลายวิดีโอ → ffmpeg-concat → ephemeral video file เดียว → broadcast เป็นไฟล์เดียวยาว |
| **Banner — Tier 1 (static)** | ✅ Done | Text + color + position per video |
| **Live operations** (start / stop / switch video / restart / volume) | ✅ Done | All endpoints + Autopilot tap sequence |
| **Live metadata** (title + caption + hashtag automation) | ✅ Done | Per-live config |
| **Backoffice (admin)** (users, devices, videos, lives, metrics, subscriptions, recheck) | ✅ Done | Two-cookie scope separation deployed |
| **Mobile companion app** (foreground service, WebSocket persistent, diagnostics, caps collector) | ✅ Done | Token-gated APK download wired |
| **Deployment infra** (Google Cloud + Cloudflare R2 + Cloudflare SSL + reruni.com) | 🟡 In progress | Production switch from staging + Stripe live-mode |
| **APK production pipeline** | 🟡 In progress | Token-gate validation + Resend domain verify + design-partner distribution |

### Out-of-scope (deferred to later versions)
- **Banner Tier 2 (dynamic real-time composition)** → V1.5 (moved out of V1 scope 2026-06-01)
- **Playlist auto-switch ระหว่าง live** → V2 (POC-gated)
- Scheduling (V2)
- CAPTCHA solver (V1.5 ⭐ — operations necessity)
- Comment monitoring (V2 ❓)
- Advanced analytics (V2/V3 ❓)
- Hybrid live (V3 ❓)
- Live AI moderation (V3 ❓)
- Fake GPS (V3 ❓ POC — location simulation use case)
- AI video generation + product search (V4 / Phase 4)

### Success Criteria
- 5-10 paying customers in beta (design partners + first organic)
- Subscription conversion > 30% from signup
- Mobile broadcast stable > 7 days without major issue
- Backend uptime > 99%
- Ban rate < 30%/month (acceptable starting baseline)

### Investment
- **~25-32K cash-out** (Claude + Google Cloud + R2 + Cloudflare SSL + GitHub) for remaining 2 wk
- Founder time (Pond solo + Claude) — self-funded as equity, not in cash budget
- **Velocity proof:** ~90% MVP shipped in 2 wk vs plan 8 wk = 8-10x industry baseline

---

## 🛠️ V1.5 — Stability + CAPTCHA + Banner Tier 2 + QoL (June 2026, wk 3 — ~1 wk after V1)

**Theme:** "Make it reliable — reduce ops burden + unblock account recovery + richer live presentation"

### Features

| Feature | Status | Notes |
|---|---|---|
| **Jigsaw CAPTCHA bot** ⭐ moved up from V3 | ❓ POC required, then 🔄 ship | CV-based solver for TikTok slider/puzzle CAPTCHA — operations necessity (TikTok shows CAPTCHA after rapid relogin/reinstall cycles, blocks customer account recovery). POC: model accuracy on TikTok slider, detection risk, success rate. **~3-5 days POC** (5x compression from industry baseline ~1-2 wk via Pond + Claude). |
| **Banner Tier 2 (dynamic real-time composition)** ⭐ moved from V1 | ✅ Confirmed | Countdown / price tag / promo composite on Camera2 preview real-time; operator updates from web → push via WebSocket → render overlay in VCam pipeline. Architecture clear; engineering only — defer from V1 to keep launch scope tight. |
| Sentry error tracking + structured logging | ✅ Confirmed | Wire RESEND_API_KEY + Sentry DSN |
| Mobile auto-update companion | 🔄 Likely | In-app version check + APK update prompt |
| Re-patch SLA infrastructure (24-48hr per TikTok release) | 🔄 Likely | CI patch+sign+upload pipeline already shipped (Phase A-D); add monitoring + auto-trigger |
| Video duration probe (ffprobe shell-out) | ✅ Confirmed | Show clip length in library |
| Thumbnail generation + video preview | ✅ Confirmed | Preview in library before assign |
| Backoffice: server-side sort, CSV export | ✅ Confirmed | |
| Live History: true total count, advanced filters, CSV export | ✅ Confirmed | |
| Email notifications (live ended, payment failed, suspension) | ✅ Confirmed | Resend wired in V1, templates in V1.5 |
| Banner template library + preset designs | ✅ Confirmed | Curated Tier 2 banners |
| Annual billing + 20% discount, prorations | ✅ Confirmed | Stripe handles proration; UI toggle |
| Better OEM compatibility (Xiaomi, Oppo, Realme) | 🔄 Likely | Supported-device list + diagnostics caps |

### Why Jigsaw CAPTCHA bot belongs in V1.5 (not V3)

- **It's not a "smart" feature — it's an operations workaround.** Customers get locked out of TikTok login after CAPTCHA appears; without a solver, they manually solve dozens of CAPTCHAs per device when onboarding or after a ban event.
- **Memory confirms the pain:** TikTok shows CAPTCHA after a handful of rapid reinstall+login cycles
- **Multi-profile rotation drop (2026-05-31)** means we explicitly can't dodge CAPTCHA by switching accounts → solving the CAPTCHA itself becomes the only path forward
- Confidence after POC = 🔄 Likely (CV CAPTCHA solvers are well-studied; main unknown is TikTok's specific puzzle variants + detection)

### Success Criteria
- 30+ paying customers
- Churn < 10%/month
- Support tickets < 2 per customer/month
- Mobile compatibility on top 5 Android OEMs
- CAPTCHA solver success rate > 80% (unblock customer onboarding flow)

---

## 📈 V2 — Scheduling + Playlist auto-switch + Stability Hardening (July 2026, wk 1-2)

**Theme:** "Make it auto-pilot — scheduling + reliability without operator handholding"

> ### ❌ Removed from V2 scope (2026-05-31)
> Multi-profile rotation (TikTok account rotation per device) was originally V2's centerpiece (✅ Confirmed via TikTok's native account switcher). **Dropped** due to TikTok account safety risk — CAPTCHA challenges observed after rapid relogin/swap cycles. Rotation tooling would amplify this at scale.
>
> Customers stay 1 phone = 1 TikTok account through V2. Reruni's differentiation remains: web control, multi-device fleet, dynamic banner, no-root LSPatch, multi-tenant SaaS.

### Features
| Feature | Confidence |
|---|---|
| Scheduling (time-based start/stop, recurring) | ✅ Confirmed |
| **Playlist auto-switch ระหว่าง live** (swap video mid-broadcast without stopping) — POC: VCam Camera2 pipeline swap video source ได้ไหมโดย TikTok ไม่ drop? **Effort 2-3 days** (compressed from 1 wk industry baseline). Fallback if POC fails: ใช้ V1 ffmpeg-concat single-file approach (no UX downgrade) | ❓ POC required |
| Stability hardening — auto-reconnect tuning, VCam module monitoring + fallback flow | ✅ Confirmed |
| Per-device health score (online %, broadcast uptime, error rate) | ✅ Confirmed |
| Alert system (live ตก, device offline > N นาที, error spike) | ✅ Confirmed |
| Per-device live history + viewer count | 🔄 Likely (track local) |
| Comment Monitoring (real-time feed + keyword alerts) | ❓ Exploratory — ต้อง access comments ผ่าน TikTok API (ไม่มี public) หรือ scrape (fragile) |
| Comment moderation (delete/ban viewer) | ❓ Exploratory — same dep |
| Analytics: GMV per device | ❓ Exploratory — ต้อง TikTok Shop API access (ไม่รู้ว่าเปิดให้ third-party หรือไม่) |
| Onboarding: self-serve tutorial + in-app tour | ✅ Confirmed |
| NPS surveys + retention analytics | ✅ Confirmed |

### V2 Customer Workflow (Scheduling)
```
Setup:
1. Customer ใน Web dashboard: เลือก device + วิดีโอ + ตั้ง schedule
   เช่น "เปิด 09:00 หยุด 23:00 ทุกวัน, swap video ทุก 2 ชม."

Runtime:
1. Scheduler ถึงเวลา → companion app start live
2. Playlist rotation: auto-switch video ตามรอบ
3. Health monitor: ถ้า live ตก/error spike → alert ลูกค้า + auto-restart
4. ถึงเวลาหยุด → companion app stop live
```

### Success Criteria
- 80+ paying customers
- ARR 2.15M+ (80 users × 7.5 devices × 299 × 12 at flat pricing)
- Broadcast uptime per device ≥ 95% over 7 days
- Auto-restart success rate ≥ 90% (when broadcast drops mid-session)
- Customer NPS > 40

---

## 🤖 V3 — Compliance + Live AI Moderation (July wk 3 → August wk 1, 2026 — POC-gated)

**Theme:** "Make live broadcasts safer — location simulation + real-time speech moderation"

> **Removed from V3 scope (2026-06-01):**
> - AI Video Generation → moved to V4 (Phase 4)
> - Product/Video Search + AI gen pipeline → moved to V4 (Phase 4)
> V3 now focuses on operational compliance + live moderation; content creation features bundled separately as V4

### Features

| Feature | Status | POC questions to answer |
|---|---|---|
| **Fake GPS — location simulation** ⭐ NEW | ❓ POC required | LSPosed hook ที่ Android Location APIs ได้ไหม? TikTok detect Mock Location ผ่าน Play Integrity? **Use case (clarified 2026-06-01):** ลูกค้าตั้งได้ว่าจะให้ TikTok เห็นว่าโทรศัพท์ live อยู่ที่ไหน — เลือกจังหวัด/พิกัดเอง (ไม่ใช่ anti-fraud, แต่เป็น region selection UX). **Effort: 1-2 วัน POC** (compressed from 3-5 วัน — LSPosed hook is small) |
| **Live Insight — forbidden word check + scoring + improvement tips real-time** ⭐ NEW | ❓ POC required | STT Thai latency < 2s? Forbidden word list ที่ TikTok ใช้ (extract จาก ban history)? Scoring algo (engagement + compliance + delivery)? Operator alert UX? Audio capture path บน Android (companion app intercept?). **Effort: 3-5 วัน POC** (compressed from 2-3 wk — most is STT + LLM API integration, not novel research) |
| **Real-time forbidden-word censor (bleep)** ⭐ NEW | ❓ POC required | Audio buffer N วินาทีได้ไหมโดยไม่ติด TikTok delay limit? Beep injection จุดไหนใน VCam pipeline? STT precision พอไหม (false positive = bleep ผิดคำ)?. **Effort: ~1 wk POC — long pole of V3** (compressed from 3-4 wk; audio pipeline complexity is genuine but solvable in days with focused work) |
| **Hybrid Live** — creator joins live in real-time, takes over from broadcast | ❓ Exploratory | Technical feasibility of mid-stream control handoff unclear; depends on TikTok's live API behavior |
| **AI Comment Reply** with operator approval | 🔄 Likely | LLM API call — depends on comments access (see V2 ❓) |
| **AI Insights** — "Best performing video" / "When to switch product" | 🔄 Likely | Depends on analytics data (see V2 ❓ GMV) |
| **Smart Scheduling** — algorithm picks optimal live times | 🔄 Likely | Needs past performance data from V2 history |
| **Auto Banner** — AI generates banner copy from video content | 🔄 Likely | LLM + video frame analysis |
| **Fraud Detection** — bot patterns, abuse signals | 🔄 Likely | Internal telemetry analysis |
| **Advanced Pin** — multi-product rotation, auto-pin by video timestamp | ✅ Confirmed | Engineering only; schema ready |

### V3 POC Sequencing (compressed)

```
Days 1-2          Days 3-7                Days 8-14 (long pole)
─────────────     ─────────────────       ─────────────────────────
[Fake GPS POC]    [Live Insight POC]      [Real-time Censor POC]
  1-2 วัน          3-5 วัน                  ~1 wk (audio pipeline)
                  (parallel with GPS ship)  (depends on STT validation)
```

V3 wraps ~2 wk total (mid-July → end of July 2026).

Each POC has a kill-switch gate — if feasibility unclear after timeboxed effort, defer or drop. V3 ship scope = features ที่ผ่าน POC gate

### Success Criteria
- 300+ paying customers
- ARR 8M+ (300 users × 7.5 devices × 299 × 12 baseline)
- Live insight + censor adoption > 50% of paying customers
- Live insight reduces ban rate by ≥ 30% vs V1 baseline
- CAPTCHA + Fake GPS + Censor combined = "Compliance Pack" subscription add-on

---

## 🎨 V4 — AI Content Creation (August 2026, wk 2-3 — POC-gated)

**Theme:** "Make content creation effortless — search existing assets + AI-generate fallback"

> Phase 4 bundles all generative-AI features into one release; V3 stays focused on operational compliance.

### Features

| Feature | Status | POC questions to answer |
|---|---|---|
| **AI Video Generation** ⭐ moved from V3 | ❓ POC required | Provider ไหน (Veo / Sora / Runway / Pika)? Cost per video? Latency (sync vs async)? Quality ขายของพอไหม? Thai voice support? **Effort: 2-3 days POC + pricing analysis** (compressed from 1-2 wk — mostly API integration) |
| **Product Search** (find listings on TikTok Shop ที่ลูกค้าอยากนำมา live) | ❓ POC required | Data source: TikTok Shop API ไม่ public — scrape feasibility? Search relevance (keyword vs semantic)? Rate limit risk? **Effort: 3-5 days POC** (compressed from 2-3 wk) |
| **Existing Video Search** (find live video corpus ที่ตรงกับสินค้า) | ❓ POC required | Video index source (TikTok video search? Affiliate platforms?). Relevance scoring. License/usage rights consideration. **Effort: 3-5 days POC** (parallel with product search) |
| **End-to-end pipeline: search → if no video → AI gen → push to library** ⭐ killer feature | ❓ POC required | UX flow: operator search สินค้า → ระบบตอบ "มีวิดีโออยู่แล้ว ใช้เลย" หรือ "ไม่มี — AI gen ให้ภายใน 5 นาที" → push เข้า library → ready for live. **Effort: ~1 wk POC** (compressed from 2-3 wk; depends on AI video + search POCs above) |
| **Asset library** (saved generated videos + curated stock) | 🔄 Likely | DB schema + R2 storage extension |
| **Video editing assistance** (trim, watermark removal, voiceover dub) | ❓ Exploratory | Provider availability for Thai voice + lip-sync |

### V4 POC Sequencing (compressed)

```
Days 1-3                    Days 4-8 (parallel)             Days 9-14
─────────────────           ──────────────────────────      ──────────────────────
[AI Video Gen POC]          [Product Search POC]            [End-to-end pipeline]
  2-3 วัน                    3-5 วัน                          ~1 wk
                            [Existing Video Search POC]      (depends on prev two)
                              3-5 วัน (parallel)
```

V4 wraps ~2 wk total (August wk 2 → wk 3, 2026).

### Success Criteria
- 500+ paying customers
- AI content add-on adoption > 30% of customers
- AI video gen unit cost < 50 บาท/video (gross margin sustainable)
- Time-to-first-live for new product < 10 นาที (search → gen → broadcast)

### Pricing implication
- Usage-based: per AI video generation (e.g., 50-100 บาท/video) on top of flat 299/device base
- หรือ tier upgrade "Creator Pack" (fixed monthly add-on with N gen credits)

---

## 🎯 What This Roadmap Says About the Business

**V1 (now, ~June wk 1-2):** Get web control + multi-device fleet + flat 299 pricing into market → 5-10 paying customers
**V1.5 (June wk 3):** CAPTCHA unblocks customer onboarding + Banner Tier 2 + stability → 30 customers
**V2 (July wk 1-2):** Auto-pilot scheduling + playlist auto-switch → reduce operator handholding → 80 customers
**V3 (July wk 3 → Aug wk 1):** Compliance + Live AI moderation (Live insight + censor + Fake GPS) → 300 customers
**V4 (Aug wk 2-3):** AI Content Creation (product search + AI video gen pipeline) → 500+ customers

**Total V1 → V4 timeline:** ~2-2.5 เดือนจาก today, assuming 5-10x velocity proof holds. Customer acquisition runs in parallel — version numbers reflect ship cadence, not customer count gates.

**Pricing implication of post-V1 features:**
- All add-ons above flat 299/device base
- **V3 = "Compliance Pack"** subscription (Live insight + censor + Fake GPS bundle)
- **V4 = "Creator Pack"** — usage-based AI video gen credits, or fixed monthly tier
- Final pricing determined after POC outcomes per feature

---

## 📋 Cross-version Open Questions

| # | Question | Decision needed by |
|---|---|---|
| 1 | ~~Mobile path~~ — DECIDED 2026-05-31: VCam LSPatch own-built (no root) | ✅ Resolved |
| 2 | TikTok Shop API access for GMV/analytics — available to third-party? | V2 design |
| 3 | Comments access — TikTok API, scraping, or skip feature? | V2 design |
| 4 | AI cost model — pass-through OR absorbed in tier price? | V3 design (depends on AI video POC pricing) |
| 5 | CAPTCHA solver self-host (CV model) vs paid API (CapMonster/2Captcha)? | V1.5 POC |
| 6 | ~~Fake GPS — needed for region targeting only, or anti-fraud signal too?~~ — clarified 2026-06-01: location simulation UX only (operator picks province/coord) | ✅ Resolved |
| 7 | Live insight + real-time censor — operator-facing only, or auto-action (cut live)? | V3 POC + customer feedback |
| 8 | AI video gen — provider choice + Thai voice + per-unit cost ceiling | V4 POC (was V3, moved 2026-06-01) |
| 9 | Playlist auto-switch — VCam pipeline can swap video source mid-live without TikTok drop? | V2 POC; fallback = stay with V1 single-file approach |

---

## Reference Documents

- V1 detailed PRD: `prd-v1-launch.md`
- Original PRD (V0/POC era): `prds/prd-TiktokRerun-2026-05-24/prd.md`
- System Overview: `system-overview.md`
- Mobile Strategy: `mobile-strategy-presentation.md`
- Cost Analysis: `cost-analysis-gcp.md`
- Decision Log: `prds/prd-TiktokRerun-2026-05-24/.decision-log.md`

---

## Appendix — What's NOT in this roadmap

**Explicitly deferred without target date:**
- ❌ International / SEA expansion (per Pond direction 2026-05-31)
- ❌ Multi-user team / org abstraction / role-based permissions
- ❌ Agency-focused features (invite, brand workspaces)
- ❌ Native mobile app (Android primary, iOS no plan)
- ❌ Public REST API for third-party integration

ถ้าตลาดเปลี่ยน + มี demand validated ค่อยพิจารณาเพิ่ม
