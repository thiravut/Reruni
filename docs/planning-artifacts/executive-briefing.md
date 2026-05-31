# TiktokRerun — Executive Briefing

**สำหรับ:** Executive Sponsor & Decision Committee
**วันที่:** 2026-05-24
**Owner:** Pond
**ความยาวคาดหวัง:** 10-15 นาทีอ่าน + 30-45 นาที walkthrough

---

## ⚡ One-paragraph pitch

**TiktokRerun** คือ web-based control plane สำหรับ **solo TikTok Shop seller** ที่ run หลายบัญชี — คุม Android phones (เริ่ม 3-10, scale ได้ถึง 100+) จากเว็บเดียว, broadcast pre-recorded วิดีโอเป็น live, ปักตะกร้าสินค้า, และเปลี่ยน promo banner real-time MVP **~90% shipped ใน 2 สัปดาห์** (vs plan 8 wk = velocity 8-10x), market gap ชัดเจน (closest competitor **SamuraiLive** เป็น app-only ราคาเท่ากัน 299/device — เราขาย web control + multi-device fleet ที่เขาไม่มี), **pricing flat 299 บาท/device/month** (match SamuraiLive 1:1) ทำ gross margin > 99% at scale **Ask:** approve remaining V1 GA polish + design-partner cycle (**~25-32K cash-out × ~4 สัปดาห์ end-to-end, Pond solo + Claude — founder time self-funded as equity**, infra Google Cloud + Cloudflare R2, legal deferred to V1)

---

## 1. The Opportunity — ทำไมเรื่องนี้สำคัญ

### Market signal
- TikTok Shop Thailand เข้าสู่ scale phase, seller SME ย้ายจาก Shopee/Lazada
- Live commerce conversion สูงกว่า static feed หลายเท่า → "live 24/7" คือ competitive necessity
- TikTok algorithm prioritize creator ที่ live บ่อย → ไม่ live = ไม่มี distribution

### Pain ที่ตลาดมีจริง
ตลาดมี solution แต่ทุกตัวขาด:
| Solution | ราคา | Capability | Gap |
|---|---|---|---|
| **SamuraiLive (TH direct competitor)** | **299 บาท/device/month** | Broadcast + VCam + pin product (Magisk+LSPosed) | ❌ **app-only, ไม่มี web control, 1 phone 1 user, ต้อง root** |
| 3-phone Wi-Fi ADB tool (TH local) | ~299 บาท/device/month | Broadcast loop video | ❌ no mid-live control, no banner, max 3 phones, PC tethered |
| TikMatrix ($29-149/mo) | 1,650-4,200 บาท/month | Engagement farm 100+ phones | ❌ no broadcast, no commerce, desktop only |
| OBS + LIVE Studio | Free | Pro broadcaster setup | ❌ 1 device/PC, ต้อง stream key 1K followers, ไม่ scale |
| MOD APK tools | Various | Unlock features | ❌ illegal, malware risk, ban risk สูง |
| Manual: จ้างคนคุม | 15-20K บาท/month | Full ops | ❌ salary expensive, ไม่ scale |

**ช่องว่างที่ Reruni เล่นเฉพาะ (vs SamuraiLive — direct comparable):**
- **Web control plane** — operator คุม 10-100 phones จาก laptop ตัวเดียว (SamuraiLive = 1 phone 1 user, app-only)
- **Multi-device fleet management** + multi-tenant SaaS
- **Dynamic banner overlay** (countdown / price / promo composite real-time)
- **No root required** (LSPatch) vs SamuraiLive (Magisk+LSPosed = ต้อง root)
- **Same price (299/device)** — แข่งบน capability, ไม่แข่งบน price

---

## 2. The Solution — เราทำอะไร

### 5 core capabilities
1. **Web control plane** — operator คนเดียวคุม 100+ phones จากเว็บ anywhere (vs commodity tools ที่ "set-and-forget")
2. **VCam Camera2 hijack** — own-built LSPosed module + LSPatch shim ฉีดวิดีโอเข้า TikTok's Camera2 preview directly → no root required สำหรับ R3 Lite (BYOD), no overlay, no screen-share permission
3. **Mid-live commerce control** — pin/unpin/switch product จาก web real-time (POC validated)
4. **Dynamic Banner composition** — banner, countdown, price tag ทับ video real-time ⭐ killer differentiator
5. **Persistent WebSocket** — fleet always reachable (vs Wi-Fi ADB tools ที่ขาดเมื่อ phone ไป 5G)

### Customer workflow
```
1. ลูกค้าสมัครผ่านเว็บ → ยอมรับ ToS → เลือก subscription tier → ใส่ payment (Stripe) → เริ่มใช้งาน
2. ติดตั้ง APK บน Android phones (1 phone = 1 TikTok account)
3. Scan QR pair → device online ใน dashboard
4. Upload วิดีโอ + ตั้ง Live Title / Caption / Hashtag / Pin Product
5. กด Start Live → phone broadcast อัตโนมัติ
6. ระหว่าง live: switch video, pin product, update banner — จาก web
```

---

## 3. Why Us, Why Now

- **POC + MVP validated** — VCam Camera2 hijack + pin product + audio routing + onboarding wizard ทำงานครบ; **~90% MVP shipped ใน 2 wk จริง**
- **Technical moat:** WebSocket-always-connected = fundamental capability ที่ commodity tools ไม่มี
- **Architectural moat:** Own-built VCam LSPosed module + Banner composition + no-root path = ไม่มีคู่แข่งทำได้ครบ
- **First-mover** ในตลาด Live Commerce Ops Platform (อยู่ใน category ที่ยังว่าง)
- **Timing:** ก่อน TikTok ออก stricter policy หรือ partner program ที่ปิดประตู
- **Founder-market fit:** ทีมเข้าใจ TikTok ecosystem ในไทยลึก

---

## 4. Business Model

### Pricing — Flat 299 บาท/device/month

| Devices | บาท/เดือน | บาท/ปี (20% off) |
|---|---|---|
| 1 device | 299 | 2,870 |
| 5 devices | 1,495 | 14,352 |
| 10 devices | 2,990 | 28,704 |
| 30 devices | 8,970 | 86,112 |
| 100 devices | 29,900 | 287,040 |

- **Flat 299 บาท/device/month** — ไม่มี tier, ไม่มีส่วนลด volume
- **Match SamuraiLive 1:1** — direct competitor ในไทยราคา 299/device → ลูกค้าตัดสินใจ on capability, ไม่ใช่ on price
- ไม่มี free trial — pay upfront
- 20% annual discount

### Unit economics
- Server cost per device: **1.78 บาท** at 2,000-user scale (Hybrid: GCP + Cloudflare R2 = $0 egress)
- Server cost ratio: **~0.6% revenue** (gross margin > 99%)
- Customer acquisition: bottom-up via design partners → community/referral

### Revenue projection (flat 299 × avg 7.5 devices/customer)
| Stage | Users | Avg devices | MRR (บาท) | ARR (บาท) |
|---|---|---|---|---|
| V1 6-mo | 30 | 7.5 | 67K | 807K |
| V1 12-mo | 80 | 7.5 | **179K** | **2.15M** |
| Year 2 | 300 | 7.5 | 673K | **8.07M** |
| At scale (2K users) | 2,000 | 7.5 | **4.49M** | **53.8M** |

**Breakeven:** ~31 customers (234 devices) ที่ V1 fixed cost ~70K/month — 3-5 เดือนหลัง launch ที่ acquisition 7-10 customers/month

---

## 5. Investment & Resource Ask

### MVP build (revised — cash-out only, founder time self-funded)

**Plan was** 8 weeks × ~190K **Reality:** ~2 weeks elapsed, ~90% scope shipped; **~2 weeks remaining** for V1 GA polish + design-partner cycle
**Founder time:** Pond self-funds as equity contribution — not a cash-out item

| Resource | Quantity (4 wk total) | Cost |
|---|---|---|
| Claude (AI coding assistant) | 6.5K/mo × 2 | **~13K** |
| Infrastructure | Google Cloud (Cloud Run + Cloud SQL + Memorystore) + Cloudflare R2 storage + Cloudflare SSL (free) + domain reruni.com × 2 mo | **~12-18K** |
| GitHub | Code hosting + CI/CD (Free tier; optional Pro) | **~0-1K** |
| **MVP total (cash-out)** | | **~25-32K** |

> **Legal review deferred to V1 phase** (before paid GA) — MVP runs with design partners under informal agreement; formal ToS + PDPA review (~50-100K) added when self-serve paid signup opens

### Why 1-person + AI = proven, not estimated
- **Actual velocity proof:** POC 4 วัน + ~90% MVP ใน 2 wk vs traditional 8-week POC + 6-12 wk MVP = **8-10x industry baseline**
- Claude + Cursor + agent tools = 1 senior full-stack มี productivity เทียบเท่าทีม 3-4 คนยุคก่อน
- Shipped already: backend (Go), Portal SPA + Backoffice SPA (React), Mobile companion + own-built VCam LSPosed module, deployment infra (Contabo + Caddy + Postgres), landing page, billing + onboarding wizard
- Remaining: design-partner cycle, Stripe live mode, APK production upload, email Resend domain verify, bug fix from real-world usage (Banner Tier 2 moved to V1.5)
- Pond มี domain expertise + ownership = ไม่ต้อง onboard ทีม

### Payback projection (cash-out only — founder time self-funded)
- Cash-out investment: **~30K** (MVP build) + ~80K legal (V1 phase) = **~110K total** to revenue stage
- Year 1 ARR target: 2.15M (80 users × 7.5 devices × 299 × 12)
- **Breakeven: ~31 customers** (~3-5 เดือนหลัง launch ที่ acquisition 7-10/mo)
- **ROI Year 1 (cash-out basis): ~1,950%** (2.15M ARR / 110K cash investment)
- ไม่ต้องระดมทุน, self-funded scale ได้

---

## 6. Roadmap

```
2026 Q2 │ POC                                 ✅ Complete (validated)
2026 May│ MVP build (actual: ~2 wk so far)    🟢 ~90% shipped
        │  • Backend (Go) + DB + auth ✅     │
        │  • Portal SPA + Backoffice SPA ✅  │
        │  • Mobile companion + VCam ✅      │
        │  • Pin product, video switching ✅ │
        │  • Onboarding wizard + billing ✅  │
        │  • Banner Tier 1 (static) ✅      │
        │  • Playlist (ffmpeg-concat) ✅    │
        │  • 2-3 design partners (next 1 wk) │
        │
Q2 end │ V1 GA — Paid launch                  │
        │  • Self-serve signup + billing      │
        │
2026 Q4 │ V1.5 — Stability + CAPTCHA + Banner Tier 2 │
2027 Q1 │ V2 — Scheduling + Playlist auto-switch + Stability │
2027 Q2 │ V3 — Compliance + Live AI moderation (POC-gated) │
2027 Q3 │ V4 — AI Content Creation (Phase 4)  │
```

---

## 7. Top Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| TikTok updates break Accessibility selectors | High | Selector versioning + 24-48hr patch SLA |
| Mass account ban กระทบ customer retention | High | Customer ToS shifts liability; best-practice docs; aggregate telemetry |
| VCam module breaks on TikTok app update | Medium | Camera2 hook + LSPatch shim modular; module versioning + 24-48hr rebuild SLA |
| APK distribution / patching pipeline fragile | Low-Med | Token-gated download already wired; CI patch+sign flow proven (3 phases shipped) |
| Competitor copies Banner feature | Low-Med | First-mover + UX execution + faster iteration |
| Legal challenge from TikTok | Medium | A1 positioning, no public ToS bypass claim, legal review pre-GA |

---

## 8. Decisions Asked from Executive

| # | Decision | Recommendation | Why this matters |
|---|---|---|---|
| 1 | **Approve V1 GA cycle** | ✅ Approve | Ship remaining ~10% + design partner validation, **~25-32K cash-out** (founder time self-funded as equity) |
| 2 | **Team structure** | Pond solo full-stack + Claude (founder equity, no salary) | AI-leveraged, no additional hire for MVP |
| 3 | **Infrastructure budget** | 5-10K/mo MVP cap | Hybrid GCP + Cloudflare R2 |
| 4 | **Public positioning** | A1 — Live Commerce Ops Platform | Avoid MOD APK / fraud association |
| 5 | **Design partners selection** | 2-3 friendly agencies | Validation + early revenue + case study |
| 6 | **Legal review (deferred to V1)** | TikTok ToS + Thai PDPA + Computer Crime Act + customer ToS | Before paid GA, ~50-100K |
| 7 | **Hosting region** | GCP asia-southeast1 (Singapore) + Cloudflare global edge | Latency + data residency |

---

## 9. Open Items (defer to MVP build phase, not blockers)

จะ resolve ระหว่าง MVP build:
- Trial duration variants (7 vs 14 vs 30 วัน)
- Play Store distribution path (sideload + possible Play Store attempt)
- OOS auto-handling default behavior
- Customer ToS draft owner (legal collaboration)
- TikTok app version compatibility strategy
- Backup plan ถ้า TikTok ปิด Camera2 path / detect VCam module

---

## 10. References

- **PRD (detail):** `docs/planning-artifacts/prds/prd-TiktokRerun-2026-05-24/prd.md` (52K, 26 FRs)
- **System Architecture:** `docs/planning-artifacts/system-overview.md`
- **Tech Detail:** `docs/planning-artifacts/technical-architecture-draft.md`
- **Cost Analysis:** `docs/planning-artifacts/cost-analysis-gcp.md`
- **Market Research:** `docs/planning-artifacts/market-research-tiktok-live-rerun.md`
- **Decision Log:** `docs/planning-artifacts/prds/prd-TiktokRerun-2026-05-24/.decision-log.md`

---

## TL;DR สำหรับ exec ที่อ่านแค่บรรทัดเดียว

> **Approve ~25-32K บาท cash-out × ~2 weeks remaining ให้ Pond (solo + Claude — founder time self-funded as equity) finish V1 TiktokRerun — ~90% MVP shipped ใน 2 wk จริง (velocity 8-10x industry), pricing flat 299/device match SamuraiLive, gross margin > 99% at scale, breakeven ~31 customers (3-5 เดือนหลัง GA), ROI Year 1 ~1,950% on cash-out basis**
