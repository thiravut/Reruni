// /setup-guide — paying-customer-only page (rendered inside AppLayout shell).
// v0.1.0 BYOD path: install bundled TikTok APK + Reruni Controller — no root,
// no Magisk, no bootloader unlock. Written for Thai sellers, not engineers.
// Permissions are walked through by the Reruni app itself after QR pair, so
// this guide stops at "pair" and doesn't enumerate permission switches.

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
          <li>มือถือ Android 9 ขึ้นไป (เช็คได้ที่ Settings → About phone)</li>
          <li>Wi-Fi (จะดาวน์โหลดไฟล์รวมกัน ~575 MB)</li>
          <li>บัญชี TikTok ที่สามารถ Live และปักตะกร้าได้</li>
          <li>เวลาประมาณ 15-20 นาที</li>
        </ul>
      </div>

      {error && (
        <div className="mb-6 p-3 rounded border border-rose-200 bg-rose-50 text-sm text-rose-700">
          โหลดข้อมูลไฟล์ไม่สำเร็จ: {error}
        </div>
      )}

      <Notice />

      <Step n={1} title="เตรียมมือถือก่อนติดตั้ง">
        <Substep>
          <b>1. ปิดอัพเดตอัตโนมัติของ Play Store</b>
          <p className="text-xs text-slate-600 mt-1">
            <b>Play Store</b> → กดรูปโปรไฟล์มุมขวาบน → <b>การตั้งค่า (Settings)</b> → <b>การตั้งค่าเครือข่าย (Network preferences)</b> → <b>อัปเดตอัตโนมัติ (Auto-update apps)</b> → เลือก <b>"ไม่อัปเดตอัตโนมัติ"</b>
          </p>
        </Substep>
        <Substep>
          <b>2. เปิดโหมด Developer</b>
          <p className="text-xs text-slate-600 mt-1">
            <Path>การตั้งค่า (Settings) → เกี่ยวกับโทรศัพท์ (About phone) → ข้อมูลซอฟต์แวร์ (Software information)</Path>
          </p>
          <p className="text-xs text-slate-600 mt-1">
            แตะที่ <b>"หมายเลขบิวด์ (Build number)"</b> รัวๆ <b>7 ครั้ง</b> จนเห็นข้อความ "คุณเป็นนักพัฒนาแล้ว"
          </p>
        </Substep>
        <Substep>
          <b>3. ลบ TikTok เดิมออกก่อน (ถ้ามี)</b>
          <p className="text-xs text-slate-600 mt-1">
            กดค้างที่ไอคอน TikTok → <b>"ถอนการติดตั้ง (Uninstall)"</b>
          </p>
          <p className="text-xs text-slate-600 mt-1">
            ⚠️ ถ้า login บัญชีไว้ — เตรียม password ไว้ login ใหม่ในขั้นถัดไป
          </p>
        </Substep>
      </Step>

      <Step
        n={2}
        title="ติดตั้ง TikTok เวอร์ชั่นของ Reruni"
        download={tiktokBundle}
        fullUrl={fullUrl}
      >
        <Substep>
          กดปุ่ม <b>"ดาวน์โหลด"</b> ข้างล่าง — ไฟล์ขนาด <b>~565 MB</b> ใช้ Wi-Fi เท่านั้น
          <p className="text-xs text-slate-600 mt-1">
            💡 ไฟล์ที่ดาวน์โหลดเสร็จจะอยู่ในโฟลเดอร์ <b>Downloads</b> (เปิดแอพ "ไฟล์ของฉัน / My Files")
          </p>
        </Substep>
        <Substep>
          เปิดไฟล์ที่ดาวน์โหลด → กด <b>"ติดตั้ง (Install)"</b>
          <p className="text-xs text-slate-600 mt-1">
            ⚠️ ถ้ามี popup เตือนว่า "ไฟล์นี้อาจไม่ปลอดภัย" หรือ "ต้องเปิดสิทธิ์ติดตั้ง" — กด <b>"อนุญาต / ติดตั้งต่อ / Install anyway"</b> ปลอดภัย เพราะเป็นไฟล์ของเราเอง
          </p>
        </Substep>
        <Substep>
          เปิดแอพ TikTok ที่เพิ่งติดตั้ง → <b>login ด้วยบัญชี</b> ที่ Live และปักตะกร้าได้
          <p className="text-xs text-slate-600 mt-1">
            ⚠️ ถ้าเจอ <b>CAPTCHA (ภาพให้กด)</b> ซ้ำๆ — แปลว่า TikTok สงสัยว่า login บ่อย พักไว้ <b>1-2 ชม.</b> ก่อนลองใหม่
          </p>
        </Substep>
      </Step>

      <Step
        n={3}
        title="ติดตั้ง Reruni Controller"
        download={rerunController}
        fullUrl={fullUrl}
      >
        <Substep>
          กด "ดาวน์โหลด" ข้างล่าง — ไฟล์เล็ก <b>~10 MB</b>
        </Substep>
        <Substep>
          เปิดไฟล์ที่ดาวน์โหลด → กด <b>"ติดตั้ง"</b>
          <p className="text-xs text-slate-600 mt-1">
            (ถ้าเจอ popup เตือน — กด "Install anyway" เหมือนตอนติดตั้ง TikTok)
          </p>
        </Substep>
        <Substep>
          เปิดแอพ Reruni → จะเห็นปุ่ม <b>"Scan QR"</b> รออยู่
        </Substep>
      </Step>

      <Step n={4} title="เชื่อมเครื่องกับ Portal">
        <Substep>
          เปิดหน้า <Link to="/devices" className="text-brand-600 underline font-medium">อุปกรณ์ (Devices)</Link> บน portal (ที่หน้าคอม) → กดปุ่ม <b>"+ เชื่อมอุปกรณ์ใหม่"</b>
          <p className="text-xs text-slate-600 mt-1">
            QR code จะโผล่ขึ้นมาบนหน้าจอคอม
          </p>
        </Substep>
        <Substep>
          ในแอพ Reruni บนมือถือ → กด <b>"Scan QR"</b> → ส่องกล้องไปที่ QR code
        </Substep>
        <Substep>
          หลังสแกนสำเร็จ — <b>แอพ Reruni จะพาคุณไปเปิดสิทธิ์ที่จำเป็นทีละข้อ</b> ทำตามที่แอพบอกได้เลย
          <p className="text-xs text-slate-600 mt-1">
            💡 ทุกสิทธิ์ที่แอพขอจำเป็นทั้งหมด — กดอนุญาตทั้งหมดได้ไม่ต้องลังเล
          </p>
        </Substep>
        <Substep>
          เปิดสิทธิ์ครบแล้ว → กลับมาที่ portal → เครื่องจะโผล่ในตาราง พร้อม <b>"ความพร้อม"</b> เขียวทุกช่อง
          <p className="text-xs text-slate-600 mt-1">
            ถ้ามีช่องไหนแดง — กลับไปที่แอพ Reruni → Settings → เปิดสิทธิ์ที่ขาดให้ครบ
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

function Notice() {
  return (
    <div className="mb-6 p-4 rounded-lg border border-sky-200 bg-sky-50 text-sm text-sky-900">
      <p className="font-medium mb-1">💡 แนะนำ</p>
      <p>
        ใช้เครื่องนี้เป็นเครื่องสำหรับ <b>broadcast เท่านั้น</b> —
        ไม่ควรใช้เป็นมือถือส่วนตัวคู่กัน เพื่อให้ live เสถียรและไม่กระทบการใช้งานอื่น
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

function Substep({ children }: { children: React.ReactNode }) {
  return <div className="text-sm">{children}</div>;
}

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
