import { useState } from 'react';
import type { DeviceCaps } from '../types/api';

interface Check {
  key: keyof DeviceCaps;
  label: string;
  // What to tell the operator if this check is failing.
  fix: string;
}

const REQUIRED_CHECKS: Check[] = [
  {
    key: 'notification_permission',
    label: 'Notification permission',
    fix: 'Android Settings → Apps → Reruni → Notifications → อนุญาต',
  },
  {
    key: 'overlay_permission',
    label: 'Overlay permission (Smart Overlay)',
    fix: 'Android Settings → Apps → Special access → Display over other apps → Reruni → อนุญาต',
  },
  {
    key: 'accessibility_enabled',
    label: 'Accessibility service (Autopilot)',
    fix: 'Android Settings → Accessibility → Reruni Autopilot → เปิด',
  },
  {
    key: 'battery_unrestricted',
    label: 'Battery unrestricted',
    fix: 'Android Settings → Apps → Reruni → Battery → ไม่จำกัด (Unrestricted)',
  },
  {
    key: 'tiktok_installed',
    label: 'TikTok app installed',
    fix: 'Play Store → ค้นหา "TikTok" → ติดตั้ง',
  },
];

export function DeviceReadiness({ caps, reportedAt }: { caps?: DeviceCaps | null; reportedAt?: string | null }) {
  const [open, setOpen] = useState(false);

  if (!caps) {
    return (
      <span className="text-xs text-slate-400">ยังไม่ได้รายงาน (รอ device ออนไลน์)</span>
    );
  }

  const failing = REQUIRED_CHECKS.filter((c) => caps[c.key] !== true);
  const ok = failing.length === 0;

  return (
    <div>
      <button
        type="button"
        className={`inline-flex items-center gap-1.5 text-xs font-medium px-2 py-1 rounded border ${
          ok
            ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
            : 'border-amber-200 bg-amber-50 text-amber-700'
        }`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`w-1.5 h-1.5 rounded-full ${ok ? 'bg-emerald-500' : 'bg-amber-500'}`} />
        {ok ? 'พร้อมใช้งาน' : `ต้องตั้งค่า (${failing.length})`}
        <span className="text-[10px] text-slate-500">{open ? '▴' : '▾'}</span>
      </button>

      {open && (
        <div className="mt-2 border border-slate-200 rounded bg-white p-3 space-y-2 max-w-md">
          <div className="text-[11px] text-slate-500 font-mono">
            {caps.device_model ?? '?'} · sdk {caps.android_sdk ?? '?'} · app v{caps.app_version ?? '?'}
            {reportedAt && <> · รายงานเมื่อ {new Date(reportedAt).toLocaleString('th-TH')}</>}
          </div>

          <ul className="text-xs space-y-1.5">
            {REQUIRED_CHECKS.map((c) => {
              const passed = caps[c.key] === true;
              return (
                <li key={String(c.key)} className="flex items-start gap-2">
                  <span className={`mt-0.5 ${passed ? 'text-emerald-600' : 'text-amber-600'}`}>
                    {passed ? '✓' : '!'}
                  </span>
                  <div className="flex-1">
                    <div className={passed ? 'text-slate-700' : 'text-slate-900 font-medium'}>
                      {c.label}
                    </div>
                    {!passed && (
                      <div className="text-[11px] text-slate-500 mt-0.5">{c.fix}</div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>

          <div className="text-[10px] text-slate-400 pt-1 border-t border-slate-100">
            สถานะอัพเดทอัตโนมัติทุกครั้งที่ device เชื่อมต่อใหม่
          </div>
        </div>
      )}
    </div>
  );
}
