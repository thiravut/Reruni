import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Modal } from '../components/Modal';
import { TextField, TextArea, FieldWrapper } from '../components/Field';
import { ErrorBanner } from '../components/ErrorBanner';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { ApiError } from '../api/client';
import { listDevices } from '../api/devices';
import { listVideos } from '../api/videos';
import { startLive } from '../api/lives';
import { useToast } from '../contexts/ToastContext';
import { useRealtime } from '../contexts/RealtimeContext';
import type { Device, Video } from '../types/api';
import {
  validateHashtags,
  validateLiveCaption,
  validateLiveTitle,
} from '../utils/validation';

export function LiveConfigPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const realtime = useRealtime();

  const [devices, setDevices] = useState<Device[]>([]);
  const [videos, setVideos] = useState<Video[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [selectedDeviceIds, setSelectedDeviceIds] = useState<Set<number>>(new Set());
  // Ordered list so the server's round-robin assignment is predictable from
  // the operator's click sequence — first clicked → device 0, etc.
  const [selectedVideoIds, setSelectedVideoIds] = useState<number[]>([]);
  const [loopForever, setLoopForever] = useState(false);
  const [loopCount, setLoopCount] = useState<number>(1);
  const [loopErr, setLoopErr] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [caption, setCaption] = useState('');
  const [hashtagInput, setHashtagInput] = useState('');
  const [pinnedSku, setPinnedSku] = useState('');

  const [titleErr, setTitleErr] = useState<string | null>(null);
  const [captionErr, setCaptionErr] = useState<string | null>(null);
  const [hashtagErr, setHashtagErr] = useState<string | null>(null);
  const [videoErr, setVideoErr] = useState<string | null>(null);
  const [devicesErr, setDevicesErr] = useState<string | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [formErr, setFormErr] = useState<string | null>(null);
  const [showConfirm, setShowConfirm] = useState(false);

  useEffect(() => {
    async function load() {
      try {
        const [d, v] = await Promise.all([listDevices(200), listVideos(200)]);
        setDevices(d.items);
        setVideos(v.items);
      } catch (e) {
        setLoadError(e instanceof Error ? e.message : 'โหลดข้อมูลไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  // realtime status patch
  const mergedDevices = useMemo(() => {
    return devices.map((d) => {
      const patch = realtime.deviceStatuses.get(d.id);
      if (!patch) return d;
      return { ...d, status: patch.status, last_seen_at: patch.last_seen_at };
    });
  }, [devices, realtime.deviceStatuses]);

  const availableDevices = useMemo(
    () => mergedDevices.filter((d) => d.status !== 'offline'),
    [mergedDevices],
  );

  function toggleDevice(id: number) {
    setSelectedDeviceIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setDevicesErr(null);
  }

  function selectAll() {
    setSelectedDeviceIds(new Set(availableDevices.filter((d) => d.status === 'idle').map((d) => d.id)));
  }

  function clearAll() {
    setSelectedDeviceIds(new Set());
  }

  function parseHashtags(input: string): string[] {
    return input
      .split(/[,\s]+/)
      .map((t) => t.trim().replace(/^#/, ''))
      .filter(Boolean);
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormErr(null);

    const tErr = validateLiveTitle(title.trim());
    const cErr = validateLiveCaption(caption);
    const hashtags = parseHashtags(hashtagInput);
    const hErr = validateHashtags(hashtags);
    const vErr = selectedVideoIds.length === 0 ? 'กรุณาเลือกวิดีโออย่างน้อย 1 รายการ' : null;
    const dErr = selectedDeviceIds.size === 0 ? 'กรุณาเลือกอุปกรณ์อย่างน้อย 1 เครื่อง' : null;
    const lErr = !loopForever && (!Number.isInteger(loopCount) || loopCount < 1 || loopCount > 1000)
      ? 'จำนวนรอบต้องเป็น 1–1000'
      : null;

    setTitleErr(tErr);
    setCaptionErr(cErr);
    setHashtagErr(hErr);
    setVideoErr(vErr);
    setDevicesErr(dErr);
    setLoopErr(lErr);
    if (tErr || cErr || hErr || vErr || dErr || lErr) return;

    setShowConfirm(true);
  }

  async function handleConfirmStart() {
    setShowConfirm(false);
    setSubmitting(true);
    try {
      const hashtags = parseHashtags(hashtagInput);
      const res = await startLive({
        device_ids: [...selectedDeviceIds],
        video_ids: selectedVideoIds,
        loop_forever: loopForever || undefined,
        loop_count: loopForever ? undefined : loopCount,
        title: title.trim(),
        caption: caption.trim() || undefined,
        hashtags: hashtags.length ? hashtags : undefined,
        pinned_sku: pinnedSku.trim() || undefined,
      });
      toast.success(
        `ส่งคำสั่งเริ่ม live แล้ว ${res.commands.length} เครื่อง`,
      );
      navigate('/live/active');
    } catch (err) {
      setFormErr(err instanceof ApiError ? err.message : 'เริ่ม live ไม่สำเร็จ');
    } finally {
      setSubmitting(false);
    }
  }

  const selectedDevicesForSummary = useMemo(
    () => mergedDevices.filter((d) => selectedDeviceIds.has(d.id)),
    [mergedDevices, selectedDeviceIds],
  );
  const selectedVideosForSummary = useMemo(
    () =>
      selectedVideoIds
        .map((id) => videos.find((v) => v.id === id))
        .filter((v): v is Video => !!v),
    [selectedVideoIds, videos],
  );

  if (loading) return <Spinner label="กำลังโหลด…" />;

  return (
    <div>
      <PageHeader
        title="เริ่ม Live"
        description="เลือกอุปกรณ์และวิดีโอเพื่อเริ่ม broadcast พร้อมกัน"
      />

      <ErrorBanner message={loadError} onDismiss={() => setLoadError(null)} />

      <form onSubmit={handleSubmit} className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Devices selector */}
        <Card className="lg:col-span-2 p-5">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold text-slate-800">เลือกอุปกรณ์</h2>
            <div className="flex gap-2 text-xs">
              <button
                type="button"
                onClick={selectAll}
                className="text-brand-600 hover:underline"
              >
                เลือกทั้งหมดที่พร้อม
              </button>
              <button
                type="button"
                onClick={clearAll}
                className="text-slate-500 hover:underline"
              >
                ล้างการเลือก
              </button>
            </div>
          </div>
          {devicesErr && (
            <p className="text-xs text-rose-600 mb-2">{devicesErr}</p>
          )}
          {mergedDevices.length === 0 ? (
            <p className="text-sm text-slate-500">
              ยังไม่มีอุปกรณ์ — กรุณาเชื่อม device ก่อน
            </p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {mergedDevices.map((d) => {
                const offline = d.status === 'offline';
                const live = d.status === 'live';
                const checked = selectedDeviceIds.has(d.id);
                return (
                  <li
                    key={d.id}
                    className={`py-2 flex items-center gap-3 ${
                      offline ? 'opacity-60' : ''
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleDevice(d.id)}
                      disabled={offline}
                      className="w-4 h-4"
                    />
                    <div className="flex-1">
                      <div className="font-medium text-slate-800">
                        {d.name || `อุปกรณ์ #${d.id}`}
                      </div>
                      <div className="text-xs text-slate-500">ID: {d.id}</div>
                    </div>
                    <StatusBadge status={d.status} />
                    {live && (
                      <span className="text-xs text-amber-600">
                        กำลัง live อยู่
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          <p className="text-xs text-slate-500 mt-3">
            เลือกแล้ว {selectedDeviceIds.size} เครื่อง
          </p>
        </Card>

        {/* Config form */}
        <Card className="p-5 flex flex-col gap-4">
          <h2 className="font-semibold text-slate-800">ตั้งค่า Live</h2>
          <ErrorBanner message={formErr} onDismiss={() => setFormErr(null)} />

          <FieldWrapper
            label="วิดีโอ broadcast"
            error={videoErr}
            required
            hint="เลือกได้มากกว่า 1 — server จะแจกจ่ายให้แต่ละ device แบบ round-robin"
          >
            {videos.length === 0 ? (
              <p className="text-sm text-slate-500 py-2">
                ยังไม่มีวิดีโอ — กรุณาอัพโหลดที่หน้าคลังวิดีโอก่อน
              </p>
            ) : (
              <ul className="divide-y divide-slate-100 border border-slate-200 rounded max-h-56 overflow-y-auto">
                {videos.map((v) => {
                  const idx = selectedVideoIds.indexOf(v.id);
                  const checked = idx !== -1;
                  return (
                    <li
                      key={v.id}
                      className="py-2 px-3 flex items-center gap-3"
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => {
                          setSelectedVideoIds((prev) =>
                            checked
                              ? prev.filter((id) => id !== v.id)
                              : [...prev, v.id],
                          );
                          setVideoErr(null);
                        }}
                        className="w-4 h-4"
                      />
                      <div className="flex-1 min-w-0">
                        <div className="font-medium text-slate-800 truncate">
                          {v.name || v.filename}
                        </div>
                        <div className="text-xs text-slate-500 truncate">
                          {v.filename}
                        </div>
                      </div>
                      {checked && (
                        <span className="text-xs text-brand-600 font-mono">
                          #{idx + 1}
                        </span>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            <p className="text-xs text-slate-500 mt-2">
              เลือกแล้ว {selectedVideoIds.length} รายการ
            </p>
          </FieldWrapper>

          <FieldWrapper label="การวนลูปวิดีโอ" error={loopErr}>
            <div className="flex items-center gap-3 flex-wrap">
              <label className="inline-flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={loopForever}
                  onChange={(e) => {
                    setLoopForever(e.target.checked);
                    setLoopErr(null);
                  }}
                  className="w-4 h-4"
                />
                วนต่อเนื่องไม่จำกัด
              </label>
              <div className="flex items-center gap-2 text-sm">
                <span className={loopForever ? 'text-slate-400' : 'text-slate-700'}>
                  จำนวนรอบ
                </span>
                <input
                  type="number"
                  min={1}
                  max={1000}
                  value={loopCount}
                  disabled={loopForever}
                  onChange={(e) => {
                    setLoopCount(Number(e.target.value));
                    setLoopErr(null);
                  }}
                  className="w-20 rounded border border-slate-300 px-2 py-1 text-sm disabled:bg-slate-100 disabled:text-slate-400"
                />
                <span className={loopForever ? 'text-slate-400' : 'text-slate-500'}>รอบ</span>
              </div>
            </div>
            <p className="text-xs text-slate-500 mt-2">
              {loopForever
                ? 'วิดีโอจะวนต่อเนื่องจนกว่าจะกดหยุด'
                : selectedVideoIds.length > 1
                  ? `เล่นไล่ตามลำดับวิดีโอที่เลือกครบ ${loopCount} รอบแล้วหยุด`
                  : `เล่น ${loopCount} ครั้งแล้วหยุด`}
            </p>
          </FieldWrapper>

          <TextField
            label="ชื่อ Live"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value);
              setTitleErr(null);
            }}
            maxLength={100}
            error={titleErr}
            hint="แสดงบนสุดของหน้า live"
            required
          />

          <TextArea
            label="คำบรรยาย"
            value={caption}
            onChange={(e) => {
              setCaption(e.target.value);
              setCaptionErr(null);
            }}
            maxLength={500}
            error={captionErr}
            hint={`${caption.length}/500`}
          />

          <TextField
            label="แฮชแท็ก"
            value={hashtagInput}
            onChange={(e) => {
              setHashtagInput(e.target.value);
              setHashtagErr(null);
            }}
            error={hashtagErr}
            placeholder="flashsale, ส่งฟรี"
            hint="คั่นด้วย comma หรือเว้นวรรค ไม่เกิน 10 แท็ก"
          />

          <TextField
            label="SKU ที่ปัก (ตัวเลือก)"
            value={pinnedSku}
            onChange={(e) => setPinnedSku(e.target.value)}
            placeholder="SKU-2024"
          />

          <Button type="submit" loading={submitting} size="lg" block>
            เริ่ม Live ทันที
          </Button>
          <p className="text-xs text-slate-500">
            ระบบจะส่งคำสั่งให้ทุกเครื่องที่เลือกพร้อมกัน
          </p>
        </Card>
      </form>

      <Modal
        open={showConfirm}
        onClose={() => setShowConfirm(false)}
        title="ยืนยันเริ่ม Live"
        size="md"
        footer={
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setShowConfirm(false)}
            >
              แก้กลับ
            </Button>
            <Button size="sm" onClick={handleConfirmStart} loading={submitting}>
              เริ่ม Live →
            </Button>
          </div>
        }
      >
        <div className="space-y-4 text-sm">
          <p className="text-slate-600">
            จะส่งคำสั่งเริ่ม live ทันทีบน:
          </p>

          <div>
            <p className="font-medium text-slate-800 mb-1.5">
              {selectedDevicesForSummary.length} อุปกรณ์
            </p>
            <ul className="text-slate-600 space-y-0.5 pl-3">
              {selectedDevicesForSummary.slice(0, 5).map((d) => (
                <li key={d.id} className="flex items-center gap-2">
                  <span>• {d.name || `อุปกรณ์ #${d.id}`}</span>
                  {d.status === 'live' && (
                    <span className="text-xs text-amber-600">
                      (กำลัง live อยู่)
                    </span>
                  )}
                </li>
              ))}
              {selectedDevicesForSummary.length > 5 && (
                <li className="text-slate-500">
                  + {selectedDevicesForSummary.length - 5} เครื่องอื่น ๆ
                </li>
              )}
            </ul>
          </div>

          <div>
            <p className="font-medium text-slate-800 mb-1.5">
              {selectedVideosForSummary.length} วิดีโอ{' '}
              {selectedVideosForSummary.length > 1 && (
                <span className="text-xs font-normal text-slate-500">
                  (round-robin ตามลำดับนี้)
                </span>
              )}
            </p>
            <ul className="text-slate-600 space-y-0.5 pl-3">
              {selectedVideosForSummary.map((v, i) => (
                <li key={v.id}>
                  <span className="font-mono text-xs text-brand-600 mr-2">
                    #{i + 1}
                  </span>
                  {v.name || v.filename}
                </li>
              ))}
            </ul>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 border-t border-slate-100 pt-3">
            <SummaryRow label="ชื่อ Live">
              <span className="text-slate-800">{title.trim()}</span>
            </SummaryRow>
            <SummaryRow label="วนลูป">
              <span className="text-slate-800">
                {loopForever ? 'ไม่จำกัด (จนกว่าจะหยุด)' : `${loopCount} รอบ`}
              </span>
            </SummaryRow>
            {hashtagInput.trim() && (
              <SummaryRow label="แฮชแท็ก">
                <span className="text-slate-800">
                  {parseHashtags(hashtagInput).map((h) => `#${h}`).join(' ')}
                </span>
              </SummaryRow>
            )}
            {pinnedSku.trim() && (
              <SummaryRow label="SKU ที่ปัก">
                <span className="font-mono text-xs text-slate-800">
                  {pinnedSku.trim()}
                </span>
                <span className="ml-1 text-amber-600" title="ระบบยังไม่ validate SKU">
                  ⚠
                </span>
              </SummaryRow>
            )}
          </div>

          {pinnedSku.trim() && (
            <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1.5">
              ⚠ ตรวจการสะกด SKU ให้แน่ใจ — ระบบยังไม่ validate กับ TikTok Shop
            </p>
          )}
        </div>
      </Modal>
    </div>
  );
}

function SummaryRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wider text-slate-500">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}
