# TiktokRerun — Release Roadmap

**Date:** 2026-05-31
**Owner:** Pond
**Audience:** Executive Sponsor + Product Team

> สรุปแผนการปล่อยแต่ละเวอร์ชั่น (V1 → V3) + features ต่อ version
> V1 มี PRD แยกที่ละเอียดกว่า ใน `prd-v1-launch.md`
>
> **Confidence markers** (ใช้ใน V2-V3 features):
> - ✅ **Confirmed** — ทำได้แน่ (built หรือ proven)
> - 🔄 **Likely** — เชื่อว่าทำได้ (mostly engineering)
> - ❓ **Exploratory** — ยังไม่รู้ — ต้อง R&D ก่อน commit

---

## 📅 Timeline Overview

```
2026 Q3 (Jul-Sep) │ V1 — Launch (paying customers)
2026 Q4 (Oct-Dec) │ V1.5 — Stability + Quality of Life
2027 Q1 (Jan-Mar) │ V2 — Multi-profile + Scheduling
2027 Q2 (Apr-Jun) │ V3 — Pro Features + AI Assist
```

---

## 🚀 V1 — Launch (Q3 2026)

**Theme:** "Make money work — first paying customers"

**Status:** Infrastructure 90% built, mobile validation in progress

### In-scope Features
| Group | Features |
|---|---|
| **Account & Billing** | Signup, login, password change, Stripe checkout, 3 tiers (Starter/Growth/Pro), recheck button |
| **Device Management** | QR pair, fleet view, status real-time, group |
| **Video Library** | Upload, list, delete, basic metadata |
| **Live Operations** | Start/stop live, switch video, restart, volume control |
| **TikTok Shop** | Pin/unpin/switch product mid-live |
| **Banner** | Static banner (Tier 1) + dynamic real-time (Tier 2) |
| **Live Metadata** | Title + caption + hashtag automation |
| **Web Dashboard (Portal)** | All operator workflows |
| **Backoffice (Admin)** | Users, devices, videos, lives, metrics, subscriptions, recheck |
| **Mobile Companion** | Patched APK OR Smart Overlay (decision pending) |
| **Auth** | Two-cookie separation (portal + admin) |

### Out-of-scope (deferred to later versions)
- Scheduling
- Comment monitoring
- Advanced analytics
- Hybrid live (human takeover)
- AI features

### Success Criteria
- 5-10 paying customers in beta (design partners + first organic)
- Subscription conversion > 30% from signup
- Mobile broadcast stable > 7 days without major issue
- Backend uptime > 99%
- Ban rate < 30%/month (acceptable starting baseline)

### Investment
- ~100-200K (mobile validation + final polish)
- Pond solo + Claude

---

## 🛠️ V1.5 — Stability + Quality of Life (Q4 2026)

**Theme:** "Make it reliable — reduce ops burden"

### Features
| Feature | Confidence |
|---|---|
| Sentry error tracking + structured logging | ✅ Confirmed |
| Mobile auto-update companion | 🔄 Likely |
| Re-patch SLA infrastructure (24-48hr per TikTok release) | 🔄 Likely — depends on mobile path decision |
| Video duration probe (ffprobe shell-out) | ✅ Confirmed |
| Thumbnail generation + video preview | ✅ Confirmed |
| Backoffice: server-side sort, CSV export | ✅ Confirmed |
| Live History: true total count, advanced filters, CSV export | ✅ Confirmed |
| Email notifications (live ended, payment failed, suspension) | ✅ Confirmed |
| Banner template library + preset designs | ✅ Confirmed |
| Annual billing + 20% discount, prorations | ✅ Confirmed |
| Better OEM compatibility (Xiaomi, Oppo, Realme) | 🔄 Likely — depends on mobile path |

### Success Criteria
- 30+ paying customers
- Churn < 10%/month
- Support tickets < 2 per customer/month
- Mobile compatibility on top 5 Android OEMs

---

## 📈 V2 — Multi-profile + Scheduling (Q1 2027)

**Theme:** "Make it ban-resistant — TikTok account rotation per device"

### Features
| Feature | Confidence |
|---|---|
| **Multi-profile rotation** — ลูกค้า login หลาย accounts ล่วงหน้าใน TikTok (native switcher); companion app ใช้ Accessibility Service กด switch ตาม rotation schedule | ✅ Confirmed — TikTok's built-in account switcher รองรับ 5+ accounts/app, แค่ automate UI taps |
| Profile management UI (add/remove/label accounts, rotation schedule) | ✅ Confirmed |
| Per-profile live history | ✅ Confirmed (schema มีรองรับ) |
| Per-profile ban detection + auto-skip in rotation | 🔄 Likely (track local) |
| Scheduling (time-based start/stop, recurring) | ✅ Confirmed |
| Playlist rotation (auto-switch video) | ✅ Confirmed |
| Comment Monitoring (real-time feed + keyword alerts) | ❓ Exploratory — ต้อง access comments ผ่าน TikTok API (ไม่มี public) หรือ scrape (fragile) |
| Comment moderation (delete/ban viewer) | ❓ Exploratory — same dep |
| Analytics: live hours + viewer count history | 🔄 Likely (track local) |
| Analytics: GMV per account | ❓ Exploratory — ต้อง TikTok Shop API access (ไม่รู้ว่าเปิดให้ third-party หรือไม่) |
| Onboarding: self-serve tutorial + in-app tour | ✅ Confirmed |
| NPS surveys + retention analytics | ✅ Confirmed |

### V2 Customer Workflow (Multi-profile)
```
Setup (one-time):
1. Customer ใน TikTok app: login @account1 → switch → login @account2 → ...
2. Companion app: detect accounts ที่ logged-in (Accessibility Service)
3. Web dashboard: label accounts, ตั้ง rotation schedule (เช่น 8hr/account)

Runtime:
1. Scheduler ถึงเวลา → companion app
2. Accessibility: tap TikTok profile → switch account → confirm
3. Start live ด้วย video + banner ที่ assigned ให้ account นั้น
4. รัน 8hr → schedule trigger swap → ทำซ้ำ
```

### Success Criteria
- 80+ paying customers
- ARR 6.3M
- Median TikTok accounts per device ≥ 2.5
- Per-account ban rate ↓ 30%+ vs V1 baseline
- Customer NPS > 40

---

## 🤖 V3 — Pro Features + AI Assist (Q2 2027)

**Theme:** "Make it smart — AI-augmented operations"

### Features
| Feature | Confidence |
|---|---|
| **Hybrid Live** — creator joins live in real-time, takes over from broadcast | ❓ Exploratory — technical feasibility of mid-stream control handoff unclear |
| **Fast snapshot-based account swap** (sub-10s) — alternative to V2 native switcher; `/data/data/<tiktok>` backup/restore | ❓ Exploratory — requires root/custom ROM; only worth it if V2 native switcher proves too slow at scale |
| **AI Comment Reply** with operator approval | 🔄 Likely (LLM API call) — depends on comments access (see V2 ❓) |
| **AI Insights** — "Best performing video" / "When to switch product" | 🔄 Likely if analytics data available (see V2 ❓ GMV) |
| **Smart Scheduling** — algorithm picks optimal live times | 🔄 Likely if past performance data exists |
| **Auto Banner** — AI generates banner copy from video content | 🔄 Likely (LLM + video analysis) |
| **Fraud Detection** — multi-account abuse, bot patterns | 🔄 Likely (internal data analysis) |
| **Advanced Pin** — multi-product rotation, auto-pin by video timestamp | ✅ Confirmed |

### Success Criteria
- 300+ paying customers
- AI-features adoption > 50% of Pro tier
- ARR 15M+

---

## 🎯 What This Roadmap Says About the Business

**V1-V1.5:** Build → reliable → 30 customers
**V2:** Multi-profile rotation → reduce ban impact → 80 customers
**V3:** AI moat → premium pricing → 300 customers

Each phase grows TAM 2-3x while raising willingness-to-pay through advanced features.

---

## 📋 Cross-version Open Questions

| # | Question | Decision needed by |
|---|---|---|
| 1 | Mobile path — Smart Overlay OR patched APK OR both? | V1 launch |
| 2 | TikTok Shop API access for GMV/analytics — available to third-party? | V2 design |
| 3 | Comments access — TikTok API, scraping, or skip feature? | V2 design |
| 4 | AI cost model — pass-through OR absorbed in tier price? | V3 design |
| 5 | V3 snapshot fast-swap — needed if V2 Accessibility-based switch is too slow at scale? | V3 R&D |

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
