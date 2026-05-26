import { useEffect, useMemo, useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Spinner } from '../components/Spinner';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { Button } from '../components/Button';
import { listLiveHistory } from '../api/lives';
import { listDevices } from '../api/devices';
import type { Device, LiveSession } from '../types/api';
import { formatDateTime } from '../utils/format';

const PAGE_SIZE = 25;

export function HistoryPage() {
  const [items, setItems] = useState<LiveSession[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [deviceFilter, setDeviceFilter] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function loadDevices() {
      try {
        const res = await listDevices(200);
        setDevices(res.items);
      } catch {
        // ignore
      }
    }
    loadDevices();
  }, []);

  useEffect(() => {
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const res = await listLiveHistory(
          PAGE_SIZE,
          offset,
          deviceFilter === '' ? undefined : Number(deviceFilter),
        );
        setItems(res.items);
        setTotal(res.total ?? res.items.length);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'โหลดประวัติไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [offset, deviceFilter]);

  const deviceMap = useMemo(() => {
    const m = new Map<number, Device>();
    for (const d of devices) m.set(d.id, d);
    return m;
  }, [devices]);

  const pageStart = total === 0 ? 0 : offset + 1;
  const pageEnd = Math.min(offset + PAGE_SIZE, total);

  return (
    <div>
      <PageHeader
        title="ประวัติ Live"
        description="Live sessions 90 วันย้อนหลัง"
      />

      <Card className="p-4 mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label className="text-xs text-slate-500 block mb-1">อุปกรณ์</label>
          <select
            value={deviceFilter}
            onChange={(e) => {
              setDeviceFilter(e.target.value ? Number(e.target.value) : '');
              setOffset(0);
            }}
            className="rounded border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            <option value="">ทั้งหมด</option>
            {devices.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name || `อุปกรณ์ #${d.id}`}
              </option>
            ))}
          </select>
        </div>
      </Card>

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {loading ? (
        <Spinner label="กำลังโหลด…" />
      ) : items.length === 0 ? (
        <EmptyState
          title="ยังไม่มีประวัติ"
          description="ประวัติ live sessions จะแสดงที่นี่หลังจากคุณเริ่ม broadcast"
        />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-slate-600 text-left">
                <tr>
                  <Th>อุปกรณ์</Th>
                  <Th>ชื่อ Live</Th>
                  <Th>เริ่ม</Th>
                  <Th>จบ</Th>
                  <Th>เหตุผลที่จบ</Th>
                </tr>
              </thead>
              <tbody>
                {items.map((l) => (
                  <tr key={l.id} className="border-t border-slate-100">
                    <Td>
                      {deviceMap.get(l.device_id)?.name ?? `#${l.device_id}`}
                    </Td>
                    <Td>{l.title ?? '—'}</Td>
                    <Td className="whitespace-nowrap">
                      {formatDateTime(l.started_at)}
                    </Td>
                    <Td className="whitespace-nowrap">
                      {formatDateTime(l.ended_at)}
                    </Td>
                    <Td>
                      <EndReasonBadge reason={l.end_reason} />
                    </Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between p-3 border-t border-slate-100 text-sm text-slate-600">
            <span>
              {total > 0 ? `${pageStart}-${pageEnd} จาก ${total}` : '—'}
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
              >
                ก่อนหน้า
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={offset + PAGE_SIZE >= total}
                onClick={() => setOffset(offset + PAGE_SIZE)}
              >
                ถัดไป
              </Button>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function EndReasonBadge({ reason }: { reason: string | null | undefined }) {
  if (!reason) return <span className="text-slate-400">—</span>;
  const label =
    reason === 'user_stop'
      ? 'ผู้ใช้กดหยุด'
      : reason === 'tiktok_end'
        ? 'TikTok สั่งจบ'
        : reason === 'error'
          ? 'เกิดข้อผิดพลาด'
          : reason;
  const cls =
    reason === 'error'
      ? 'bg-rose-100 text-rose-700 border-rose-200'
      : 'bg-slate-100 text-slate-700 border-slate-200';
  return (
    <span
      className={`inline-block rounded-full border px-2 py-0.5 text-xs ${cls}`}
    >
      {label}
    </span>
  );
}

function Th({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return (
    <th className={`px-4 py-2 text-xs font-medium uppercase tracking-wider ${className}`}>
      {children}
    </th>
  );
}
function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-4 py-3 align-middle ${className}`}>{children}</td>;
}
