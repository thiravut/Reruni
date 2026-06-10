import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Modal } from '../components/Modal';
import { Confirm } from '../components/Confirm';
import { Spinner } from '../components/Spinner';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { TextField, FieldWrapper } from '../components/Field';
import { StatusBadge } from '../components/StatusBadge';
import { BannerEditor } from '../components/BannerEditor';
import { ApiError } from '../api/client';
import { useToast } from '../contexts/ToastContext';
import { useRealtime } from '../contexts/RealtimeContext';
import {
  listActiveLives,
  pinProduct,
  restartLive,
  stopLive,
  switchVideo,
  unpinProduct,
} from '../api/lives';
import { listVideos } from '../api/videos';
import { listDevices } from '../api/devices';
import { createBanner, listBanners } from '../api/banners';
import type {
  Banner,
  BannerInput,
  CommandRef,
  Device,
  LiveSession,
  Video,
} from '../types/api';
import { formatDateTime, relativeFromNow } from '../utils/format';

interface LiveRow {
  live: LiveSession;
  device?: Device;
  video?: Video;
}

type ActivityKind = 'switch' | 'pin' | 'unpin' | 'restart' | 'stop' | 'banner';

interface ActivityEntry {
  id: number;
  at: number;
  kind: ActivityKind;
  label: string;
  status: 'pending' | 'ack' | 'error';
  errorMessage?: string;
}

interface CardError {
  message: string;
  retry?: () => void;
}

const COMMAND_TIMEOUT_MS = 30_000;
const MAX_ACTIVITY_PER_LIVE = 50;

export function ActiveLivesPage() {
  const toast = useToast();
  const realtime = useRealtime();

  const [lives, setLives] = useState<LiveSession[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [videos, setVideos] = useState<Video[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // controls
  const [switchTarget, setSwitchTarget] = useState<LiveSession | null>(null);
  const [pickedVideoId, setPickedVideoId] = useState<number | ''>('');
  const [pinTarget, setPinTarget] = useState<LiveSession | null>(null);
  const [pinSku, setPinSku] = useState('');
  const [stopTarget, setStopTarget] = useState<LiveSession | null>(null);
  const [bannerTarget, setBannerTarget] = useState<LiveSession | null>(null);
  const [bannerSaving, setBannerSaving] = useState(false);
  const [bannerList, setBannerList] = useState<Banner[]>([]);
  const [busy, setBusy] = useState<Record<number, boolean>>({});
  const [cardErrors, setCardErrors] = useState<Record<number, CardError>>({});
  const [activityLog, setActivityLog] = useState<Record<number, ActivityEntry[]>>({});
  const [expandedLog, setExpandedLog] = useState<Record<number, boolean>>({});

  const pendingCommands = useRef<
    Map<
      string,
      {
        liveId: number;
        entryId: number;
        timeoutId: number;
        label: string;
        retry?: () => void;
      }
    >
  >(new Map());
  const activityIdRef = useRef(0);
  // Re-render every 30s so relative timestamps in the activity log refresh.
  const [, setNowTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setNowTick((n) => n + 1), 30_000);
    return () => clearInterval(t);
  }, []);

  const reload = useCallback(async () => {
    setError(null);
    try {
      const [livesRes, devicesRes, videosRes] = await Promise.all([
        listActiveLives(),
        listDevices(200),
        listVideos(200),
      ]);
      setLives(livesRes.items);
      setDevices(devicesRes.items);
      setVideos(videosRes.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    const a = realtime.onLiveStarted(() => reload());
    const b = realtime.onLiveEnded(() => reload());
    return () => {
      a();
      b();
    };
  }, [realtime, reload]);

  const rows: LiveRow[] = useMemo(() => {
    return lives.map((l) => {
      const baseDevice = devices.find((d) => d.id === l.device_id);
      const patch = realtime.deviceStatuses.get(l.device_id);
      const device = baseDevice && patch
        ? { ...baseDevice, status: patch.status, last_seen_at: patch.last_seen_at }
        : baseDevice;
      return {
        live: l,
        device,
        video: videos.find((v) => v.id === l.video_id),
      };
    });
  }, [lives, devices, videos, realtime.deviceStatuses]);

  const updateEntry = useCallback(
    (
      liveId: number,
      entryId: number,
      patch: Partial<Pick<ActivityEntry, 'status' | 'errorMessage'>>,
    ) => {
      setActivityLog((prev) => {
        const arr = prev[liveId];
        if (!arr) return prev;
        return {
          ...prev,
          [liveId]: arr.map((e) => (e.id === entryId ? { ...e, ...patch } : e)),
        };
      });
    },
    [],
  );

  const clearCardError = useCallback((liveId: number) => {
    setCardErrors((prev) => {
      if (!prev[liveId]) return prev;
      const next = { ...prev };
      delete next[liveId];
      return next;
    });
  }, []);

  async function doAction(
    liveId: number,
    kind: ActivityKind,
    label: string,
    fn: () => Promise<CommandRef>,
    retry?: () => void,
  ) {
    clearCardError(liveId);
    setBusy((b) => ({ ...b, [liveId]: true }));

    const entryId = ++activityIdRef.current;
    setActivityLog((prev) => {
      const existing = prev[liveId] ?? [];
      const next: ActivityEntry = {
        id: entryId,
        at: Date.now(),
        kind,
        label,
        status: 'pending',
      };
      return {
        ...prev,
        [liveId]: [next, ...existing].slice(0, MAX_ACTIVITY_PER_LIVE),
      };
    });

    try {
      const ref = await fn();
      const commandId = ref?.command_id;
      if (!commandId) {
        // No command_id surfaced — treat as completed synchronously.
        updateEntry(liveId, entryId, { status: 'ack' });
        toast.success(label);
        setBusy((b) => ({ ...b, [liveId]: false }));
        return;
      }

      const timeoutId = window.setTimeout(() => {
        const pending = pendingCommands.current.get(commandId);
        if (!pending) return;
        pendingCommands.current.delete(commandId);
        const msg = 'ไม่ได้รับการตอบกลับใน 30 วินาที (อุปกรณ์อาจขาดการเชื่อมต่อ)';
        updateEntry(liveId, entryId, {
          status: 'error',
          errorMessage: msg,
        });
        setCardErrors((prev) => ({
          ...prev,
          [liveId]: { message: `${label} — ${msg}`, retry: pending.retry },
        }));
        setBusy((b) => ({ ...b, [liveId]: false }));
      }, COMMAND_TIMEOUT_MS);

      pendingCommands.current.set(commandId, {
        liveId,
        entryId,
        timeoutId,
        label,
        retry,
      });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'คำสั่งล้มเหลว';
      updateEntry(liveId, entryId, { status: 'error', errorMessage: msg });
      setCardErrors((prev) => ({
        ...prev,
        [liveId]: { message: `${label} — ${msg}`, retry },
      }));
      setBusy((b) => ({ ...b, [liveId]: false }));
    }
  }

  // Wire WS command_completed → resolve pending command, update activity entry,
  // surface error on the card if the device rejected the command.
  useEffect(() => {
    return realtime.onCommandCompleted((p) => {
      const pending = pendingCommands.current.get(p.command_id);
      if (!pending) return;
      pendingCommands.current.delete(p.command_id);
      window.clearTimeout(pending.timeoutId);

      if (p.status === 'ack') {
        updateEntry(pending.liveId, pending.entryId, { status: 'ack' });
      } else {
        const msg = p.error?.message ?? 'คำสั่งล้มเหลว';
        updateEntry(pending.liveId, pending.entryId, {
          status: 'error',
          errorMessage: msg,
        });
        setCardErrors((prev) => ({
          ...prev,
          [pending.liveId]: {
            message: `${pending.label} — ${msg}`,
            retry: pending.retry,
          },
        }));
      }
      setBusy((b) => ({ ...b, [pending.liveId]: false }));
    });
  }, [realtime, updateEntry]);

  async function handleSwitch() {
    if (!switchTarget || pickedVideoId === '') return;
    const live = switchTarget;
    const videoId = Number(pickedVideoId);
    const video = videos.find((v) => v.id === videoId);
    const label = `สลับเป็น ${video?.name || video?.filename || `วิดีโอ #${videoId}`}`;
    const fire = () => switchVideo(live.device_id, videoId);
    const retry = () => void doAction(live.id, 'switch', label, fire, retry);
    setSwitchTarget(null);
    setPickedVideoId('');
    await doAction(live.id, 'switch', label, fire, retry);
  }

  async function handlePin() {
    if (!pinTarget || !pinSku.trim()) return;
    const live = pinTarget;
    const sku = pinSku.trim();
    const label = `ปัก SKU ${sku}`;
    const fire = () => pinProduct(live.device_id, sku);
    const retry = () => void doAction(live.id, 'pin', label, fire, retry);
    setPinTarget(null);
    setPinSku('');
    await doAction(live.id, 'pin', label, fire, retry);
  }

  async function handleUnpin(live: LiveSession) {
    const label = 'ถอด SKU';
    const fire = () => unpinProduct(live.device_id);
    const retry = () => void doAction(live.id, 'unpin', label, fire, retry);
    await doAction(live.id, 'unpin', label, fire, retry);
  }

  async function handleStop() {
    if (!stopTarget) return;
    const live = stopTarget;
    const label = 'หยุด Live';
    const fire = () => stopLive(live.device_id);
    const retry = () => void doAction(live.id, 'stop', label, fire, retry);
    setStopTarget(null);
    await doAction(live.id, 'stop', label, fire, retry);
  }

  async function handleRestart(live: LiveSession) {
    const label = 'รีสตาร์ท';
    const fire = () => restartLive(live.device_id);
    const retry = () => void doAction(live.id, 'restart', label, fire, retry);
    await doAction(live.id, 'restart', label, fire, retry);
  }

  async function openBannerEditor(live: LiveSession) {
    setBannerTarget(live);
    setBannerList([]);
    try {
      const res = await listBanners({ liveSessionId: live.id });
      setBannerList(res.items);
    } catch {
      // ignore
    }
  }

  async function handleCreateBanner(input: BannerInput) {
    if (!bannerTarget) return;
    setBannerSaving(true);
    try {
      const banner = await createBanner({
        ...input,
        live_session_id: bannerTarget.id,
      });
      setBannerList((prev) => [...prev, banner]);
      toast.success('สร้างแบนเนอร์เรียบร้อย');
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'สร้างแบนเนอร์ไม่สำเร็จ');
    } finally {
      setBannerSaving(false);
    }
  }

  if (loading) return <Spinner label="กำลังโหลด…" />;

  return (
    <div>
      <PageHeader
        title="Live ที่กำลังออน"
        description="ควบคุมระหว่าง broadcast แบบเรียลไทม์"
      />

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {rows.length === 0 ? (
        <EmptyState
          title="ยังไม่มี live ที่กำลังออน"
          description="ไปที่หน้า “เริ่ม Live” เพื่อเริ่ม broadcast"
          action={
            <Link to="/live">
              <Button>ไปหน้าเริ่ม Live</Button>
            </Link>
          }
        />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {rows.map(({ live, device, video }) => {
            const isBusy = !!busy[live.id];
            const cardErr = cardErrors[live.id];
            const log = activityLog[live.id] ?? [];
            const isExpanded = !!expandedLog[live.id];
            const visibleLog = isExpanded ? log : log.slice(0, 3);
            return (
              <Card key={live.id} className="p-5">
                <div className="flex items-start justify-between gap-3 mb-3">
                  <div>
                    <h3 className="font-semibold text-slate-800">
                      {device?.name || `อุปกรณ์ #${live.device_id}`}
                    </h3>
                    <p className="text-xs text-slate-500">
                      เริ่ม {formatDateTime(live.started_at)} (
                      {relativeFromNow(live.started_at)})
                    </p>
                  </div>
                  {device && <StatusBadge status={device.status} />}
                </div>

                {device?.status === 'offline' && (
                  <div className="mb-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800 flex items-center gap-2">
                    <span aria-hidden>⚠</span>
                    <span>
                      อุปกรณ์ขาดการเชื่อมต่อ
                      {device.last_seen_at
                        ? ` (${relativeFromNow(device.last_seen_at)})`
                        : ''}
                      {' '}— คำสั่งที่ส่งอาจไม่ถึงเครื่อง
                    </span>
                  </div>
                )}

                {cardErr && (
                  <div className="mb-3 rounded border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700 flex items-start gap-2">
                    <span aria-hidden className="mt-0.5">✕</span>
                    <div className="flex-1">
                      <div>{cardErr.message}</div>
                      <div className="mt-1 flex gap-2 text-xs">
                        {cardErr.retry && (
                          <button
                            type="button"
                            onClick={cardErr.retry}
                            className="text-rose-700 underline hover:no-underline"
                          >
                            ลองอีกครั้ง
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => clearCardError(live.id)}
                          className="text-rose-500 hover:text-rose-700"
                        >
                          ปิด
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                <dl className="text-sm text-slate-600 space-y-1 mb-4">
                  <Row label="ชื่อ Live">{live.title ?? '—'}</Row>
                  <Row label="วิดีโอ">
                    {video?.filename ?? `#${live.video_id ?? '—'}`}
                  </Row>
                  <Row label="SKU ที่ปัก">
                    {live.pinned_sku ? (
                      <span className="font-mono text-xs">{live.pinned_sku}</span>
                    ) : (
                      <span className="text-slate-400">ไม่ได้ปัก</span>
                    )}
                  </Row>
                </dl>

                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => {
                      setSwitchTarget(live);
                      setPickedVideoId(live.video_id ?? '');
                    }}
                    disabled={isBusy}
                  >
                    สลับวิดีโอ
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => {
                      setPinTarget(live);
                      setPinSku(live.pinned_sku ?? '');
                    }}
                    disabled={isBusy}
                  >
                    ปัก SKU
                  </Button>
                  {live.pinned_sku && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => handleUnpin(live)}
                      disabled={isBusy}
                    >
                      ถอด SKU
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => openBannerEditor(live)}
                    disabled={isBusy}
                  >
                    แบนเนอร์
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => handleRestart(live)}
                    disabled={isBusy}
                  >
                    รีสตาร์ท
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    onClick={() => setStopTarget(live)}
                    disabled={isBusy}
                  >
                    หยุด Live
                  </Button>
                </div>

                {log.length > 0 && (
                  <div className="mt-4 border-t border-slate-100 pt-3">
                    <button
                      type="button"
                      onClick={() =>
                        setExpandedLog((prev) => ({
                          ...prev,
                          [live.id]: !prev[live.id],
                        }))
                      }
                      className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-700"
                      aria-expanded={isExpanded}
                    >
                      <span aria-hidden>{isExpanded ? '▾' : '▸'}</span>
                      กิจกรรมล่าสุด ({log.length})
                    </button>
                    <ul className="mt-2 space-y-1 text-xs">
                      {visibleLog.map((entry) => (
                        <ActivityRow key={entry.id} entry={entry} />
                      ))}
                      {!isExpanded && log.length > 3 && (
                        <li className="text-slate-400 pl-3">
                          + {log.length - 3} รายการอื่น ๆ
                        </li>
                      )}
                    </ul>
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}

      {/* Switch video modal */}
      <Modal
        open={!!switchTarget}
        onClose={() => setSwitchTarget(null)}
        title="สลับวิดีโอ"
        footer={
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setSwitchTarget(null)}
            >
              ยกเลิก
            </Button>
            <Button
              size="sm"
              onClick={handleSwitch}
              disabled={pickedVideoId === ''}
            >
              ส่งคำสั่ง
            </Button>
          </div>
        }
      >
        <FieldWrapper label="เลือกวิดีโอใหม่">
          <select
            value={pickedVideoId}
            onChange={(e) =>
              setPickedVideoId(e.target.value ? Number(e.target.value) : '')
            }
            className="rounded border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            <option value="">— เลือก —</option>
            {videos.map((v) => (
              <option key={v.id} value={v.id}>
                {v.filename}
              </option>
            ))}
          </select>
        </FieldWrapper>
      </Modal>

      {/* Pin SKU modal */}
      <Modal
        open={!!pinTarget}
        onClose={() => setPinTarget(null)}
        title="ปัก SKU"
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setPinTarget(null)}>
              ยกเลิก
            </Button>
            <Button size="sm" onClick={handlePin} disabled={!pinSku.trim()}>
              ปัก
            </Button>
          </div>
        }
      >
        <TextField
          label="SKU"
          value={pinSku}
          onChange={(e) => setPinSku(e.target.value)}
          placeholder="SKU-2024"
          autoFocus
        />
      </Modal>

      {/* Stop confirm */}
      <Confirm
        open={!!stopTarget}
        title="ยืนยันหยุด Live"
        message="ระบบจะส่งคำสั่งหยุด live ทันที ผู้ชมจะเห็น live จบทันที"
        confirmLabel="หยุด Live"
        danger
        onConfirm={handleStop}
        onCancel={() => setStopTarget(null)}
      />

      {/* Banner editor */}
      <Modal
        open={!!bannerTarget}
        onClose={() => setBannerTarget(null)}
        title="เพิ่มแบนเนอร์ระหว่าง Live"
        size="lg"
      >
        {bannerTarget && (
          <div className="space-y-5">
            {bannerList.length > 0 && (
              <div>
                <h3 className="text-sm font-semibold text-slate-700 mb-2">
                  แบนเนอร์ปัจจุบัน
                </h3>
                <ul className="space-y-1">
                  {bannerList.map((b) => (
                    <li
                      key={b.id}
                      className="flex items-center justify-between text-sm border border-slate-200 rounded px-3 py-2"
                    >
                      <span>
                        <span
                          className="inline-block px-2 py-0.5 rounded text-xs mr-2"
                          style={{
                            backgroundColor: b.bg_color,
                            color: b.text_color,
                          }}
                        >
                          {b.slot}
                        </span>
                        {b.text}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <BannerEditor
              liveSessionId={bannerTarget.id}
              saving={bannerSaving}
              onSubmit={handleCreateBanner}
              onCancel={() => setBannerTarget(null)}
            />
          </div>
        )}
      </Modal>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-2">
      <dt className="text-slate-500 w-24 flex-shrink-0">{label}</dt>
      <dd className="flex-1">{children}</dd>
    </div>
  );
}

function ActivityRow({ entry }: { entry: ActivityEntry }) {
  const colors =
    entry.status === 'error'
      ? 'text-rose-600'
      : entry.status === 'pending'
        ? 'text-slate-500'
        : 'text-slate-700';
  const marker =
    entry.status === 'error' ? '✕' : entry.status === 'pending' ? '○' : '●';
  return (
    <li className={`flex items-start gap-2 ${colors}`}>
      <span aria-hidden className="w-3 text-center">
        {marker}
      </span>
      <span className="flex-1">
        <span>{entry.label}</span>
        {entry.status === 'pending' && (
          <span className="ml-1 text-slate-400">(รอ ack)</span>
        )}
        {entry.status === 'error' && entry.errorMessage && (
          <span className="ml-1 text-rose-500">— {entry.errorMessage}</span>
        )}
      </span>
      <span className="text-slate-400 text-[10px] whitespace-nowrap">
        {relativeFromNow(new Date(entry.at).toISOString())}
      </span>
    </li>
  );
}
