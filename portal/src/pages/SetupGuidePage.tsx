// /setup-guide — paying-customer-only page (rendered inside AppLayout shell).
// Walks the operator through rooting a broadcast phone, installing Magisk +
// GhostCam + Reruni companion, and pairing with the portal. Downloads come
// from /api/downloads/manifest (subscription-gated); upstream-only items
// (Magisk, LSPosed) link to GitHub releases.

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

interface DownloadItem {
  key: string;
  label: string;
  version?: string;
  size_bytes?: number;
  url: string;
  upstream_url?: string;
  hosted: boolean;
  required: boolean;
}

function formatBytes(n?: number): string {
  if (!n) return '';
  const u = ['B', 'KB', 'MB', 'GB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${u[i]}`;
}

export function SetupGuidePage() {
  const [items, setItems] = useState<DownloadItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const base = import.meta.env.VITE_API_BASE_URL ?? '';
    fetch(`${base}/api/downloads/manifest`)
      .then((r) => r.json())
      .then((d) => setItems(d.items ?? []))
      .catch((e) => setError(String(e)));
  }, []);

  const byKey = (k: string) => items.find((x) => x.key === k);
  const fullUrl = (item: DownloadItem) => {
    if (item.url.startsWith('http')) return item.url;
    return `${import.meta.env.VITE_API_BASE_URL ?? ''}${item.url}`;
  };

  return (
    <div className="max-w-3xl mx-auto">
      <header className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight">
          คู่มือเตรียมอุปกรณ์ (V3 — Device Camera + VCam)
        </h1>
        <p className="mt-2 text-slate-600">
          ใช้กับมือถือ Android ที่จะ broadcast TikTok Live ด้วยวิดีโออัดล่วงหน้า
          ผ่าน Magisk + GhostCam VCam — broadcast แบบ Device camera 9:16 เต็มจอ
          ทุกหมวดสินค้า
        </p>
      </header>

        {error && (
          <div className="mb-6 p-3 rounded border border-rose-200 bg-rose-50 text-sm text-rose-700">
            โหลด manifest ไม่สำเร็จ: {error}
          </div>
        )}

        <Warning />

        <Step
          n={1}
          title="ก่อนเริ่ม — สิ่งที่ต้องเตรียม"
        >
          <ul className="list-disc pl-6 space-y-1 text-sm">
            <li>มือถือ Android 9+ ที่ <b>unlock bootloader ได้</b> — Pixel, Xiaomi (Mi/POCO/Redmi), OnePlus ส่วนใหญ่ทำได้, Samsung รุ่นใหม่ <b>ไม่ได้</b></li>
            <li>คอมพิวเตอร์ + USB cable</li>
            <li>ติด adb + fastboot บนคอม:
              <span className="block text-xs font-mono text-slate-500 mt-1">
                macOS: <code>brew install android-platform-tools</code><br />
                Windows: ดาวน์โหลด <a className="text-brand-600 underline" href="https://developer.android.com/tools/releases/platform-tools" target="_blank" rel="noreferrer">Android Platform Tools</a>
              </span>
            </li>
            <li><b>สำคัญ:</b> root จะลบข้อมูลทั้งเครื่อง + ปลด warranty — backup ก่อน</li>
          </ul>
        </Step>

        <Step
          n={2}
          title="Unlock bootloader"
        >
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>เปิด <b>Developer options</b> — Settings → About phone → แตะ "Build number" 7 ครั้ง</li>
            <li>เปิด <b>OEM unlocking</b> + <b>USB debugging</b> ใน Developer options</li>
            <li>เชื่อม USB → คอม → run:
              <pre className="bg-slate-900 text-slate-100 text-xs rounded p-3 mt-2 overflow-x-auto">{`adb reboot bootloader
fastboot flashing unlock     # Pixel / Xiaomi
# Samsung: ใช้วิธีแยกผ่าน Odin (ดูคู่มือต่อรุ่น)`}</pre>
            </li>
            <li>กดปุ่ม volume ตามที่หน้าจอเครื่องบอก → confirm unlock → รอ wipe</li>
          </ol>
        </Step>

        <Step
          n={3}
          title="ติดตั้ง Magisk (root)"
          download={byKey('magisk')}
          fullUrl={fullUrl}
        >
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>หา <b>stock boot.img</b> ของเครื่องคุณ (ดาวน์โหลดจาก firmware factory image ของยี่ห้อ — Pixel มีหน้า google.com/android/images, Xiaomi ใช้ Mi Flash)</li>
            <li>copy boot.img เข้าเครื่อง → ติดตั้ง Magisk APK</li>
            <li>เปิด Magisk → <b>Install</b> → <b>Select and Patch a File</b> → เลือก boot.img → ได้ <code>magisk_patched-xxxx.img</code></li>
            <li>copy patched file กลับเข้าคอม:
              <pre className="bg-slate-900 text-slate-100 text-xs rounded p-3 mt-2 overflow-x-auto">{`adb pull /storage/emulated/0/Download/magisk_patched-xxxx.img
adb reboot bootloader
fastboot flash boot magisk_patched-xxxx.img
fastboot reboot`}</pre>
            </li>
            <li>เปิด Magisk app → ควรเห็น <b>Installed</b> + เลขเวอร์ชัน</li>
          </ol>
        </Step>

        <Step
          n={4}
          title="ติดตั้ง GhostCam Module (VCam pipeline)"
          download={byKey('ghostcam')}
          fullUrl={fullUrl}
        >
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>copy ไฟล์ <code>ghostcam-magisk.zip</code> เข้าเครื่อง</li>
            <li>เปิด Magisk → <b>Modules</b> → <b>Install from storage</b> → เลือก zip</li>
            <li>รอ install เสร็จ → <b>Reboot</b></li>
            <li>หลัง reboot → เปิด GhostCam app → ตั้งวิดีโอ default ที่จะแทนกล้อง (test pattern ก็พอ — Reruni จะ override ตอน live จริง)</li>
          </ol>
        </Step>

        <Step
          n={5}
          title="ติดตั้ง LSPosed + autoCaptcha (ไม่บังคับ)"
          download={byKey('lsposed')}
          fullUrl={fullUrl}
        >
          <p className="text-sm text-slate-600 mb-2">
            ขั้นนี้ <b>ไม่บังคับ</b> ถ้าทีมไม่ใช้ autoCaptcha autopilot — ข้ามไปขั้น 6 ได้
          </p>
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>เปิด Magisk → Settings → เปิด <b>Zygisk</b> → Reboot</li>
            <li>ติดตั้ง LSPosed zip ผ่าน Magisk → Modules → Install from storage</li>
            <li>Reboot → เปิดแอพ LSPosed → install autoCaptcha module:
              <DownloadInline item={byKey('autocaptcha')} fullUrl={fullUrl} />
            </li>
            <li>LSPosed → Modules → autoCaptcha → enable → reboot</li>
          </ol>
        </Step>

        <Step
          n={6}
          title="ติดตั้ง Reruni Companion APK"
          download={byKey('reruni_apk')}
          fullUrl={fullUrl}
        >
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>copy APK เข้าเครื่อง → install (อาจต้องเปิด "Install from unknown sources" ก่อน)</li>
            <li>เปิด Reruni app → อนุญาต permissions:
              <ul className="list-disc pl-6 mt-1">
                <li>Notification</li>
                <li>Battery (ตั้งเป็น "Unrestricted")</li>
                <li>Accessibility — Settings → Accessibility → Reruni Autopilot → เปิด</li>
              </ul>
            </li>
            <li>ตรวจว่า TikTok app ติดตั้งแล้ว (Play Store)</li>
          </ol>
        </Step>

        <Step
          n={7}
          title="Pair เครื่องกับ Portal"
        >
          <ol className="list-decimal pl-6 space-y-2 text-sm">
            <li>ใน <Link to="/devices" className="text-brand-600 underline">portal Devices</Link> → กด "เชื่อมอุปกรณ์ใหม่"</li>
            <li>ในแอพ Reruni → Settings → "Scan QR" → ส่องที่หน้าจอ portal</li>
            <li>กด Save → modal บน portal จะปิดเอง + device โผล่ในตาราง</li>
            <li>ตรวจ <b>"ความพร้อม"</b> ที่ตาราง devices ว่าเป็นเขียวครบทุก permission</li>
          </ol>
        </Step>

        <div className="mt-10 p-4 rounded-lg border border-slate-200 bg-white text-sm text-slate-600">
          ติดที่ไหน? ส่งภาพหน้าจอ + ขั้นที่ติด มาที่{' '}
          <a className="text-brand-600 underline" href="mailto:hello@reruni.com">
            hello@reruni.com
          </a>{' '}
          ทีมเราจะตอบภายใน 1 วันทำการ
        </div>
    </div>
  );
}

function Warning() {
  return (
    <div className="mb-6 p-4 rounded-lg border border-amber-200 bg-amber-50 text-sm text-amber-900">
      <p className="font-medium">⚠ ทำให้สุดทาง = เป็นมือถือ broadcast-only</p>
      <p className="mt-1">
        เครื่องที่ผ่านขั้นตอนนี้แล้ว <b>ไม่ควร</b> ใช้สำหรับการใช้งานส่วนตัว
        (mobile banking, e-wallet, แอพที่ตรวจ root) เพราะ Magisk + custom firmware
        เปลี่ยน device state — ใช้เครื่องสำรองหรือเครื่องที่ตั้งใจ broadcast เท่านั้น
      </p>
    </div>
  );
}

function Step({
  n,
  title,
  children,
  download,
  fullUrl,
}: {
  n: number;
  title: string;
  children: React.ReactNode;
  download?: DownloadItem;
  fullUrl?: (item: DownloadItem) => string;
}) {
  return (
    <section className="mb-6 bg-white rounded-lg border border-slate-200 p-5">
      <h2 className="text-lg font-semibold flex items-center gap-2 mb-3">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-brand-600 text-white text-sm">
          {n}
        </span>
        {title}
      </h2>
      <div className="text-slate-700">{children}</div>
      {download && fullUrl && (
        <div className="mt-4">
          <DownloadInline item={download} fullUrl={fullUrl} />
        </div>
      )}
    </section>
  );
}

function DownloadInline({
  item,
  fullUrl,
}: {
  item?: DownloadItem;
  fullUrl: (item: DownloadItem) => string;
}) {
  if (!item) return null;
  if (!item.hosted && !item.upstream_url) {
    return (
      <div className="inline-flex items-center gap-2 px-3 py-2 rounded border border-slate-200 bg-slate-50 text-sm text-slate-500">
        ⏳ {item.label} — เร็วๆ นี้
      </div>
    );
  }
  return (
    <a
      href={fullUrl(item)}
      target={item.upstream_url ? '_blank' : undefined}
      rel="noreferrer"
      className="inline-flex items-center gap-2 px-3 py-2 rounded border border-brand-200 bg-brand-50 text-sm text-brand-700 hover:bg-brand-100"
    >
      📥 ดาวน์โหลด {item.label}
      {item.size_bytes && (
        <span className="text-xs text-slate-500">
          ({formatBytes(item.size_bytes)})
        </span>
      )}
      {item.upstream_url && (
        <span className="text-xs text-slate-500">↗ GitHub</span>
      )}
    </a>
  );
}
