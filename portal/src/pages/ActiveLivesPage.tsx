import { useCallback, useEffect, useMemo, useState } from 'react';
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
    return lives.map((l) => ({
      live: l,
      device: devices.find((d) => d.id === l.device_id),
      video: videos.find((v) => v.id === l.video_id),
    }));
  }, [lives, devices, videos]);

  async function doAction(
    liveId: number,
    fn: () => Promise<unknown>,
    successMsg: string,
  ) {
    setBusy((b) => ({ ...b, [liveId]: true }));
    try {
      await fn();
      toast.success(successMsg);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'คำสั่งล้มเหลว');
    } finally {
      setBusy((b) => ({ ...b, [liveId]: false }));
    }
  }

  async function handleSwitch() {
    if (!switchTarget || pickedVideoId === '') return;
    await doAction(
      switchTarget.id,
      () => switchVideo(switchTarget.device_id, Number(pickedVideoId)),
      'ส่งคำสั่งสลับวิดีโอแล้ว',
    );
    setSwitchTarget(null);
    setPickedVideoId('');
  }

  async function handlePin() {
    if (!pinTarget || !pinSku.trim()) return;
    await doAction(
      pinTarget.id,
      () => pinProduct(pinTarget.device_id, pinSku.trim()),
      `ส่งคำสั่งปัก SKU ${pinSku.trim()} แล้ว`,
    );
    setPinTarget(null);
    setPinSku('');
  }

  async function handleUnpin(live: LiveSession) {
    await doAction(
      live.id,
      () => unpinProduct(live.device_id),
      'ส่งคำสั่งถอด SKU แล้ว',
    );
  }

  async function handleStop() {
    if (!stopTarget) return;
    await doAction(
      stopTarget.id,
      () => stopLive(stopTarget.device_id),
      'ส่งคำสั่งหยุด live แล้ว',
    );
    setStopTarget(null);
  }

  async function handleRestart(live: LiveSession) {
    await doAction(
      live.id,
      () => restartLive(live.device_id),
      'ส่งคำสั่งรีสตาร์ทแล้ว',
    );
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
