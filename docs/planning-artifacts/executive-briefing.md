# TiktokRerun — Executive Briefing

**สำหรับ:** Executive Sponsor & Decision Committee
**วันที่:** 2026-05-24
**Owner:** Pond
**ความยาวคาดหวัง:** 10-15 นาทีอ่าน + 30-45 นาที walkthrough

---

## ⚡ One-paragraph pitch

**TiktokRerun** คือ web-based control plane สำหรับ **solo TikTok Shop seller** ที่ run หลายบัญชี — คุม Android phones (เริ่ม 3-10, scale ได้ถึง 100+) จากเว็บเดียว, broadcast pre-recorded วิดีโอเป็น live, ปักตะกร้าสินค้า, และเปลี่ยน promo banner real-time POC validated (4 วัน vs คาด 2-3 wk = AI-leveraged velocity 5x), market gap ชัดเจน (ไม่มีคู่แข่ง direct), pricing 3,990 บาท/10 devices ทำ gross margin > 90% **Ask:** approve MVP build (**~200K × 8 สัปดาห์, Pond solo full-stack + Claude**, infra Tier A scrappy, legal deferred to V1)

---

## 1. The Opportunity — ทำไมเรื่องนี้สำคัญ

### Market signal
- TikTok Shop Thailand เข้าสู่ scale phase, seller SME ย้ายจาก Shopee/Lazada
- Live commerce conversion สูงกว่า static feed หลายเท่า → "live 24/7" คือ competitive necessity
- TikTok algorithm prioritize creator ที่ live บ่อย → ไม่ live = ไม่มี distribution

### Pain ที่ตลาดมีจริง
ตลาดมี solution แต่ทุกตัวขาด:
| Solution | Capability | Gap |
|---|---|---|
| 3-phone Wi-Fi ADB tool (TH local) | Broadcast loop video | ❌ no mid-live control, no banner, max 3 phones, no web |
| TikMatrix ($29-149/mo) | Engagement farm 100+ phones | ❌ no broadcast, no commerce, desktop only |
| OBS + LIVE Studio | Pro broadcaster setup | ❌ 1 device/PC, ต้อง stream key 1K followers, ไม่ scale |
| MOD APK tools | Unlock features | ❌ illegal, malware risk, ban risk สูง |
| Manual: จ้างคนคุม | Full ops | ❌ salary 15-20K บาท/คน, ไม่ scale |

**ช่องว่างที่ไม่มีใครเล่น:** Live Commerce Ops Platform — broadcast + commerce control + multi-device + web

---

## 2. The Solution — เราทำอะไร

### 5 core capabilities
1. **Web control plane** — operator คนเดียวคุม 100+ phones จากเว็บ anywhere (vs commodity tools ที่ "set-and-forget")
2. **Smart Overlay broadcast** — Companion App วาด video เป็น overlay บน TikTok screen-share → no flicker, no UI exposure
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

- **POC validated** — Smart Overlay broadcast + pin product + audio routing ทำงานครบ
- **Technical moat:** WebSocket-always-connected = fundamental capability ที่ commodity tools ไม่มี
- **Architectural moat:** Smart Overlay + Banner composition = ไม่มีคู่แข่งทำได้
- **First-mover** ในตลาด Live Commerce Ops Platform (อยู่ใน category ที่ยังว่าง)
- **Timing:** ก่อน TikTok ออก stricter policy หรือ partner program ที่ปิดประตู
- **Founder-market fit:** ทีมเข้าใจ TikTok ecosystem ในไทยลึก

---

## 4. Business Model

### Pricing (4-tier subscription)
| Tier | Devices | บาท/เดือน | บาท/device |
|---|---|---|---|
| Starter | up to 10 | **3,990** | 399 |
| Growth | up to 30 | 8,990 | 300 |
| Pro | up to 100 | 28,990 | 199 |
| Enterprise | 100+ | quote | 120-140 |

- ไม่มี free trial — pay upfront
- 20% annual discount

### Unit economics
- Server cost per device: **5-15 บาท** (Tier A: Hetzner + Cloudflare R2 = $0 egress)
- Server cost ratio: **3-7% revenue** (gross margin > 90%)
- Customer acquisition: bottom-up via design partners → community/referral

### Revenue projection
| Stage | Users | Avg devices | MRR (บาท) | ARR (บาท) |
|---|---|---|---|---|
| V1 6-mo | 30 | 20 | 192K | 2.3M |
| V1 12-mo | 80 | 25 | **524K** | **6.3M** |
| Year 2 | 300 | 30 | 2.28M | **27.3M** |

---

## 5. Investment & Resource Ask

### MVP phase (8 สัปดาห์, Q3 2026) — AI-leveraged solo execution
| Resource | Quantity | Cost |
|---|---|---|
| Pond (founder + full-stack) | 1 FTE × 50K salary | 100K |
| Claude (AI coding assistant) | 6.5K/mo × 2 | 13K |
| Infrastructure (Tier A: Hetzner + Cloudflare R2) | | 4K |
| Tools (Linear, GitHub, Sentry) | 10K/mo × 2 | 20K |
| Misc + contingency (specialist contractor if needed) | | 30-50K |
| **MVP total** | | **~170-190K** |

> **Legal review deferred to V1 phase** (before paid GA) — MVP runs with design partners under informal agreement; formal ToS + PDPA review (~50-100K) added when self-serve paid signup opens

### Why 1-person + AI = realistic
- **POC velocity proof:** ทำเสร็จใน 3-4 วัน vs คาด 2-3 wk = **5x ของ industry baseline**
- Claude + Cursor + agent tools = 1 senior full-stack มี productivity เทียบเท่าทีม 3-4 คนยุคก่อน
- Scope MVP = ~8-10x ของ POC → maintain velocity = 6-8 wk MVP
- Pond มี domain expertise + ownership = ไม่ต้อง onboard ทีม

### Payback projection
- Investment: **~200K** (MVP) + ~80K legal (V1 phase) = ~280K total to revenue stage
- Year 1 ARR target: 6.3M
- **Payback: ~1 เดือน หลัง paid GA**
- **ROI Year 1: 1,500-2,000%+**
- ไม่ต้องระดมทุน, self-funded scale ได้

---

## 6. Roadmap

```
2026 Q2 │ POC                                 ✅ Complete (validated)
2026 Q3 │ MVP build (3 months)                ◀── REQUESTING APPROVAL
        │  • Smart Overlay + Banner Tier 1+2  │
        │  • Multi-tenant web + Companion App │
        │  • Pin product, video switching     │
        │  • 2-3 design partners onboarded    │
        │
2026 Q4 │ V1 GA — Paid launch                 │
        │  • Self-serve signup + billing      │
        │  • Scheduling, comment monitoring   │
        │  • Scheduling                       │
        │
2027 Q1 │ Scale + Analytics                   │
2027 Q2 │ Hybrid Live + AI assist             │
2027 Q3 │ SEA expansion (VN, ID, PH)          │
```

---

## 7. Top Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| TikTok updates break Accessibility selectors | High | Selector versioning + 24-48hr patch SLA |
| Mass account ban กระทบ customer retention | High | Customer ToS shifts liability; best-practice docs; aggregate telemetry |
| Smart Overlay verification gates fail | Medium | POC extension before MVP build; fallback to plain screen-share |
| Competitor copies Banner feature | Low-Med | First-mover + UX execution + faster iteration |
| Legal challenge from TikTok | Medium | A1 positioning, no public ToS bypass claim, legal review pre-GA |

---

## 8. Decisions Asked from Executive

| # | Decision | Recommendation | Why this matters |
|---|---|---|---|
| 1 | **Approve MVP phase** | ✅ Approve | Unlock 8-week build, **~200K บาท** |
| 2 | **Team structure** | Pond solo full-stack + Claude (50K/mo salary) | AI-leveraged, no additional hire for MVP |
| 3 | **Infrastructure budget** | 5-10K/mo MVP cap | Tier A scrappy stack |
| 4 | **Public positioning** | A1 — Live Commerce Ops Platform | Avoid MOD APK / fraud association |
| 5 | **Design partners selection** | 2-3 friendly agencies | Validation + early revenue + case study |
| 6 | **Legal review (deferred to V1)** | TikTok ToS + Thai PDPA + Computer Crime Act + customer ToS | Before paid GA, ~50-100K |
| 7 | **Hosting region** | Bangkok-first (Tier A: Hetzner Singapore for now) | Latency + data residency |

---

## 9. Open Items (defer to MVP build phase, not blockers)

จะ resolve ระหว่าง MVP build:
- Trial duration variants (7 vs 14 vs 30 วัน)
- Play Store distribution path (sideload + possible Play Store attempt)
- OOS auto-handling default behavior
- Customer ToS draft owner (legal collaboration)
- TikTok app version compatibility strategy
- Multi-account login per Device design
- Backup plan ถ้า TikTok ปิด screen-share API

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

> **Approve ~200K บาท × 8 weeks ให้ Pond (solo + Claude) สร้าง MVP TiktokRerun — POC พิสูจน์ velocity 5x แล้ว (4 วัน vs 2-3 wk), gross margin > 90%, payback 1 เดือน หลัง GA, ROI Year 1 = 1,500-2,000%+**
