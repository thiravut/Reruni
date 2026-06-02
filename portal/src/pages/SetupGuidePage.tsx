// /setup-guide — paying-customer-only page (rendered inside AppLayout shell).
// v0.1.0 BYOD path: install bundled TikTok APK + Reruni Controller — no root,
// no Magisk, no bootloader unlock. Customers on Standard/Pro concierge tiers
// receive a pre-prepared device and never see this page.

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
    fetch(`${base}/api/downloads/manifest`, { credentials: 'include' })
      .then((r) => r.json())
      .then((d) => setItems(d.items ?? []))
      .catch((e) => setError(String(e)));
  }, []);

  const byKey = (k: string) => items.find((x) => x.key === k);
  const fullUrl = (item: DownloadItem) => {
    if (item.url.startsWith('http')) return item.url;
    return `${import.meta.env.VITE_API_BASE_URL ?? ''}${item.url}`;
  };

  const tiktokBundle = byKey('tiktok_reruni');
  const rerunController = byKey('reruni_apk');

  return (
    <div className="max-w-3xl mx-auto">
      <header className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight">
          คู่มือเตรียมเครื่องสำหรับ Reruni
        </h1>
        <p className="mt-2 text-slate-600">
          ใช้กับมือถือ Android ของคุณเอง ไม่ต้อง root ไม่ต้องปลด bootloader —
          แค่ติดตั้ง 2 APK แล้วเชื่อมกับ portal
        </p>
        {(tiktokBundle?.version || rerunController?.version) && (
          <p className="mt-1 text-xs text-slate-500">
            เวอร์ชั่นปัจจุบัน: v{tiktokBundle?.version ?? rerunController?.version}
          </p>
        )}
      </header>

      {error && (
        <div className="mb-6 p-3 rounded border border-rose-200 bg-rose-50 text-sm text-rose-700">
          โหลด manifest ไม่สำเร็จ: {error}
        </div>
      )}

      <Warning />

      <Step n={1} title="เตรียมเครื่อง — ตั้งค่า Android">
        <ul className="list-disc pl-6 space-y-1 text-sm">
          <li>มือถือ Android 9 ขึ้นไป — RAM 4GB+ แนะนำ</li>
          <li>เปิด <b>"Install from unknown sources"</b> สำหรับเบราว์เซอร์ / File manager
            <span className="block text-xs text-slate-500 mt-0.5">
              Settings → Apps → (เบราว์เซอร์) → Install unknown apps → Allow
            </span>
          </li>
          <li>ปิด <b>Play Protect</b> ชั่วคราว (อาจ block bundled TikTok ตอนติดตั้ง)
            <span className="block text-xs text-slate-500 mt-0.5">
              Play Store → โปรไฟล์ → Play Protect → Settings → ปิด Scan apps
            </span>
          </li>
          <li>ปิด <b>Auto-update</b> ของ Play Store (กัน Play Store update TikTok ทับเวอร์ชั่นเรา)</li>
        </ul>
      </Step>

      <Step n={2} title="ลบ TikTok เดิม (ถ้ามี)">
        <ol className="list-decimal pl-6 space-y-2 text-sm">
          <li><b>สำคัญ:</b> ก่อนลบ — เปิดแอพ TikTok → ตรวจว่าจำ login ของบัญชี seller ไว้หรือยัง ไม่งั้นต้อง login ใหม่หลังติดตั้ง bundled APK</li>
          <li>Settings → Apps → TikTok → <b>Uninstall</b></li>
          <li>ถ้าเป็นเครื่องที่ไม่เคยใช้ TikTok มาก่อน — ข้ามขั้นนี้</li>
        </ol>
      </Step>

      <Step
        n={3}
        title="ติดตั้ง TikTok (Reruni bundle)"
        download={tiktokBundle}
        fullUrl={fullUrl}
      >
        <ol className="list-decimal pl-6 space-y-2 text-sm">
          <li>ดาวน์โหลด APK ข้างล่าง (~565 MB ใช้ Wi-Fi)</li>
          <li>เปิดไฟล์ที่ดาวน์โหลด → กด <b>Install</b></li>
          <li>เปิดแอพ TikTok ที่ติดตั้งใหม่ → login บัญชี seller ของคุณ</li>
          <li>ตรวจว่าสามารถเข้าหน้า Live Studio ได้ตามปกติ — ถ้า login ไม่ได้/CAPTCHA ขึ้นบ่อย ให้พักไว้สักวันก่อนลอง</li>
        </ol>
      </Step>

      <Step
        n={4}
        title="ติดตั้ง Reruni Controller"
        download={rerunController}
        fullUrl={fullUrl}
      >
        <ol className="list-decimal pl-6 space-y-2 text-sm">
          <li>ดาวน์โหลด Reruni Controller (~10 MB)</li>
          <li>เปิดไฟล์ที่ดาวน์โหลด → กด Install</li>
          <li>เปิดแอพ Reruni — จะเห็นหน้า "Scan QR" รอ pair กับ portal</li>
        </ol>
      </Step>

      <Step n={5} title="ตั้งค่า permissions ของ Reruni Controller">
        <p className="text-sm text-slate-600 mb-2">
          ต้องเปิดทั้งหมดก่อน pair — ไม่งั้น autopilot จะคุม TikTok ไม่ได้
        </p>
        <ul className="list-disc pl-6 space-y-2 text-sm">
          <li><b>Notification</b> — ให้แสดงสถานะ live ใน status bar
            <span className="block text-xs text-slate-500 mt-0.5">
              Settings → Apps → Reruni → Notifications → เปิดทั้งหมด
            </span>
          </li>
          <li><b>Battery — Unrestricted</b> — กันระบบ kill app กลาง live
            <span className="block text-xs text-slate-500 mt-0.5">
              Settings → Apps → Reruni → Battery → Unrestricted
            </span>
          </li>
          <li><b>Display over other apps</b> — ให้ Reruni เห็น TikTok ผ่าน overlay
            <span className="block text-xs text-slate-500 mt-0.5">
              Settings → Apps → Special access → Display over other apps → Reruni → เปิด
            </span>
          </li>
          <li><b>Accessibility</b> — autopilot tap/swipe สั่ง TikTok ผ่านบริการนี้
            <span className="block text-xs text-slate-500 mt-0.5">
              Settings → Accessibility → Reruni Autopilot → เปิด → ยอมรับ warning
            </span>
          </li>
        </ul>
      </Step>

      <Step n={6} title="Pair เครื่องกับ Portal">
        <ol className="list-decimal pl-6 space-y-2 text-sm">
          <li>ใน <Link to="/devices" className="text-brand-600 underline">portal Devices</Link> → กด "เชื่อมอุปกรณ์ใหม่" → QR code ขึ้น</li>
          <li>ในแอพ Reruni → กด "Scan QR" → ส่องที่หน้าจอ portal</li>
          <li>รอสักครู่ → modal บน portal จะปิดเอง + device โผล่ในตาราง</li>
          <li>ตรวจคอลัมน์ <b>"ความพร้อม"</b> — ควรเขียวครบทุก permission ถ้าแดงให้กลับไปขั้น 5</li>
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
      <p className="font-medium">⚠ เครื่องนี้ควรเป็น broadcast-only</p>
      <p className="mt-1">
        bundled TikTok เป็นเวอร์ชั่น modified — <b>ไม่ควรใช้คู่กับบัญชี TikTok ส่วนตัว</b>
        ของคุณ ใช้บัญชี seller โดยเฉพาะ และไม่ควรใช้เครื่องนี้กับ mobile banking /
        e-wallet (เพราะแอพเหล่านั้น detect ว่ามี modified TikTok แล้วอาจ block)
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
      {item.version && (
        <span className="text-xs text-slate-500">v{item.version}</span>
      )}
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
