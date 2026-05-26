# TiktokRerun — Mobile App (POC v0)

แอป Android เล็กๆ ที่ทำหน้าที่เดียว: **เล่นวิดีโอ fullscreen + loop** ให้ TikTok Mobile Gaming screen-share จับไป broadcast เป็น live

> **Architecture:** ดู [../docs/planning-artifacts/technical-architecture-draft.md](../docs/planning-artifacts/technical-architecture-draft.md) — Section 11 (POC v0 decisions)

---

## ✨ Scope v2.0 (current — Layer 1)

**ทำ (3 modes):**
1. **Local mode** — เพิ่มวิดีโอจาก storage → playlist loop fullscreen (ตั้งแต่ v0.1)
2. **Remote control mode (NEW)** — เชื่อมต่อ TiktokRerun Control panel บน web → web สั่งเล่นวิดีโอบน device → device download + เล่นอัตโนมัติ
3. ทั้ง 2 mode ใช้ร่วมกันได้ — connection ทำ background, local playlist ใช้งานปกติ

**ยังไม่ทำใน v2.0:**
- ❌ Auto-start TikTok Live (Accessibility Service — Layer 2)
- ❌ Foreground service (WS dropped เมื่อ app backgrounded นาน)
- ❌ QR pairing (paste token แทน)
- ❌ Multi-tenant auth (single pair token)

**ไม่ทำใน v0 (ตั้งใจ):**
- ❌ Pin product automation → creator pin เองใน TikTok ตามปกติ
- ❌ Backend / WebSocket / web dashboard
- ❌ Multi-device fleet management
- ❌ Cloud video sync

ดู rationale ใน Section 11 ของ tech arch — เก็บไว้ทำใน v1+

---

## 🚀 Setup (ครั้งแรก)

### 1. ติดตั้ง Android Studio

ดาวน์โหลด: <https://developer.android.com/studio>

เลือก **Android Studio Ladybug** (2024.2.1) หรือใหม่กว่า — ใช้กับ AGP 8.5 ที่ project นี้ใช้

ระหว่าง install ให้ติ๊กเลือก:
- Android SDK
- Android SDK Platform 35
- Android Virtual Device (ถ้าจะใช้ emulator) — แต่ POC นี้ต้องใช้ **เครื่องจริง** เพื่อ test กับ TikTok

### 2. เปิด project

```
Android Studio → File → Open → เลือกโฟลเดอร์ /Users/pond/Developer/localhost/TikTok/Rerun/mobile
```

ระหว่าง Gradle sync ครั้งแรก จะดาวน์โหลด dependencies (~5-10 นาที, ครั้งเดียว)

### 3. เตรียมเครื่อง Android

บน Android phone:

1. เปิด **Settings → About phone** → tap "Build number" 7 ครั้ง → ปลด Developer Options
2. เปิด **Settings → Developer options → USB debugging**
3. ต่อ USB กับ Mac → กดยอมรับ RSA fingerprint

ตรวจว่า Mac เห็นเครื่อง:

```bash
# ใน Android Studio terminal หรือหลัง install platform-tools:
adb devices
# ควรเห็นเครื่องของคุณ
```

### 4. Build & install

ใน Android Studio:

- เลือก device (โทรศัพท์ของคุณ) จาก dropdown ด้านบน
- กดปุ่ม **Run ▶** (หรือ `Ctrl+R` / `Cmd+R`)

แอปจะ install ลงเครื่อง + เปิดขึ้นมาเลย

---

## 🧪 Workflow A — Local mode (วิดีโอจากเครื่อง)

1. ในแอป → กด **"+ เพิ่มวิดีโอ"** → เลือกหลายตัว
2. ออกจากแอป → เปิด TikTok → `+` → LIVE → **Mobile Gaming** → **Screen Share** → Allow
3. กลับมาแอป → **"เริ่มเล่น Playlist Loop"** → วิดีโอเล่นต่อเนื่อง

## 🧪 Workflow B — Remote control mode (NEW v2)

**Setup ครั้งแรก:**
1. Mac/laptop: `cd server && go run .` → เปิด browser ไป `http://<Mac-IP>:8080`
2. Dashboard → **"+ สร้าง Pair Token"** → copy token
3. ในแอป → tap **"⚙ ตั้งค่า Server"** → ใส่ URL + paste token + ชื่อ device → กด "บันทึก + เชื่อมต่อ"
4. กลับมาหน้าหลัก → จะเห็น `● Server: ✓` มุมบนขวา

**ใช้งาน:**
5. Dashboard บน laptop → upload วิดีโอ → เลือก device + video → กด **Play ▶**
6. Phone จะเด้ง toast `⏬ กำลังโหลด...` → download เสร็จ → PlayerActivity เปิดขึ้นเล่นวิดีโออัตโนมัติ
7. ใน phone: เริ่ม TikTok Live → Mobile Gaming → Screen Share (เหมือน Workflow A) — Layer 2 จะ automate ส่วนนี้ในอนาคต

> **Tip:** ใช้บัญชี 2 เปิดดู live ของตัวเอง เพื่อ verify ว่าผู้ชมเห็นอะไรจริงๆ
>
> **เกี่ยวกับ aspect ratio:** Mobile Gaming live เป็น **landscape 16:9** — ถ้าวิดีโอ portrait 9:16 ผู้ชมจะเห็น pillarbox ดู Section 11 ของ tech arch (Q11.5)
>
> **ข้อจำกัด WS connection ใน v2.0:** ถ้าออกจากแอปนานๆ (สลับไป TikTok นานๆ) OS อาจ kill connection — กลับมาเปิดแอปจะ auto-reconnect ใหม่ Layer 2 จะแก้ด้วย foreground service

---

## 📋 v0 Success Criteria (verify ตอนเทส)

- [ ] วิดีโอเล่น fullscreen ไม่มีแถบ status/nav bar
- [ ] วิดีโอ loop ต่อเนื่อง ไม่ขาด
- [ ] TikTok screen-share จับวิดีโอเราได้ (ตรวจจากบัญชีที่ดูสด)
- [ ] ไลฟ์ดำเนินได้ ≥30 นาทีโดยไม่ crash/kick
- [ ] เสียงวิดีโอ broadcast ออกไปด้วย (ถ้าไม่ได้ — log เป็น issue audio routing)

---

## 🗂️ โครงสร้างไฟล์

```
mobile/
├── settings.gradle.kts
├── build.gradle.kts                  # root project
├── gradle.properties
└── app/
    ├── build.gradle.kts              # app module + dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/rerun/tiktokrerun/
        │   ├── MainActivity.kt        # หน้าแรก: เลือกวิดีโอ + start
        │   └── PlayerActivity.kt      # หน้าเล่น: ExoPlayer fullscreen loop
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   └── activity_player.xml
            └── values/
                ├── strings.xml
                └── themes.xml
```

---

## 🔮 Roadmap

- ✅ **v0** — single video fullscreen loop
- ✅ **v0.1** — Playlist mode (multi-video sequential loop)
- ✅ **v2.0** — Backend + WebSocket + web dashboard (Layer 1) — **current**
- **v2.1** — Foreground service (กัน OS kill WS connection ตอน app backgrounded)
- **v2.2** — QR pairing (แทน paste token)
- **v3.0** — Layer 2: Accessibility Service auto-start TikTok Live + Screen Share
- **v3.1** — Pin automation via Accessibility Service (revisit Q4.1)
- **v4.0** — Multi-tenant auth + production deploy
