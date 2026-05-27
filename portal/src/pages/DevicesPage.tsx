import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Modal } from '../components/Modal';
import { Confirm } from '../components/Confirm';
import { Spinner } from '../components/Spinner';
import { ErrorBanner } from '../components/ErrorBanner';
import { EmptyState } from '../components/EmptyState';
import { StatusBadge } from '../components/StatusBadge';
import { TextField } from '../components/Field';
import { ApiError } from '../api/client';
import { useToast } from '../contexts/ToastContext';
import { useRealtime } from '../contexts/RealtimeContext';
import {
  createPairToken,
  deleteDevice,
  listDevices,
  updateDevice,
} from '../api/devices';
import type { Device, DeviceStatus, PairToken } from '../types/api';
import { formatDateTime, relativeFromNow } from '../utils/format';
import { validateDeviceName } from '../utils/validation';

export function DevicesPage() {
  const toast = useToast();
  const realtime = useRealtime();
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Pair modal
  const [pairOpen, setPairOpen] = useState(false);
  const [pairToken, setPairToken] = useState<PairToken | null>(null);
  const [pairLoading, setPairLoading] = useState(false);
  const [pairErr, setPairErr] = useState<string | null>(null);
  const [pairCountdown, setPairCountdown] = useState<number>(0);
  // Snapshot of device IDs at the moment the pair modal opens, so we can
  // detect when the QR has been scanned and a new device just appeared.
  const baselineDeviceIds = useRef<Set<number>>(new Set());

  // Rename modal
  const [renameDevice, setRenameDevice] = useState<Device | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renameErr, setRenameErr] = useState<string | null>(null);
  const [renameLoading, setRenameLoading] = useState(false);

  // Delete modal
  const [deleteTarget, setDeleteTarget] = useState<Device | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const reload = useCallback(async () => {
    setError(null);
    try {
      const res = await listDevices(200, 0);
      setDevices(res.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'โหลดอุปกรณ์ไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  // merge realtime status into device list
  const mergedDevices = useMemo(() => {
    return devices.map((d) => {
      const patch = realtime.deviceStatuses.get(d.id);
      if (!patch) return d;
      return { ...d, status: patch.status, last_seen_at: patch.last_seen_at };
    });
  }, [devices, realtime.deviceStatuses]);

  // listen for pair-triggered device additions
  useEffect(() => {
    return realtime.onDeviceStatus(() => {
      // when an unknown device shows up, refetch
      reload();
    });
  }, [realtime, reload]);

  // Pair countdown
  useEffect(() => {
    if (!pairToken) return;
    function update() {
      const remain = Math.max(
        0,
        Math.floor((new Date(pairToken!.expires_at).getTime() - Date.now()) / 1000),
      );
      setPairCountdown(remain);
    }
    update();
    const id = window.setInterval(update, 1000);
    return () => window.clearInterval(id);
  }, [pairToken]);

  async function handleCreatePairToken() {
    setPairLoading(true);
    setPairErr(null);
    try {
      const token = await createPairToken();
      // Snapshot current device IDs so the auto-close watcher knows what
      // counts as "new" once the user scans the QR.
      baselineDeviceIds.current = new Set(devices.map((d) => d.id));
      setPairToken(token);
      setPairOpen(true);
    } catch (e) {
      setPairErr(e instanceof Error ? e.message : 'สร้าง pair token ไม่สำเร็จ');
      setPairOpen(true);
    } finally {
      setPairLoading(false);
    }
  }

  // Auto-close the pair modal once the just-paired device shows up in our
  // device list. Server broadcasts device_status_changed → onDeviceStatus
  // listener reloads → devices state updates → this effect fires.
  useEffect(() => {
    if (!pairOpen) return;
    const newDevice = devices.find((d) => !baselineDeviceIds.current.has(d.id));
    if (newDevice) {
      setPairOpen(false);
      setPairToken(null);
      toast.success(
        `เชื่อมอุปกรณ์สำเร็จ: ${newDevice.name || `#${newDevice.id}`}`,
      );
    }
  }, [devices, pairOpen, toast]);

  async function handleRename(e: React.FormEvent) {
    e.preventDefault();
    if (!renameDevice) return;
    const err = validateDeviceName(renameValue.trim());
    setRenameErr(err);
    if (err) return;
    setRenameLoading(true);
    try {
      const updated = await updateDevice(renameDevice.id, renameValue.trim());
      setDevices((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
      toast.success('เปลี่ยนชื่ออุปกรณ์เรียบร้อย');
      setRenameDevice(null);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'เปลี่ยนชื่อไม่สำเร็จ');
    } finally {
      setRenameLoading(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleteLoading(true);
    try {
      await deleteDevice(deleteTarget.id);
      setDevices((prev) => prev.filter((d) => d.id !== deleteTarget.id));
      toast.success('ลบอุปกรณ์เรียบร้อย');
      setDeleteTarget(null);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'ลบไม่สำเร็จ');
    } finally {
      setDeleteLoading(false);
    }
  }

  return (
    <div>
      <PageHeader
        title="อุปกรณ์"
        description="จัดการ Android phone ใน fleet ของคุณ"
        actions={
          <Button onClick={handleCreatePairToken} loading={pairLoading}>
            + เชื่อมอุปกรณ์ใหม่
          </Button>
        }
      />

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {loading ? (
        <Spinner label="กำลังโหลด…" />
      ) : mergedDevices.length === 0 ? (
        <EmptyState
          title="ยังไม่มีอุปกรณ์"
          description="กดปุ่ม “เชื่อมอุปกรณ์ใหม่” แล้วสแกน QR ด้วย Companion App บนมือถือเพื่อเริ่ม"
          action={
            <Button onClick={handleCreatePairToken} loading={pairLoading}>
              + เชื่อมอุปกรณ์ใหม่
            </Button>
          }
        />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-slate-600 text-left">
                <tr>
                  <Th>ชื่ออุปกรณ์</Th>
                  <Th>สถานะ</Th>
                  <Th>ออนไลน์ล่าสุด</Th>
                  <Th>SKU ที่ปัก</Th>
                  <Th className="text-right">การจัดการ</Th>
                </tr>
              </thead>
              <tbody>
                {mergedDevices.map((d) => (
                  <tr key={d.id} className="border-t border-slate-100">
                    <Td>
                      <div className="font-medium text-slate-800">
                        {d.name || `อุปกรณ์ #${d.id}`}
                      </div>
                      <div className="text-xs text-slate-500">ID: {d.id}</div>
                    </Td>
                    <Td>
                      <StatusBadge status={d.status as DeviceStatus} />
                    </Td>
                    <Td>
                      <span title={formatDateTime(d.last_seen_at)}>
                        {relativeFromNow(d.last_seen_at)}
                      </span>
                    </Td>
                    <Td>
                      {d.current_pinned_sku ? (
                        <span className="font-mono text-xs">{d.current_pinned_sku}</span>
                      ) : (
                        <span className="text-slate-400">—</span>
                      )}
                    </Td>
                    <Td className="text-right space-x-1 whitespace-nowrap">
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          setRenameDevice(d);
                          setRenameValue(d.name ?? '');
                          setRenameErr(null);
                        }}
                      >
                        เปลี่ยนชื่อ
                      </Button>
                      <Button
                        size="sm"
                        variant="danger"
                        onClick={() => setDeleteTarget(d)}
                      >
                        ยกเลิกการเชื่อม
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Pair modal */}
      <Modal
        open={pairOpen}
        onClose={() => {
          setPairOpen(false);
          setPairToken(null);
        }}
        title="เชื่อมอุปกรณ์ใหม่"
      >
        {pairErr ? (
          <ErrorBanner message={pairErr} />
        ) : pairToken ? (
          <div className="flex flex-col items-center gap-4">
            <div className="bg-slate-100 rounded p-3">
              <img
                src={`${import.meta.env.VITE_API_BASE_URL ?? ''}${pairToken.qr_url}`}
                alt="QR pair token"
                className="w-56 h-56 object-contain"
              />
            </div>
            <div className="w-full text-center">
              <p className="text-sm text-slate-600 mb-1">
                สแกน QR ด้วย Companion App
              </p>
              <p className="font-mono text-xs text-slate-500">{pairToken.token}</p>
            </div>
            <div className="text-xs text-slate-500">
              {pairCountdown > 0
                ? `หมดอายุใน ${Math.floor(pairCountdown / 60)}:${String(
                    pairCountdown % 60,
                  ).padStart(2, '0')} นาที`
                : 'QR หมดอายุแล้ว กรุณาสร้างใหม่'}
            </div>
            <Button
              variant="secondary"
              onClick={handleCreatePairToken}
              loading={pairLoading}
            >
              สร้าง QR ใหม่
            </Button>
          </div>
        ) : (
          <Spinner label="กำลังสร้าง QR…" />
        )}
      </Modal>

      {/* Rename modal */}
      <Modal
        open={!!renameDevice}
        onClose={() => setRenameDevice(null)}
        title="เปลี่ยนชื่ออุปกรณ์"
        footer={
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setRenameDevice(null)}
              disabled={renameLoading}
            >
              ยกเลิก
            </Button>
            <Button
              size="sm"
              onClick={(e) =>
                handleRename(e as unknown as React.FormEvent)
              }
              loading={renameLoading}
            >
              บันทึก
            </Button>
          </div>
        }
      >
        <form onSubmit={handleRename}>
          <TextField
            label="ชื่อใหม่"
            value={renameValue}
            onChange={(e) => {
              setRenameValue(e.target.value);
              setRenameErr(null);
            }}
            error={renameErr}
            maxLength={50}
            hint="สูงสุด 50 ตัวอักษร"
            autoFocus
          />
        </form>
      </Modal>

      {/* Delete confirm */}
      <Confirm
        open={!!deleteTarget}
        title="ยืนยันยกเลิกการเชื่อม"
        message={`อุปกรณ์ "${
          deleteTarget?.name || `#${deleteTarget?.id}`
        }" จะไม่สามารถรับคำสั่งได้อีก ต้องการดำเนินการต่อหรือไม่?`}
        confirmLabel="ยกเลิกการเชื่อม"
        danger
        loading={deleteLoading}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
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
