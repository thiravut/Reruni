# Market Research: TikTok Live Rerun

**วันที่:** 2026-05-23
**สถานะ:** Initial research — ใช้เป็น input สำหรับ Product Brief / PRD
**ที่มา:** Web research (TikTok newsroom, TikTok Support, Thai TikTok creator blogs, BlackHatWorld threads, third-party tool sites)

---

## 1. นิยาม "Live Rerun" ใน context TikTok

ตลาดใช้คำว่า "rerun / รีรัน" กับ 2 พฤติกรรมที่ต่างกันมาก ต้องแยกให้ชัดก่อนออกแบบ product:

### A. Live Replay (official, on-platform)
- ฟีเจอร์จาก TikTok เองที่บันทึกไลฟ์ที่จบแล้วเป็น VOD
- ผู้ชมที่พลาดสามารถดูย้อนหลังผ่าน profile / LIVE Center ได้
- **อายุสั้น: เก็บแค่ 30 วัน** หลังจากนั้นหาย
- Creator จัดการ (download / clip / delete) ได้จาก LIVE Center
- เอกสาร: https://support.tiktok.com/th/live-gifts-wallet/tiktok-live/tiktok-live-replay

### B. Pre-recorded "Loop Live" (off-policy)
- ใช้ OBS / Streamlabs / OneStream + วิดีโออัดไว้ → broadcast เป็น live สด 24/7
- เป้าหมาย: รักษา presence ใน TikTok Shop, algorithm boost, music station, gameplay loop
- **TikTok 2026 Community Guidelines ระบุชัดว่าผิดกฎ** — ห้าม "low-quality content" รวม pre-recorded loops
- รายงานจาก BlackHatWorld: OBS virtual camera มักโดน violation ใน ~20 นาที
- Algorithm 2026 ตรวจจับ loop ได้แม่นยำขึ้นเรื่อยๆ

> **Implication:** ถ้า product เราเป็น "loop live tool" ต้อง budget สำหรับ cat-and-mouse กับ TikTok detection หรือเปลี่ยน positioning

---

## 2. แรงขับเคลื่อนของกระแส

| ปัจจัย | รายละเอียด |
|---|---|
| TikTok Shop boom | Creator ต้องการ shopping presence 24/7 แต่ไลฟ์เองไม่ไหว |
| Algorithm preference | go LIVE = สัญญาณ active creator → distribution boost |
| Replay 30-day limit | Creator ต้องการ tool save / repurpose ก่อนหาย |
| Cross-platform restream | กระจายไลฟ์เดียวไป YouTube / Facebook / Twitch |
| Archive / monitoring need | Fan / นักวิเคราะห์ / คู่แข่ง ต้องการ archive ของ creator |
| ไทย: Live Fest 2026 | TikTok Live ecosystem ในไทยโตต่อเนื่อง (Kuian, Dunknatachai 3.5M followers) |

---

## 3. คู่แข่ง / เครื่องมือในตลาด

### กลุ่ม "TikTok Live commerce ops tool" (TH local market — direct comparable)

**SamuraiLive** ⭐ direct competitor (confirmed 2026-05-31)
- **ราคา:** **299 บาท/device/month** (ตรงกับ pricing ของ Reruni)
- **Distribution:** LINE OA + api.depend.live
- **Architecture:** patched TikTok APK + own LSPosed VCam module + Magisk root (เรา decompile V1.0.0 confirm แล้ว — stack เดียวกับ V3 plan ของเราตอนแรก)
- **ข้อจำกัด vs Reruni:**
  - ❌ **App-only — ไม่มี web control plane**
  - ❌ **1 phone 1 user** — ไม่มี multi-device fleet management
  - ❌ **ต้อง root** (Magisk + LSPosed) — เราใช้ LSPatch ไม่ต้อง root
  - ❌ ไม่มี dynamic banner overlay (countdown/price/promo composite)
  - ❌ ไม่มี multi-tenant SaaS (admin, billing, quota built-in)
- **ความหมายต่อ positioning:** เรา = ops platform (web + fleet + SaaS); SamuraiLive = single-user mobile app เราขายสิ่งที่เขาขาดที่ราคาเดียวกัน

**3-phone PC tool (TH vendor — name unknown)**
- ราคา ~299 บาท/device/month
- PC-tethered, max 3 phones, ไม่มี mid-live control, ไม่มี web

### กลุ่ม "Broadcast pre-recorded as live" (global)
- **OneStream Live** — schedule pre-recorded, loop playlist 60 วัน, multi-platform restream
- **OBS + virtual camera** — DIY, ฟรี, เสี่ยง detect สูง

### กลุ่ม "Record creator's live"
- **StreamRecorder.io** — auto-record ตั้งแต่วินาทีแรกที่ creator go live
- **StreamArchive.io** — cloud-based, ไม่ต้องเปิดเครื่องเอง
- **GREC** — monitor profile 24/7
- **Michele0303/tiktok-live-recorder** (GitHub, Python, open-source)
- **Apify TikTok LIVE Unlimited**

### Gap ที่สังเกตได้
1. **SamuraiLive มี broadcast + commerce แต่ขาด web control + multi-device fleet** — เป็นช่องทาง direct ของ Reruni
2. **ไม่มีเครื่องมือไหนแก้ปัญหา "ทำ rerun โดยไม่โดนแบน"** ได้จริง
3. **Replay → repurpose pipeline** (auto download / clip / re-post) ยังไม่มี player ที่เด่น
4. **Search ภายใน live archive** (semantic search สิ่งที่ creator พูด/แสดง) ยังไม่มี
5. **Thai-localized tool** สำหรับ TikTok Shop seller ที่ run หลายเครื่อง = SamuraiLive (app-only) vs Reruni (web + fleet) — duopoly ในตลาด TH

---

## 4. Target Segments

| Segment | Pain | Willingness to Pay |
|---|---|---|
| TikTok Shop seller รายเล็ก-กลาง (TH) | ไลฟ์ขายไม่ไหว 24/7, ขาดทีม | สูง — มี ROI ตรง |
| Brand / agency | ต้องการ presence + multi-platform restream | สูง |
| Creator entertainment | replay หายใน 30 วัน, อยาก repurpose | กลาง |
| Fan / archivist | อยากเก็บไลฟ์ creator ที่ชอบ | ต่ำ-กลาง (B2C) |
| Researcher / analyst | ต้องการ archive + วิเคราะห์ trend | กลาง (B2B) |

---

## 5. ความเสี่ยง / Open Questions

- **Policy risk** — ทุก solution ที่ใกล้ "fake live" ขัดกฎ TikTok ปี 2026 ชัดเจน
- **ToS scraping** — record creator คนอื่นโดยไม่ขออาจขัด ToS (โดยเฉพาะถ้า monetize)
- **Copyright** — TikTok update Live Stream Copyright Rules ปี 2026; rerun เพลง/วิดีโอลิขสิทธิ์เสี่ยง claim
- **Differentiation** — ตลาด tool หนาแน่นแล้ว ต้องหา wedge เฉพาะ (เช่น Thai-first, Shop-integrated, Replay repurpose)

---

## 6. คำถามที่ต้องตอบก่อนเข้า Product Brief

1. โปรเจกต์ **TiktokRerun** จะเป็น product ฝั่งไหน? (broadcast tool / archive tool / replay-repurpose tool / cross-platform restream)
2. Target user หลัก = TH seller, global creator, หรือ archivist?
3. รับ policy risk ขนาดไหน? (จะอยู่ใน gray area หรือ play safe ใช้ official Replay API)
4. Monetization model = subscription, usage-based, free + premium tier?
5. มี TikTok Shop integration ในแผนหรือไม่?

---

---

## 7. Update: เทรนด์ "OBS บน Android" (พฤษภาคม 2026)

### ข้อเท็จจริงที่ต้องเคลียร์
OBS Studio **ไม่มีเวอร์ชัน Android อย่างเป็นทางการ** (desktop-only) เมื่อ creator พูดว่า "ลง OBS บน Android" จริงๆ หมายถึงหนึ่งใน 4 app นี้:

| App | สิ่งที่ทำได้ (OBS-like) | หมายเหตุ |
|---|---|---|
| **PRISM Live Studio** (Naver) | video playlist + RTMP + multi-stream + media widget | ใกล้ OBS มากสุดบน Android; v3.8.0 เพิ่ม RTMP overlay |
| **Streamlabs Mobile** | live + overlay + scene | official ปลอดภัย แต่ feature น้อย |
| **CameraFi Live** | screen mirror + media file | นิยมสาย gaming |
| **TikTok Live MOD APK** | unlock RTMP / pre-recorded / screen share | **เถื่อน, เสี่ยง malware + account ban** |

### 3 เทคนิคที่นิยม

1. **PRISM + Playlist RTMP** — เพิ่มวิดีโอเก่าเป็น media source, set loop, push RTMP ไป TikTok stream key
2. **Screen Mirroring trick** — เปิด TikTok Live → screen share → play video file ใน player อีกตัว → broadcast เป็นสด
3. **MOD APK** — open RTMP / pre-recorded ที่ official app ปิด (เสี่ยงสูงสุด)

### ทำไมเทรนด์นี้พุ่งบน Android ตอนนี้
- TikTok Shop seller ไทย mobile-first ไม่มี PC
- OBS desktop ต้อง stream key ที่ TikTok ออกให้ creator ระดับ ≥1,000 followers เท่านั้น
- PRISM Android v3.8.0 (2026) เพิ่ม RTMP overlay + media playlist → ทำได้บนมือถือเครื่องเดียว
- Screen mirroring บน TikTok Live ยังไม่ถูก auto-detect

### Policy enforcement (2026)
- **ห้ามชัด:** blank screen, static image, pre-recorded video looping (TikTok 2026 Community Guidelines)
- **บทลงโทษ:** 1 violation → 72hr live ban / shadow restriction / permanent suspension
- **AI moderation 2026:** audio-video analysis + cross-platform behavioral scoring
- **Pattern detection:** เนื้อหาซ้ำ, ไม่ตอบ comment, no facial movement → suspicious
- **Screen recording** ของผู้ชม TikTok ยังไม่ส่ง notification — แต่ creator ที่ broadcast loop ตรวจได้

### Implication ต่อ TiktokRerun (สำคัญ)

**Gap ที่ชัดเจนขึ้นจากเทรนด์นี้:**
1. **Mobile-first rerun tool** สำหรับ TH seller — ยังไม่มี dominant player
2. **Anti-detection stability** — ทุก tool โดน flag ใน weeks
3. **Hybrid live** — pre-recorded video + human ตอบ comment สด → หลบ pattern detection
4. **TikTok-native UX** — PRISM ออกแบบกว้าง ไม่ optimize TikTok โดยเฉพาะ

**Decision point ที่เพิ่มขึ้น:**
- **Platform target:** Android-first, iOS-also, หรือ desktop?
- **Anti-detection investment:** จะลงทุนเรื่องหลบ AI moderation แค่ไหน?
- **Hybrid mode:** มี option ให้คนสดเข้าตอบ comment แทรกได้ไหม?

---

---

## 8. Direct Competitor: TikMatrix (พฤษภาคม 2026)

### Profile
- **Product:** TikMatrix — Professional TikTok Account Management Tool
- **Platform:** Windows / Mac / Linux (desktop only, **ไม่มี web dashboard**)
- **Capacity:** "100+ phones mirroring to a computer, with synchronized operation"
- **Pricing:** Free / $29 / $59 / $99 / $149 per month (task concurrency)
- **Site:** https://tikmatrix.com/

### Feature Coverage

| Feature | TikMatrix | TiktokRerun (proposed) |
|---|---|---|
| Phone fleet control | ✅ desktop mirror | ✅ web-based |
| Batch login / account mgmt | ✅ | ⚠️ tbd |
| Auto post / engagement | ✅ | ❌ (out of scope) |
| Mass DM / scraping | ✅ | ❌ |
| **Watch/like/comment target lives** (engagement farm) | ✅ | ❌ |
| **Broadcast video to TikTok Live (rerun)** | ❌ | ✅ **gap** |
| **Product pinning automation** | ❌ | ✅ **gap** |
| **Web control plane** | ❌ | ✅ **gap** |
| Local REST API | ✅ (Pro+) | ✅ |

### Key insight: 3 ช่อง gap ที่ TikMatrix ไม่ทำ — โอกาสของเรา
1. **Broadcast (loop video → live)** — TikMatrix ทำ engagement ไม่ทำ broadcast
2. **TikTok Shop product pin automation** — ไม่มีในตลาด
3. **Web-based control** — TikMatrix desktop only, ลูกค้าต้องอยู่หน้าคอม

→ Product positioning: **"Web-based TikTok Live Commerce Ops Platform"**
→ ไม่ใช่ direct competitor กับ TikMatrix แต่ตลาดข้างเคียง / complementary

### Validation signals
- TikMatrix charge ได้ $29-$149/เดือน + มีฐานลูกค้า → ตลาด phone fleet management มีจริงและจ่ายเงิน
- 100+ device support เป็น standard baseline
- Desktop-only = legacy approach, web-based = next-gen UX

---

## 9. Confirmed Technical Approach (จาก competitor research)

### Broadcasting Method
**Screen-share + fullscreen video** (Q1 = A) — เป็น standard pattern ที่ทุก competitor ใช้
- ไม่ต้อง stream key
- ไม่ต้อง 1,000-follower requirement (เพราะใช้ TikTok app live ปกติ)
- Native flow → low detection footprint
- Quality trade-off เล็กน้อย (screen capture vs native encode)

### Product Pinning Method
**Accessibility Service บน TikTok app** (Q2 = 1) — แต่ต้อง resolve workflow conflict:
- ปัญหา: ถ้า TikTok ใน screen-share mode, การ tap pin button = ภาพ TikTok UI โผล่บน broadcast
- ทางออกที่ต้อง POC:
  - **(a)** TikTok screen-share อาจมี floating pin widget แยกจาก app — ต้อง verify
  - **(b)** Pin จาก web (TikTok LIVE Studio dashboard) แทน → ตัด Accessibility Service ออก
  - **(c)** Dual-phone setup: phone broadcaster + phone controller (cost x2)
  - **(d)** Brief overlay: ใส่ video overlay ทับ TikTok UI ขณะ pin (ผู้ชมไม่เห็น)

### Target Customer
**ทั้ง A1 (legit ops) + A2 (multi-account)** — แต่ public positioning = A1 เท่านั้น

---

## 10. แหล่งอ้างอิง

- [TikTok Live Replay (TH)](https://support.tiktok.com/th/live-gifts-wallet/tiktok-live/tiktok-live-replay)
- [TikTok Next 2026 Trend Report](https://ads.tiktok.com/business/library/TikTok_Next_2026_Trend_Report.pdf)
- [TikTok LIVE Global Phenomenon (TH Newsroom)](https://newsroom.tiktok.com/th-th/tiktok-live-global-phenomenon)
- [TikTok LIVE Prohibited Content 2026](https://tiktokstats.com/articles/tiktok-live-prohibited-content-2026-safety-guide)
- [TikTok Updates Live Stream Copyright Rules](https://www.tiktok.com/en/trending/detail/tiktok-updates-live-stream-copyright-rules)
- [How to Record TikTok Live Streams 2026](https://www.streamarchive.io/blog/how-to-record-tiktok-live-streams)
- [OneStream — Schedule TikTok Live](https://onestream.live/tiktok-live-stream/)
- [StreamRecorder.io TikTok](https://streamrecorder.io/tiktok)
- [GREC TikTok Live Recording 2026](https://www.grecrecorder.com/blog/how-to-record-tiktok-live-streams)
- [tiktok-live-recorder (GitHub)](https://github.com/Michele0303/tiktok-live-recorder)
- [BlackHatWorld — Pre-recorded loop discussion](https://www.blackhatworld.com/seo/tiktok-pre-recorded-video-on-loop-for-tiktok-live.1536704/)
- [Top TikTok Influencers Thailand (HypeAuditor)](https://hypeauditor.com/top-tiktok-thailand/)
- [How to make money from TikTok Live (TH)](https://www.thestreetratchada.com/Blogs/240/how-to-make-money-from-TikTok)
- [TikTok Live Shopping Strategy (TH)](https://blog.mandalasystem.com/th/tiktok-live-shopping)
- [PRISM Live Studio Mobile Guide](https://guide.prismlive.com/mobile/guides/getting-started-with-prism-mobile)
- [PRISM Android v3.8.0 RTMP Overlay](https://medium.com/prismlivestudio/mobile-android-v3-8-0-update-informational-effects-rtmp-overlay-resume-your-youtube-streams-d44e32ac9c0b)
- [TikTok Livestream Policy 2026 (Ecomobi)](https://ecomobi.com/tiktok-livestream-policy/)
- [TikTok Live Stream Violation Solutions 2026](https://www.alibaba.com/product-insights/tiktok-live-stream-violation-solutions-2026.html)
- [TikTok Live MOD APK warning](https://tiktok-live.apktodo.io/)
- [How to Stream on TikTok with OBS (Restream)](https://restream.io/learn/obs-studio/how-to-stream-on-tiktok-with-obs/)
- [TikMatrix — Professional TikTok Account Management](https://tikmatrix.com/)
- [TikMatrix How to Build a TikTok Phone Farm](https://tikmatrix.com/blog/how-to-build-tiktok-phonefarm)
- [Chinese 100-phone TikTok streamer farm reference](https://www.tiktok.com/discover/chinese-influencer-farm-phones)
