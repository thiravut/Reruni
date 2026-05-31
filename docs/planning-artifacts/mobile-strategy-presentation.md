# Mobile Strategy — TiktokRerun

**Executive Presentation — Mobile App Development Plan**
**Date:** 2026-05-31 (Updated — major breakthrough)
**Owner:** Pond
**Target audience:** Executive Sponsor & Decision Committee
**ความยาวคาดหวัง:** 10 นาที walkthrough

> ⚠️ **Architecture refined since this doc:** initial breakthrough was patched-APK-with-VCAM-embedded approach.
> **Current production path (2026-05-31 evening):** own-built VCam LSPosed module + LSPatch shim — same no-root benefit, cleaner separation (TikTok app เปิดผ่าน LSPatch + VCam module load ผ่าน LSPosed). All other strategic conclusions (no root, BYOD, 2-3 wk timeline, re-patch SLA) still hold.
>
> See [system-overview.md](system-overview.md) §9 + [v1-launch-presentation.md](v1-launch-presentation.md) Slide 9 for current architecture.

> **Use:** ไฟล์นี้สำหรับ NotebookLM → แปลงเป็นสไลด์อีกที แต่ละ section คือ 1-2 slides

---

## 1. Headline — Major Breakthrough

> **Patched TikTok APK + VCAM ทำงานได้แล้ว — ไม่ต้อง root เครื่อง**

ผลที่ตามมา:
- ✅ ลูกค้าใช้ phone ตัวเอง (BYOD จริงๆ)
- ✅ ไม่ต้อง flash/root → ไม่มี brick risk
- ✅ Customer setup ใช้เวลา < 10 นาที
- ✅ TAM กว้างขึ้นมาก (ใครก็ใช้ได้)
- ✅ Investment ลดลง 60-70%

**Game changer — เปลี่ยนทั้ง business model**

---

## 2. ทำงานยังไง (สั้นๆ)

```
[ลูกค้า]                          [เรา]
1. โหลด TiktokRerun app ของเรา ────┐
2. โหลด patched TikTok APK         │  เราเตรียมให้
   (TikTok APK + VCAM module       │  + re-patch
    integrated by us)              │  ทุก TikTok release
3. Install ทั้ง 2 → Login TikTok ───┘
4. ใช้งานตามปกติ
```

**ลูกค้า:** ไม่ต้อง root, ไม่ต้อง flash, ไม่ต้อง shop
**เรา:** maintain patched APK + update cycle

---

## 3. What This Changes vs Previous Plan

**ยกเลิก:**
- ❌ Mail-in flash service
- ❌ Partner shop network (สำหรับ flash)
- ❌ Pixel + root path (เหลือเป็น R&D backup)
- ❌ Custom ROM as primary (เก็บเป็น hedge)
- ❌ Inventory model

**ยังต้องการ:**
- ✅ Maintain re-patch cycle (TikTok updates ทุก 2-4 wk)
- ✅ Monitor detection / ban rate
- ✅ Distribution channel (เว็บ download + companion app)
- ✅ APK signing + integrity protection

---

## 4. New Simplified Strategy — Single Tier

| Item | Detail |
|---|---|
| Path | **Single path:** patched APK + VCAM (no root) |
| Customer profile | All TikTok Shop sellers (mass market) |
| Device requirements | Any Android phone (specific versions tested) |
| Setup time | 5-10 นาที |
| Maintenance | Continuous re-patch per TikTok release |

**Tier differentiation มาจาก device count (subscription tier เดิม):**
- Starter 10 / Growth 30 / Pro 100 — **ไม่ต้องแยก device tier**

---

## 5. Critical Risks Remaining

แม้ breakthrough — ยังมีความเสี่ยง:

| Risk | Severity | Mitigation |
|---|---|---|
| **TikTok signature/integrity check** | High | Re-patch + signing strategy |
| **Ban rate ยังไม่รู้** | High | 1-2 wk monitoring with 5-10 test accounts |
| **TikTok update breaks patch** | High | 24-48hr patch SLA + monitoring |
| **LSPatch / patcher detected by TikTok** | Medium | Multi-method approach, rotate techniques |
| **Play Integrity rejection** | Medium | Some accounts may be flagged immediately |
| **Legal / TikTok ToS** | Medium | Customer ToS shifts liability (existing) |

> ⚠️ "ทำได้แล้ว" ≠ "ban rate ต่ำ" — ต้อง validate ใน 2-3 สัปดาห์ก่อน scale

---

## 6. What We Need to Validate (Next 2-3 Weeks)

| Test | Goal | Duration |
|---|---|---|
| **Ban rate baseline** | 5-10 test accounts × 7 วัน broadcast | 1 wk |
| **TikTok update resilience** | รอ TikTok next update → re-patch speed | 2-4 wk (depends on TikTok) |
| **Multi-device testing** | 10-50 phones concurrent | 1 wk |
| **Edge cases** | Android versions 10/11/12/13/14, different OEMs | 1 wk |
| **Distribution UX** | Customer onboarding flow time-to-first-live | 1 wk |
| **Customer can re-install** | Update flow when we push new patch | Continuous |

---

## 7. New Investment Plan

| Item | Cost | Note |
|---|---|---|
| Patch automation tooling | ~30K | One-time — script TikTok APK → patched APK pipeline |
| Ban rate testing | ~20K | Test accounts + monitoring infrastructure |
| Distribution setup (web download + companion update) | ~30K | Already partially built in portal |
| Documentation + customer onboarding | ~20K | Guide + video tutorial |
| Custom ROM hedge (defer to Phase 2) | 0 (now) | Activate if patch path breaks |
| **NEW TOTAL** | **~100K** | (was 370K — saved 270K) |

**Timeline:** 2-3 weeks to production-ready (was 8 weeks)

---

## 8. ROI Recalculated

**ก่อนหน้า:** Two-tier (BYOD + Pro Custom ROM) → +3-5M ARR
**ตอนนี้:** Single tier ใช้ patched APK ได้ทุกคน → **bigger TAM**

| Metric | Before | After |
|---|---|---|
| Addressable customers | 30% Pro-willing | **80-90% any seller** |
| Setup friction | สูง (root/flash) | **ต่ำ (APK install)** |
| Investment | 370K | **100K** |
| Time to launch | 8 wk | **2-3 wk** |
| Year 1 ARR uplift | +3-5M (Pro premium) | **+8-12M** (broader adoption) |

> Larger TAM × Lower friction = Significantly bigger market opportunity

---

## 9. Decisions Asked from Executive

| # | Decision | Recommendation |
|---|---|---|
| 1 | **Approve simplified mobile track ~100K** | ✅ Approve (60-70% less than original plan) |
| 2 | **Single path: patched APK (no root)** | ✅ Approve |
| 3 | **Distribution: web download + companion update** | ✅ Approve |
| 4 | **Ban rate validation 1-2 wk before scale** | ✅ Mandatory gate |
| 5 | **Custom ROM as Phase 2 hedge** | Keep on roadmap but defer |
| 6 | **Re-patch SLA: 24-48hr per TikTok release** | ✅ Commit operationally |
| 7 | ~~Smart Overlay (POC original) — deprecate?~~ | ✅ **DECIDED — Deprecated** (own-built VCam LSPosed module replaces it) |

---

## 10. New Timeline — 2-3 Week Plan

```
Week 1   | Setup patch automation pipeline
         | 5-10 test accounts → run broadcast 7 days
         | Monitor ban rate baseline
         | Customer onboarding doc + video

Week 2   | Validate ban rate < 20%/week (acceptable)
         | Multi-device test (10-20 phones)
         | TikTok APK version compatibility matrix
         | Distribution UX (web download flow)

Week 3   | Beta test with 2-3 design partners
         | Iterate on customer feedback
         | Launch-ready: documented re-patch process
```

---

## 11. Updated Success Metrics

| Metric | Target Q3 2026 |
|---|---|
| Patched APK install success rate | > 95% (customer-reported) |
| Time-to-first-live (signup → broadcast) | < 30 min |
| Ban rate per account per week | < 20% (acceptable starting point) |
| Re-patch turnaround per TikTok release | < 48 hours |
| Customer retention 3-month | > 70% |
| Customer NPS | > 30 in beta, > 50 in V1 |

---

## 12. Why This Matters Strategically

**ก่อน breakthrough:** เรา = niche product สำหรับ technical users
**หลัง breakthrough:** เรา = mass market SaaS สำหรับ TikTok Shop sellers ใดๆ

**Competitive moat ใหม่:**
- Patch automation + re-patch SLA = **operational moat**
- คู่แข่งจะลอกได้แต่ต้องมี continuous patching team
- เรา head-start 2-3 เดือนก่อนคู่แข่ง catch up

**Business model simplification:**
- No device sales / inventory / shipping logistics
- Pure SaaS + APK distribution
- Margin > 95% (no hardware cost)

---

## 13. Open Questions Still to Resolve

1. **Re-patch cadence** — automate full pipeline or semi-manual?
2. **TikTok signature** — เราใช้ signature ของเรา (ลูกค้าต้อง enable "install from unknown") หรือ?
3. **Update mechanism** — companion app push update โดย auto?
4. **Patched APK hosting** — ของเรา (legal risk) หรือ require customer self-source?
5. **Fallback path** — ถ้า VCam module break ตอน TikTok update, 24-48hr rebuild SLA หรือ fallback อื่น? (Smart Overlay deprecated, ไม่ใช่ option แล้ว)

---

## 14. TL;DR — One Slide Summary

> **Mobile track breakthrough — Patched TikTok + VCAM works without root**
>
> Strategy simplified:
> - ✅ Single path (no two-tier needed)
> - ✅ BYOD friendly (any Android phone)
> - ✅ No flash/root/custom ROM required for primary
> - ✅ Investment cut from 370K → 100K
> - ✅ Timeline cut from 8 wk → 2-3 wk
>
> **Critical to validate:** ban rate (1-2 weeks test before scale)
> **Operational commitment:** 24-48hr re-patch SLA per TikTok release
>
> Year 1 ARR upside: +8-12M (vs +3-5M before) due to mass-market reachability

---

## Appendix A — Reference Docs

- PRD: `docs/planning-artifacts/prds/prd-TiktokRerun-2026-05-24/prd.md`
- Cost analysis: `docs/planning-artifacts/cost-analysis-gcp.md`
- Decision log: `docs/planning-artifacts/prds/prd-TiktokRerun-2026-05-24/.decision-log.md`
- Original Smart Overlay tech (now backup): `docs/planning-artifacts/technical-architecture-draft.md` §3.5

---

## Appendix B — What "Patched APK + VCAM" Means Technically

For exec who wants understanding:
- **Patched TikTok APK** = TikTok app modified to accept external video signal
- **VCAM module** = open-source camera replacement, embedded in patched APK
- **No root** = ลูกค้าติดตั้งเป็น regular app, ไม่ต้องดัดแปลง Android system
- **ทำงาน:** ลูกค้าเปิด patched TikTok → start live → video ที่เราใส่ → TikTok เห็นเป็น camera output → broadcast ออก

**Maintenance cycle:**
- TikTok ออก update → APK เปลี่ยน signature/structure
- เรา re-patch ภายใน 24-48hr
- ลูกค้า update ผ่าน companion app
