// /setup-guide — paying-customer-only page (rendered inside AppLayout shell).
// v0.1.0 BYOD path: install bundled TikTok APK + Reruni Controller — no root,
// no Magisk, no bootloader unlock. Written for Thai sellers, not engineers.

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
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight">
          คู่มือเตรียมเครื่องสำหรับ Reruni
        </h1>
        <p className="mt-2 text-slate-600">
          ใช้กับมือถือ Android ของคุณเอง ไม่ต้อง root ไม่ต้องเชื่อมคอม —
          แค่ติดตั้ง 2 แอพแล้วเชื่อมกับ portal
        </p>
        {(tiktokBundle?.version || rerunController?.version) && (
          <p className="mt-1 text-xs text-slate-500">
            เวอร์ชั่นปัจจุบัน: v{tiktokBundle?.version ?? rerunController?.version}
          </p>
        )}
      </header>

      <div className="mb-6 p-4 rounded-lg border border-sky-200 bg-sky-50 text-sm text-sky-900">
        <p className="font-medium mb-1">📋 ก่อนเริ่ม ตรวจให้แน่ว่ามี</p>
        <ul className="list-disc pl-5 space-y-0.5">
          <li>มือถือ Android 9 ขึ้นไป (เช็คได้ที่ Settings → About phone — เลข Android 9, 10, 11, … ใช้ได้หมด)</li>
          <li>Wi-Fi (จะดาวน์โหลดไฟล์รวมกัน ~575 MB — ห้ามใช้ 4G/5G ถ้าไม่อยากเปลือง package)</li>
          <li>บัญชี TikTok seller ที่จะใช้ broadcast (รู้ username + password)</li>
          <li>เวลาประมาณ 15-20 นาที</li>
        </ul>
      </div>

      {error && (
        <div className="mb-6 p-3 rounded border border-rose-200 bg-rose-50 text-sm text-rose-700">
          โหลดข้อมูลไฟล์ไม่สำเร็จ: {error}
        </div>
      )}

      <Warning />

      <Step n={1} title="เตรียมมือถือก่อนติดตั้ง">
        <Why>
          การติดตั้งแอพแบบนี้ Android เรียกว่า "sideload" — ติดตั้งโดยไม่ผ่าน
          Play Store ต้องไปเปิดสิทธิ์ไว้ก่อน ไม่งั้นจะติดตั้งไม่ได้
        </Why>
        <Substep>
          <b>เปิดสิทธิ์ติดตั้งแอพจากแหล่งอื่น</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ไปที่ <Path>การตั้งค่า (Settings) → แอป (Apps) → Chrome (หรือเบราว์เซอร์ที่ใช้) → ติดตั้งแอปที่ไม่รู้จัก (Install unknown apps) → กดเปิด "อนุญาต"</Path>
          </p>
          <p className="text-xs text-slate-500 mt-0.5">
            💡 ถ้าใช้ Samsung Browser หรือ Firefox ก็เปิดสิทธิ์ของแอพนั้นแทน
          </p>
        </Substep>
        <Substep>
          <b>ปิด Play Protect ชั่วคราว</b>
          <p className="text-xs text-slate-600 mt-0.5">
            Play Protect คือระบบของ Google ที่ scan แอพที่ติดตั้งใหม่ —
            ถ้าเจอแอพที่ไม่ใช่ของ Play Store จะ <b>ลบทิ้งให้</b>
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            เปิด <b>Play Store</b> → กดรูปโปรไฟล์มุมขวาบน → <b>Play Protect</b> → ⚙️ มุมขวาบน → ปิด <b>"สแกนแอป" / "Scan apps with Play Protect"</b>
          </p>
        </Substep>
        <Substep>
          <b>ปิดอัพเดตอัตโนมัติของ Play Store</b>
          <p className="text-xs text-slate-600 mt-0.5">
            กัน Play Store ไป update TikTok ทับเวอร์ชั่นเรา (ถ้าโดน update ทับ
            จะใช้กับ Reruni ไม่ได้ ต้องลงใหม่)
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            <b>Play Store</b> → โปรไฟล์ → <b>การตั้งค่า (Settings)</b> → <b>การตั้งค่าเครือข่าย (Network preferences)</b> → <b>อัปเดตอัตโนมัติ (Auto-update apps)</b> → เลือก <b>"ไม่อัปเดตอัตโนมัติ"</b>
          </p>
        </Substep>
      </Step>

      <Step n={2} title="ลบ TikTok เดิมออกก่อน (ถ้ามี)">
        <Why>
          TikTok ของเรา (ที่จะติดตั้งในขั้นที่ 3) ใช้ <b>ชื่อแอพเดียวกัน</b>
          กับของจริง — ติดตั้งทับไม่ได้ ต้องลบของเก่าก่อน
        </Why>
        <Substep>
          ถ้ามี TikTok อยู่ในเครื่องและ <b>login บัญชี seller ค้างไว้</b>:
          <p className="text-xs text-slate-600 mt-0.5">
            ⚠️ พอลบไปจะหลุดออกจากบัญชี — เตรียม password ของบัญชี seller ไว้ login ใหม่ในขั้น 3 (หรือใช้ "Login with phone" + SMS OTP)
          </p>
        </Substep>
        <Substep>
          <b>กดค้างที่ไอคอน TikTok ที่หน้าจอ Home</b> → เลือก <b>"Uninstall" / "ถอนการติดตั้ง"</b>
          <p className="text-xs text-slate-600 mt-0.5">
            หรือถ้าไม่มีไอคอนที่หน้า Home: <Path>Settings → Apps → TikTok → Uninstall</Path>
          </p>
        </Substep>
        <Substep>
          ถ้าไม่เคยติดตั้ง TikTok มาก่อน → ข้ามขั้นนี้ไปขั้น 3 ได้เลย
        </Substep>
      </Step>

      <Step
        n={3}
        title="ติดตั้ง TikTok เวอร์ชั่นของ Reruni"
        download={tiktokBundle}
        fullUrl={fullUrl}
      >
        <Why>
          เวอร์ชั่นนี้ของเราติด <b>VCam</b> ไว้ในตัว — ทำให้เปิด live แล้วยิงวิดีโอที่อัดล่วงหน้าได้
          โดยไม่ต้อง root เครื่อง
        </Why>
        <Substep>
          กดปุ่ม "ดาวน์โหลด" ข้างล่าง — จะเป็นไฟล์ <b>~565 MB</b> ใช้ Wi-Fi เท่านั้น
          <p className="text-xs text-slate-600 mt-0.5">
            💡 ดาวน์โหลดเสร็จไฟล์จะอยู่ในโฟลเดอร์ <b>Downloads</b> ของเครื่อง (เปิด "ไฟล์ของฉัน" / "My Files")
          </p>
        </Substep>
        <Substep>
          เปิดไฟล์ที่ดาวน์โหลด → กด <b>"ติดตั้ง" (Install)</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ⚠️ ถ้ามี popup เตือนว่า "ไฟล์นี้อาจไม่ปลอดภัย" — กด <b>"ติดตั้งต่อ" / "Install anyway"</b> ปลอดภัย เพราะเป็นไฟล์จาก Reruni ของเราเอง
          </p>
        </Substep>
        <Substep>
          เปิดแอพ TikTok ที่เพิ่งติดตั้ง → <b>login ด้วยบัญชี seller</b> ของคุณ
          <p className="text-xs text-slate-600 mt-0.5">
            💡 หลัง login แล้ว ลองเข้าไปที่หน้า Live Studio เพื่อตรวจว่าทุกอย่างทำงานปกติ
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            ⚠️ ถ้า login แล้วเจอ <b>CAPTCHA (ภาพให้กด)</b> ซ้ำๆ หลายรอบ — แปลว่า TikTok สงสัยว่า login บ่อย ลองพักไว้ <b>1-2 ชม.</b> ก่อนลองใหม่
          </p>
        </Substep>
      </Step>

      <Step
        n={4}
        title="ติดตั้ง Reruni Controller"
        download={rerunController}
        fullUrl={fullUrl}
      >
        <Why>
          Reruni Controller คือแอพที่จะ <b>ทำหน้าที่ผู้ช่วย</b> สั่งให้ TikTok
          เริ่ม/หยุด live เปลี่ยน banner ตามที่คุณสั่งจาก portal บนคอม
        </Why>
        <Substep>
          กด "ดาวน์โหลด" ข้างล่าง — ไฟล์เล็ก <b>~10 MB</b>
        </Substep>
        <Substep>
          เปิดไฟล์ที่ดาวน์โหลด → กด <b>"ติดตั้ง"</b>
          <p className="text-xs text-slate-600 mt-0.5">
            (ถ้าระบบเตือนเหมือนตอนติดตั้ง TikTok — กด "Install anyway" ได้เลย)
          </p>
        </Substep>
        <Substep>
          เปิดแอพ Reruni → จะเห็นปุ่ม <b>"Scan QR"</b> รออยู่ — ยังไม่ต้องกด ทำขั้น 5 ก่อน
        </Substep>
      </Step>

      <Step n={5} title="ตั้งค่าสิทธิ์ให้ Reruni Controller">
        <Why>
          Reruni ต้องได้รับ <b>4 สิทธิ์</b> นี้ก่อนถึงจะคุม TikTok ได้
          ถ้าข้ามขั้นนี้ — pair กับ portal ได้ แต่สั่งเริ่ม live ไม่ได้
        </Why>
        <Substep>
          <b>1. การแจ้งเตือน (Notifications)</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ทำไม: ให้แสดงไอคอนค้างไว้ว่าตอนนี้ live อยู่ ไม่งั้น Android อาจปิดแอพให้
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            ในแอพ Reruni → กด "เปิดสิทธิ์การแจ้งเตือน" → กด <b>"อนุญาต"</b> ตอน popup ขึ้น
          </p>
        </Substep>
        <Substep>
          <b>2. แบตเตอรี่ — "ไม่จำกัด" (Unrestricted)</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ทำไม: ปกติ Android จะปิดแอพที่กินแบตมากตอน live นานๆ — ต้องบอกระบบให้ "ปล่อยให้รัน"
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            <Path>Settings → Apps → Reruni → Battery (แบตเตอรี่) → เลือก "ไม่จำกัด" / "Unrestricted"</Path>
          </p>
        </Substep>
        <Substep>
          <b>3. แสดงทับแอพอื่น (Display over other apps)</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ทำไม: ให้ Reruni "มองเห็น" หน้าจอ TikTok ได้ — autopilot ถึงรู้ว่าจะแตะตรงไหน
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            <Path>Settings → Apps → Special access → แสดงทับแอพอื่น (Display over other apps) → Reruni → เปิด</Path>
          </p>
        </Substep>
        <Substep>
          <b>4. การช่วยเหลือพิเศษ (Accessibility)</b>
          <p className="text-xs text-slate-600 mt-0.5">
            ทำไม: ทำให้ Reruni "กดปุ่ม" ใน TikTok แทนคุณได้ — เริ่ม live, ปักตะกร้า, จบ live
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            <Path>Settings → การช่วยเหลือพิเศษ (Accessibility) → Reruni Autopilot → เปิด</Path>
          </p>
          <p className="text-xs text-slate-600 mt-0.5">
            ⚠️ มี popup เตือนยาวๆ ขึ้นมา — อ่านแล้วกด <b>"ตกลง" / "Allow"</b> ปลอดภัย เพราะคุณคุมเองจาก portal
          </p>
        </Substep>
      </Step>

      <Step n={6} title="เชื่อมเครื่องกับ Portal">
        <Why>
          เป็นขั้นสุดท้าย — เชื่อมแอพ Reruni บนมือถือกับบัญชีของคุณบน portal
          ผ่านการสแกน QR code
        </Why>
        <Substep>
          เปิดหน้า <Link to="/devices" className="text-brand-600 underline font-medium">อุปกรณ์ (Devices)</Link> บน portal (ที่หน้าคอม) → กดปุ่ม <b>"+ เชื่อมอุปกรณ์ใหม่"</b>
          <p className="text-xs text-slate-600 mt-0.5">
            จะมี QR code โผล่ขึ้นมาบนหน้าจอคอม
          </p>
        </Substep>
        <Substep>
          ในแอพ Reruni บนมือถือ → กด <b>"Scan QR"</b> → ส่องกล้องไปที่ QR code บนหน้าจอคอม
        </Substep>
        <Substep>
          รอประมาณ 2-3 วินาที → QR code บนคอมจะหายไปเอง → เครื่องโผล่ในตารางอุปกรณ์
          <p className="text-xs text-slate-600 mt-0.5">
            ✅ ดูคอลัมน์ <b>"ความพร้อม"</b> — ควรเขียวทุกช่อง (Notification, Battery, Overlay, Accessibility) ถ้าแดงตัวไหน — กลับไปขั้น 5 เปิดสิทธิ์ตัวนั้นใหม่
          </p>
        </Substep>
        <Substep>
          เสร็จแล้ว 🎉 ลองสั่งเริ่ม live ครั้งแรกได้ที่หน้า <Link to="/live" className="text-brand-600 underline font-medium">เริ่ม Live</Link>
        </Substep>
      </Step>

      <div className="mt-10 p-4 rounded-lg border border-slate-200 bg-white text-sm text-slate-600">
        <p className="font-medium text-slate-800 mb-2">ติดที่ไหน? อยากได้คนช่วย?</p>
        <p>
          ส่งภาพหน้าจอที่ติด + บอกว่าติดขั้นไหน มาที่{' '}
          <a className="text-brand-600 underline" href="mailto:hello@reruni.com">
            hello@reruni.com
          </a>{' '}
          — ทีมเราจะตอบภายใน 1 วันทำการ
        </p>
      </div>
    </div>
  );
}

function Warning() {
  return (
    <div className="mb-6 p-4 rounded-lg border border-amber-200 bg-amber-50 text-sm text-amber-900">
      <p className="font-medium mb-1">⚠ ใช้เครื่องนี้เพื่อ broadcast เท่านั้น</p>
      <ul className="list-disc pl-5 space-y-1">
        <li>
          TikTok เวอร์ชั่นของเราเป็น <b>เวอร์ชั่นแก้</b> — ไม่ควรใช้บัญชี TikTok ส่วนตัวของคุณ
          ให้ใช้บัญชี seller โดยเฉพาะ
        </li>
        <li>
          ไม่ควรลง <b>mobile banking, e-wallet, แอพราชการ</b> ในเครื่องเดียวกัน — แอพพวกนี้ตรวจเจอว่ามี TikTok เวอร์ชั่นแก้แล้วอาจไม่ให้ใช้
        </li>
        <li>
          ใช้เครื่องสำรอง / เครื่องที่ตั้งใจเอาไว้ broadcast โดยเฉพาะ
        </li>
      </ul>
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
    <section className="mb-5 bg-white rounded-lg border border-slate-200 p-5">
      <h2 className="text-lg font-semibold flex items-center gap-2 mb-3">
        <span className="inline-flex items-center justify-center w-7 h-7 rounded-full bg-brand-600 text-white text-sm">
          {n}
        </span>
        {title}
      </h2>
      <div className="text-slate-700 space-y-3">{children}</div>
      {download && fullUrl && (
        <div className="mt-4 pt-4 border-t border-slate-100">
          <DownloadInline item={download} fullUrl={fullUrl} />
        </div>
      )}
    </section>
  );
}

function Why({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-3 py-2 bg-slate-50 rounded text-xs text-slate-600 border-l-2 border-slate-300">
      <span className="font-medium text-slate-700">ทำไมต้องทำขั้นนี้: </span>
      {children}
    </div>
  );
}

function Substep({ children }: { children: React.ReactNode }) {
  return <div className="text-sm">{children}</div>;
}

// Path renders a settings breadcrumb in a slightly distinct style so users can
// see at a glance "this is a sequence of menu taps".
function Path({ children }: { children: React.ReactNode }) {
  return (
    <span className="inline font-mono text-[11px] bg-slate-100 px-1.5 py-0.5 rounded text-slate-700">
      {children}
    </span>
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
      className="inline-flex items-center gap-2 px-4 py-2.5 rounded-md border border-brand-300 bg-brand-50 text-sm font-medium text-brand-700 hover:bg-brand-100"
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
