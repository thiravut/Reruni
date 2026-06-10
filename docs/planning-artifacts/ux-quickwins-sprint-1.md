# UX Quick-Wins Sprint #1 — Portal

**Owner:** Sally (UX Designer)
**Date:** 2026-06-10
**Status:** Draft for review

## เป้าหมายของ sprint นี้

แก้ 5 จุดที่ทำให้ operator รู้สึก "ไม่มั่นใจ" หรือ "ตาบอด" ระหว่าง live โดย:
- **ไม่ refactor architecture** — ใช้ infrastructure ที่มีอยู่ (realtime, Confirm, ErrorBanner)
- **ไม่รอ backend ใหม่** — เลื่อน feature ที่ต้อง backend (live preview, SKU validation) ไป sprint ถัดไป
- **ship ได้ทีละข้อ** — แต่ละ section คือ PR แยกได้

ลำดับการทำตามผลลัพธ์ต่อ operator (สูง → ต่ำ):

| # | งาน | ความซับซ้อน | Impact |
|---|------|------------|--------|
| 1 | ActiveLives: realtime device status | S | สูงมาก |
| 2 | ActiveLives: per-card error + activity log | M | สูง |
| 3 | LiveConfig: pre-flight confirm summary | S | สูง |
| 4 | Confirm pattern: แทน window.confirm | S | กลาง |
| 5 | Onboarding: polling progress + check-now button | S | กลาง |

> **หมายเหตุ**: ข้อ "SetupGuide screenshots" เลื่อนออก — เป็น content work + ต้องถ่ายภาพหน้า Android หลายรุ่น ไม่ใช่ pure dev task

---

## 1. ActiveLivesPage — สถานะ device จาก realtime

**ไฟล์:** [portal/src/pages/ActiveLivesPage.tsx](portal/src/pages/ActiveLivesPage.tsx)

### Problem

หน้านี้แสดง device status จาก snapshot ที่ fetch ตอน load (line 232: `<StatusBadge status={device.status} />`) ระบบ realtime infrastructure มีอยู่แล้วใน [RealtimeContext.tsx](portal/src/contexts/RealtimeContext.tsx) (`deviceStatuses` Map ที่ patch จาก WS `device_status_changed`) — แต่หน้านี้ไม่ subscribe

**Scenario ที่ทำให้ operator เจ็บ:**
- เปิดหน้า /live/active ดู 5 devices ที่กำลัง live
- device #3 batt หมด → app crash → server เห็นเป็น offline
- หน้า ActiveLives ยังโชว์ "Live" สีเขียวกระพริบ
- operator กดสั่ง "สลับวิดีโอ" → ส่งคำสั่งไม่ถึง → toast error → งง

### Change

แก้ใน [ActiveLivesPage.tsx](portal/src/pages/ActiveLivesPage.tsx) ใช้ pattern เดียวกับ [LiveConfigPage.tsx:70-76](portal/src/pages/LiveConfigPage.tsx#L70-L76):

```tsx
const rows: LiveRow[] = useMemo(() => {
  return lives.map((l) => {
    const baseDevice = devices.find((d) => d.id === l.device_id);
    const patch = realtime.deviceStatuses.get(l.device_id);
    const device = baseDevice && patch
      ? { ...baseDevice, status: patch.status, last_seen_at: patch.last_seen_at }
      : baseDevice;
    return {
      live: l,
      device,
      video: videos.find((v) => v.id === l.video_id),
    };
  });
}, [lives, devices, videos, realtime.deviceStatuses]);
```

นอกจาก StatusBadge แล้ว เพิ่ม **visual warning** บน card เมื่อ device offline ระหว่าง live:

```
┌─────────────────────────────────────┐
│ อุปกรณ์ #5            [● live]      │
│ ⚠ ขาดการเชื่อมต่อ 23 วินาที         │  ← เพิ่ม strip นี้
│ เริ่ม 15:42 (45 นาทีก่อน)           │
└─────────────────────────────────────┘
```

Logic: ถ้า `device.status === 'offline'` แต่ยังมี active live row → แสดง warning strip พร้อม `relativeFromNow(device.last_seen_at)`

### Acceptance

- เปิด /live/active ใน Chrome 1 tab, เปิด adb force-stop com.rerun.companion ใน device ตัวจริง
- ภายใน 2 วินาที card แสดง warning strip + status badge เปลี่ยนเป็น offline โดยไม่ต้อง reload
- พอ companion app reconnect (start app ใหม่) → strip หายไป + badge กลับเป็น live

### Complexity: S (1-2 ชม.)

---

## 2. ActiveLivesPage — per-card error + activity log

**ไฟล์:** [portal/src/pages/ActiveLivesPage.tsx](portal/src/pages/ActiveLivesPage.tsx)

### Problem

ปัจจุบัน [doAction (line 103-117)](portal/src/pages/ActiveLivesPage.tsx#L103-L117) ใช้ toast เป็นช่องทางเดียวในการแจ้งผล:

```tsx
toast.success(successMsg);
// ...
toast.error(e instanceof ApiError ? e.message : 'คำสั่งล้มเหลว');
```

ปัญหา 3 ชั้น:
1. **toast หายเร็ว** — operator พลาดข้อความ "Stop ไม่สำเร็จ" → เดินจาก → live ยังคงอยู่
2. **ไม่รู้ history** — operator กลับมา 30 นาที จำไม่ได้ว่า "ปัก SKU แล้วยัง? สลับ video ไปยัง?"
3. **`command_completed` event ไม่ถูกใช้** — RealtimeContext.tsx pop toast generic แต่ไม่ tied back to card ที่สั่ง

### Change

**2a. Per-card error slot**

เพิ่ม state `cardErrors: Record<number, { message: string; at: Date }>` และแสดงเป็น ErrorBanner ใน card:

```
┌─────────────────────────────────────┐
│ อุปกรณ์ #5            [● live]      │
│ ⚠ ขาดการเชื่อมต่อ 23 วินาที         │
│ ╭─────────────────────────────────╮ │
│ │ ✕ Pin SKU ล้มเหลว — DEVICE_OFFLINE│ │  ← persistent
│ │   [ลองอีกครั้ง]  [ปิด]            │ │
│ ╰─────────────────────────────────╯ │
│ เริ่ม 15:42 ...                    │
└─────────────────────────────────────┘
```

แก้ doAction ให้ set/clear cardErrors แทน toast (toast เก็บไว้แค่ success ก็พอ)

**2b. Activity log mini (in-memory)**

เพิ่ม state `activityLog: Record<number, ActivityEntry[]>` โดย ActivityEntry = `{ at: Date; kind: 'pin' | 'unpin' | 'switch' | 'restart' | 'stop' | 'banner'; detail: string }`

ทุก successful doAction → push entry เข้า log ของ liveId นั้น

Render ใต้ action buttons เป็น collapsible:

```
▾ กิจกรรมล่าสุด (3)
  • ปัก SKU SKU-2024  (5 นาทีก่อน)
  • สลับเป็น video_B.mp4  (12 นาทีก่อน)
  • เริ่ม live  (45 นาทีก่อน)
```

**2c. Subscribe `command_completed`**

ปัจจุบัน [RealtimeContext.tsx:89-95](portal/src/contexts/RealtimeContext.tsx#L89-L95) pop toast generic — เพิ่ม listener interface `onCommandCompleted` แล้วให้ ActiveLivesPage subscribe เพื่อ:
- ถ้า command ที่ส่งจาก card ใด fail → push error เข้า cardErrors ของ card นั้น
- ถ้า ack → push เข้า activityLog

**หมายเหตุสำหรับ implementation**: ต้อง thread `command_id` จาก API response กลับมา map กับ `live.id` ใน local state — ปัจจุบัน api/lives.ts function `switchVideo`, `pinProduct`, etc. return `CommandRef` (มี command_id) แต่ doAction ทิ้ง

### Acceptance

- ปัก SKU บน device ที่ offline → เห็น persistent error strip ใน card นั้น (ไม่ใช่แค่ toast)
- กด "ลองอีกครั้ง" → ลอง action ใหม่
- กด ๆ action บน card สำเร็จ → กิจกรรมล่าสุดมี entry ใหม่ใน 1 วินาที
- reload page → activity log หายไป (in-memory เท่านั้น) แต่ live ยังอยู่

### Complexity: M (4-6 ชม.)

> **Out of scope**: persistent activity log (ต้อง backend table) — ถ้าจะ phase 2 ค่อยทำ

---

## 3. LiveConfigPage — Pre-flight confirm summary

**ไฟล์:** [portal/src/pages/LiveConfigPage.tsx](portal/src/pages/LiveConfigPage.tsx)

### Problem

ปุ่ม "เริ่ม Live ทันที" (line 382-384) submit form ตรง ๆ ไม่มี confirm step — operator click ผิดครั้งเดียว = 10 devices เริ่ม broadcast ผิด config

### Change

แทรก `<Confirm>` ก่อน submit จริง:

```tsx
// State ใหม่
const [showConfirm, setShowConfirm] = useState(false);

async function handleSubmit(e: React.FormEvent) {
  e.preventDefault();
  // ... validation เดิมทั้งหมด
  if (tErr || cErr || hErr || vErr || dErr || lErr) return;
  setShowConfirm(true);  // ← เปลี่ยนจาก setSubmitting(true)
}

async function handleConfirmStart() {
  setShowConfirm(false);
  setSubmitting(true);
  // ... ส่ง API เดิม
}
```

**Confirm modal content:**

```
┌─ ยืนยันเริ่ม Live ────────────────────┐
│                                       │
│ จะส่งคำสั่งเริ่ม live ทันทีบน:        │
│                                       │
│ • 5 อุปกรณ์:                         │
│   - device A                          │
│   - device B (กำลัง live อยู่)        │
│   - device C                          │
│   - +2 อื่น ๆ                         │
│                                       │
│ • 3 วิดีโอ (round-robin):             │
│   #1 sale_day3.mp4                    │
│   #2 product_intro.mp4                │
│   #3 testimonial.mp4                  │
│                                       │
│ • ชื่อ: "Sale Day 3 ลด 50%"          │
│ • SKU ที่ปัก: SKU-2024 ⚠              │
│ • วนลูป: 3 รอบ                       │
│                                       │
│      [แก้กลับ]   [เริ่ม Live →]      │
└───────────────────────────────────────┘
```

จุดสำคัญ:
- แสดง **เลขที่ของวิดีโอ #1, #2, #3** ตามที่กดเลือก → operator เห็นชัดว่าเรียงตามอะไร
- เตือนถ้ามี device ที่ status `live` อยู่แล้ว
- ถ้ามี SKU ปัก แสดง ⚠ พร้อม hint "ตรวจสะกดให้แน่ใจ" (เพราะยังไม่ validate ฝั่ง backend)

### Acceptance

- กรอก form ครบ กด "เริ่ม Live ทันที" → modal เปิด
- กด "แก้กลับ" → modal ปิด form ยังคงค่าเดิม
- กด "เริ่ม Live →" → ส่ง API เดิม + navigate ไป /live/active
- ถ้า validation ล้มเหลว → ไม่เปิด modal (กลับไป error inline เหมือนเดิม)

### Complexity: S (1-2 ชม.)

---

## 4. Confirm pattern consistency

### Problem

ปัจจุบันมี 3 แบบปนกัน:

| ที่ | Pattern | ปัญหา |
|---|---------|-------|
| [BillingPage.tsx:57](portal/src/pages/BillingPage.tsx#L57) | `window.confirm()` | ไม่สวย ไม่ตรง brand |
| LiveConfigPage submit | ไม่มี confirm | ⚠ critical → แก้ในข้อ 3 |
| DevicesPage, VideosPage, ActiveLivesPage | `<Confirm>` modal | ✓ ดีอยู่แล้ว |

### Change

แก้ [BillingPage.tsx:56-57](portal/src/pages/BillingPage.tsx#L56-L57):

```tsx
// ก่อน
async function handleCancel() {
  if (!confirm('ยกเลิกการต่ออายุเมื่อสิ้นสุดรอบบิลปัจจุบัน?')) return;
  // ...
}

// หลัง
const [showCancelConfirm, setShowCancelConfirm] = useState(false);

async function doCancel() {
  setShowCancelConfirm(false);
  setBusy('cancel');
  // ... เดิม
}

// ใน return:
<Confirm
  open={showCancelConfirm}
  title="ยืนยันยกเลิกแพ็กเกจ"
  message="ระบบจะหยุดต่ออายุอัตโนมัติเมื่อสิ้นรอบบิลปัจจุบัน คุณยังใช้งานต่อได้จนถึงวันที่หมดอายุ"
  confirmLabel="ยกเลิกการต่ออายุ"
  danger
  onConfirm={doCancel}
  onCancel={() => setShowCancelConfirm(false)}
/>
```

### Acceptance

- กดปุ่ม "ยกเลิกแพ็กเกจ" → modal สวย ๆ เปิดแทน browser confirm
- ข้อความอธิบายผลกระทบชัด ("ยังใช้งานต่อได้จนถึงวันที่หมดอายุ") — operator ไม่ตกใจ

### Complexity: S (30 นาที - 1 ชม.)

### Design rule (เขียนใส่ commit / PR description)

> **เมื่อไรต้อง `<Confirm>`:** ทุก action ที่ **(ก) เปลี่ยน state ที่ลูกค้า/ผู้ชมเห็น** หรือ **(ข) ไม่สามารถ undo ได้ใน 1 click**
> ตัวอย่าง: start/stop live, ยกเลิก subscription, ลบ device/video, ลบ banner
> ไม่ต้อง: edit field ที่ save แบบ debounced, toggle ที่ revert ได้ง่าย

---

## 5. OnboardingPage — polling progress + manual check

**ไฟล์:** [portal/src/pages/OnboardingPage.tsx](portal/src/pages/OnboardingPage.tsx)

### Problem

`PaymentWaitingStep` ([line 232-243](portal/src/pages/OnboardingPage.tsx#L232-L243)) และ `PairDeviceStep` ([line 319-370](portal/src/pages/OnboardingPage.tsx#L319-L370)) ใช้ polling 3 วินาทีโดย:
- ไม่บอก elapsed time
- ไม่มีปุ่ม "เช็คเดี๋ยวนี้"
- ไม่มี help context ถ้านานผิดปกติ

→ user คิดว่า hang → refresh ก็เสี่ยง → loop ของ Stripe checkout webhook ที่ดีเลย์ 30 วินาที = user ทิ้ง

### Change

**5a. Track elapsed time**

```tsx
function PaymentWaitingStep() {
  const startedAt = useRef(Date.now());
  const [elapsedSec, setElapsedSec] = useState(0);

  useEffect(() => {
    const t = setInterval(() => {
      setElapsedSec(Math.floor((Date.now() - startedAt.current) / 1000));
    }, 1000);
    return () => clearInterval(t);
  }, []);

  // ...
}
```

UI:
```
รอผลการชำระเงินจาก Stripe…
รอมาแล้ว 45 วินาที

ปกติใช้เวลา 5-15 วินาที ถ้านานกว่านี้:
  [เช็คสถานะเดี๋ยวนี้]  หรือไปหน้า /billing
```

**5b. Manual check button**

ปุ่มที่ trigger `refresh()` ทันทีโดยไม่รอ interval — ใช้ได้ทุก step ที่ polling

**5c. Help context ขึ้นเมื่อ elapsed > 60s**

```tsx
{elapsedSec > 60 && (
  <div className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">
    💡 รอนานกว่าปกติ — ตรวจสอบ email Stripe หรือลอง refresh
  </div>
)}
```

ทำเหมือนกันสำหรับ `PairDeviceStep`:
- elapsed time: "รอเครื่องแรก online มา X นาที"
- ถ้า elapsed > 90s + `devices_paired === 0` → help context: "ยังไม่เจอเครื่อง? ตรวจสอบว่า APK ติดตั้งเรียบร้อยและสแกน Pair QR แล้ว — กลับไปขั้นตอนที่ 4"

### Acceptance

- เริ่ม checkout → กลับมาขั้น Payment → เห็น "รอมา 5 วิ" → 10 → 30…
- กด "เช็คสถานะเดี๋ยวนี้" → spinner หมุนแว้บ → state update (ถ้า Stripe webhook มาแล้ว)
- รอครบ 60 วิ → แถบ help สีเหลืองโผล่

### Complexity: S (2-3 ชม.)

---

## ลำดับการ ship ที่แนะนำ

### Week 1 (sprint นี้)

1. **PR #1:** ข้อ 1 + ข้อ 4 — เล็ก ไว ทดสอบง่าย ส่ง confidence
2. **PR #2:** ข้อ 3 — เพิ่ม safety net ใหญ่ที่สุด ต่อ operator
3. **PR #3:** ข้อ 5 — รักษา conversion onboarding
4. **PR #4:** ข้อ 2 — งานใหญ่ที่สุดของชุดนี้ ส่งสุดท้ายเพราะ touch หลายส่วน

### Sprint 2 (ถัดไป — ต้อง design + backend support)

- **Live preview** ใน ActiveLivesPage — ต้อง backend endpoint สำหรับ frame snapshot
- **SKU validation** ใน LiveConfig + ActiveLives — ต้อง TikTok Shop product list API
- **SetupGuide screenshots** — ต้อง content production (ถ่ายภาพ A12, A15, รุ่นยอดนิยม)
- **Persistent activity log** — backend table + endpoint

---

## คำถามที่ Pond ต้องช่วยตัดสิน

1. **Toast vs persistent error ใน ActiveLives** — เก็บ toast success ไว้ไหม หรือเอาออกหมดให้ error/log อยู่ใน card อย่างเดียว? (ฉันแนะนำ: เก็บ toast success ไว้ — ให้ feedback ทันที — แต่ error ต้องอยู่ใน card)

2. **Activity log แสดงกี่ entry?** — ฉันเสนอ 3 ล่าสุด + collapsible "ดูทั้งหมด" (in-memory เซสชันเดียว) คุณ Pond อยากเห็นเยอะกว่าไหม?

3. **PaymentWaitingStep — ปุ่ม "ไปหน้า /billing"** เป็น escape hatch ที่ดี แต่ถ้า user กดไปแล้ว ออกจาก onboarding flow → กลับมายังไง? อาจต้องเช็คใน `/billing` page ว่ามี onboarding ค้างไหม แล้วโชว์ banner "กลับไป onboarding"

4. **Pre-flight confirm บน LiveConfig** — ถ้า operator ทำงานเป็นกิจวัตร (เปิด live ทุกวัน) modal นี้จะกลายเป็นคลิกอัตโนมัติเปล่าประโยชน์ไหม? อยาก add "อย่าถามอีก สำหรับวันนี้" checkbox ไหม? (ฉันแนะนำ: **ไม่** — operator ทำซ้ำเป็น session ก็จริง แต่ความเสียหายจากคลิกผิดยังสูง 2 วิที่กดยืนยันคุ้มค่ามาก)
