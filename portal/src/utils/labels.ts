import type { DeviceStatus } from '../types/api';

export function deviceStatusLabel(status: DeviceStatus): string {
  switch (status) {
    case 'idle':
      return 'พร้อมใช้งาน';
    case 'pairing':
      return 'กำลังเชื่อม';
    case 'live':
      return 'กำลัง LIVE';
    case 'error':
      return 'มีข้อผิดพลาด';
    case 'offline':
      return 'ออฟไลน์';
    default:
      return status;
  }
}

export function deviceStatusColor(status: DeviceStatus): string {
  switch (status) {
    case 'idle':
      return 'bg-slate-100 text-slate-700 border-slate-200';
    case 'pairing':
      return 'bg-amber-100 text-amber-700 border-amber-200';
    case 'live':
      return 'bg-emerald-100 text-emerald-700 border-emerald-200';
    case 'error':
      return 'bg-rose-100 text-rose-700 border-rose-200';
    case 'offline':
      return 'bg-gray-100 text-gray-500 border-gray-200';
    default:
      return 'bg-slate-100 text-slate-700 border-slate-200';
  }
}
