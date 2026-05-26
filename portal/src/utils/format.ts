// Display helpers — always render UTC inputs in Asia/Bangkok local time.

const TZ = 'Asia/Bangkok';

const dateTimeFmt = new Intl.DateTimeFormat('th-TH', {
  timeZone: TZ,
  dateStyle: 'medium',
  timeStyle: 'short',
});

const timeFmt = new Intl.DateTimeFormat('th-TH', {
  timeZone: TZ,
  timeStyle: 'short',
});

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return dateTimeFmt.format(d);
}

export function formatTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return timeFmt.format(d);
}

export function formatBytes(bytes?: number | null): string {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function formatDuration(seconds?: number | null): string {
  if (seconds === null || seconds === undefined) return '—';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function relativeFromNow(iso?: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (isNaN(t)) return '—';
  const diffSec = Math.floor((Date.now() - t) / 1000);
  if (diffSec < 5) return 'เมื่อสักครู่';
  if (diffSec < 60) return `${diffSec} วินาทีก่อน`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin} นาทีก่อน`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr} ชั่วโมงก่อน`;
  const diffDay = Math.floor(diffHr / 24);
  return `${diffDay} วันก่อน`;
}
