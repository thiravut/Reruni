import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Spinner } from '../components/Spinner';
import { EmptyState } from '../components/EmptyState';
import { Confirm } from '../components/Confirm';
import { ErrorBanner } from '../components/ErrorBanner';
import { ApiError } from '../api/client';
import { useToast } from '../contexts/ToastContext';
import { concatVideos, deleteVideo, listVideos, renameVideo, uploadVideo } from '../api/videos';
import { Modal } from '../components/Modal';
import { TextField } from '../components/Field';
import type { Video } from '../types/api';
import { formatBytes, formatDateTime, formatDuration } from '../utils/format';
import { validateVideoFile } from '../utils/validation';

export function VideosPage() {
  const toast = useToast();
  const [videos, setVideos] = useState<Video[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0); // 0..100
  const [deleteTarget, setDeleteTarget] = useState<Video | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [renameTarget, setRenameTarget] = useState<Video | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renameErr, setRenameErr] = useState<string | null>(null);
  const [renameLoading, setRenameLoading] = useState(false);
  // Ordered — defines the concat sequence on submit.
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [concatOpen, setConcatOpen] = useState(false);
  const [concatName, setConcatName] = useState('');
  const [concatLoading, setConcatLoading] = useState(false);
  const [concatErr, setConcatErr] = useState<string | null>(null);

  const fileRef = useRef<HTMLInputElement>(null);

  async function reload() {
    setError(null);
    try {
      const res = await listVideos(200, 0);
      setVideos(res.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'โหลดวิดีโอไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    reload();
  }, []);

  function handlePick() {
    fileRef.current?.click();
  }

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    const err = validateVideoFile(file);
    if (err) {
      toast.error(err);
      return;
    }

    setUploading(true);
    setUploadProgress(0);
    // simulate progress while waiting since fetch lacks upload progress
    const progressTimer = window.setInterval(() => {
      setUploadProgress((p) => Math.min(95, p + 5));
    }, 400);

    try {
      const v = await uploadVideo(file);
      setUploadProgress(100);
      setVideos((prev) => [v, ...prev]);
      toast.success('อัปโหลดวิดีโอสำเร็จ');
    } catch (err2) {
      toast.error(
        err2 instanceof ApiError ? err2.message : 'อัปโหลดไม่สำเร็จ',
      );
    } finally {
      window.clearInterval(progressTimer);
      setTimeout(() => {
        setUploading(false);
        setUploadProgress(0);
      }, 500);
    }
  }

  function toggleSelect(id: number) {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  async function handleConcat(e: React.FormEvent) {
    e.preventDefault();
    setConcatErr(null);
    if (selectedIds.length < 2) {
      setConcatErr('เลือกวิดีโออย่างน้อย 2 รายการ');
      return;
    }
    setConcatLoading(true);
    try {
      const v = await concatVideos(selectedIds, concatName.trim() || undefined);
      setVideos((prev) => [v, ...prev]);
      toast.success('รวมวิดีโอเรียบร้อย');
      setConcatOpen(false);
      setConcatName('');
      setSelectedIds([]);
    } catch (e2) {
      setConcatErr(e2 instanceof ApiError ? e2.message : 'รวมไม่สำเร็จ');
    } finally {
      setConcatLoading(false);
    }
  }

  async function handleRename(e: React.FormEvent) {
    e.preventDefault();
    if (!renameTarget) return;
    const name = renameValue.trim();
    if (name.length === 0 || name.length > 100) {
      setRenameErr('ชื่อต้อง 1-100 ตัวอักษร');
      return;
    }
    setRenameLoading(true);
    setRenameErr(null);
    try {
      const updated = await renameVideo(renameTarget.id, name);
      setVideos((prev) => prev.map((v) => (v.id === updated.id ? updated : v)));
      toast.success('เปลี่ยนชื่อวิดีโอเรียบร้อย');
      setRenameTarget(null);
    } catch (e2) {
      toast.error(e2 instanceof ApiError ? e2.message : 'เปลี่ยนชื่อไม่สำเร็จ');
    } finally {
      setRenameLoading(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleteLoading(true);
    try {
      await deleteVideo(deleteTarget.id);
      setVideos((prev) => prev.filter((v) => v.id !== deleteTarget.id));
      toast.success('ลบวิดีโอเรียบร้อย');
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
        title="คลังวิดีโอ"
        description="อัปโหลดวิดีโอที่จะใช้ broadcast ลง TikTok Live"
        actions={
          <>
            <input
              ref={fileRef}
              type="file"
              accept="video/mp4,video/quicktime,.mp4,.mov"
              className="hidden"
              onChange={handleFileChange}
            />
            <Button onClick={handlePick} loading={uploading}>
              + อัปโหลดวิดีโอ
            </Button>
          </>
        }
      />

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {uploading && (
        <div className="mb-4 bg-white rounded border border-slate-200 p-3">
          <div className="flex justify-between text-sm text-slate-600 mb-1">
            <span>กำลังอัปโหลด…</span>
            <span>{uploadProgress}%</span>
          </div>
          <div className="h-2 bg-slate-100 rounded overflow-hidden">
            <div
              className="h-full bg-brand-600 transition-all"
              style={{ width: `${uploadProgress}%` }}
            />
          </div>
        </div>
      )}

      {loading ? (
        <Spinner label="กำลังโหลด…" />
      ) : videos.length === 0 ? (
        <EmptyState
          title="ยังไม่มีวิดีโอ"
          description="วิดีโอจะ broadcast วนซ้ำระหว่าง live — รองรับ mp4 หรือ mov ไม่เกิน 500 MB และยาวไม่เกิน 60 นาที"
          action={
            <Button onClick={handlePick} loading={uploading}>
              + อัปโหลดวิดีโอ
            </Button>
          }
        />
      ) : (
        <Card>
          {selectedIds.length > 0 && (
            <div className="px-4 py-2 bg-brand-50 border-b border-brand-100 flex items-center gap-3 text-sm">
              <span className="font-medium text-brand-700">
                เลือกแล้ว {selectedIds.length} รายการ
              </span>
              <Button
                size="sm"
                onClick={() => {
                  setConcatName(`รวม ${selectedIds.length} วิดีโอ`);
                  setConcatErr(null);
                  setConcatOpen(true);
                }}
                disabled={selectedIds.length < 2}
              >
                รวมเป็นวิดีโอเดียว
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => setSelectedIds([])}
              >
                ล้างการเลือก
              </Button>
              <span className="text-xs text-slate-500 ml-auto">
                ลำดับที่คลิก = ลำดับการต่อวิดีโอ
              </span>
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-slate-600 text-left">
                <tr>
                  <Th className="w-10"> </Th>
                  <Th>ชื่อวิดีโอ</Th>
                  <Th>ความยาว</Th>
                  <Th>ขนาด</Th>
                  <Th>อัปโหลดเมื่อ</Th>
                  <Th className="text-right">การจัดการ</Th>
                </tr>
              </thead>
              <tbody>
                {videos.map((v) => {
                  const selIdx = selectedIds.indexOf(v.id);
                  return (
                  <tr key={v.id} className="border-t border-slate-100">
                    <Td>
                      <label className="inline-flex items-center gap-1">
                        <input
                          type="checkbox"
                          checked={selIdx !== -1}
                          onChange={() => toggleSelect(v.id)}
                          className="w-4 h-4"
                        />
                        {selIdx !== -1 && (
                          <span className="text-xs font-mono text-brand-600">
                            #{selIdx + 1}
                          </span>
                        )}
                      </label>
                    </Td>
                    <Td>
                      <div className="font-medium text-slate-800 truncate max-w-xs">
                        {v.name || v.filename}
                      </div>
                      <div className="text-xs text-slate-500 truncate max-w-xs">
                        {v.filename}
                      </div>
                    </Td>
                    <Td>{formatDuration(v.duration_sec)}</Td>
                    <Td>{formatBytes(v.size_bytes)}</Td>
                    <Td className="whitespace-nowrap">
                      {formatDateTime(v.uploaded_at)}
                    </Td>
                    <Td className="text-right space-x-1 whitespace-nowrap">
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          setRenameTarget(v);
                          setRenameValue(v.name || v.filename);
                          setRenameErr(null);
                        }}
                      >
                        เปลี่ยนชื่อ
                      </Button>
                      <Link
                        to={`/videos/${v.id}/banners`}
                        className="inline-block"
                      >
                        <Button size="sm" variant="secondary">
                          แบนเนอร์
                        </Button>
                      </Link>
                      <Button
                        size="sm"
                        variant="danger"
                        onClick={() => setDeleteTarget(v)}
                      >
                        ลบ
                      </Button>
                    </Td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Confirm
        open={!!deleteTarget}
        title="ยืนยันลบวิดีโอ"
        message={`ต้องการลบ "${deleteTarget?.name || deleteTarget?.filename}" หรือไม่? หากวิดีโอกำลัง broadcast อยู่จะไม่สามารถลบได้`}
        confirmLabel="ลบ"
        danger
        loading={deleteLoading}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      <Modal
        open={concatOpen}
        title={`รวม ${selectedIds.length} วิดีโอเป็นไฟล์เดียว`}
        onClose={() => concatLoading ? null : setConcatOpen(false)}
      >
        <form onSubmit={handleConcat} className="space-y-4">
          {concatErr && <ErrorBanner message={concatErr} onDismiss={() => setConcatErr(null)} />}
          <div className="text-sm text-slate-600 space-y-1">
            <p>
              Server จะ re-encode วิดีโอที่เลือก (ตามลำดับที่คลิก) เป็นไฟล์เดียว
              ใช้เวลาประมาณ <span className="font-mono">{Math.max(1, Math.round(selectedIds.length * 0.5))}</span> นาทีต่อความยาวรวม 1 นาที
            </p>
            <p className="text-xs text-slate-500">
              เลือกไว้แล้ว: {selectedIds.map((id, i) => {
                const v = videos.find((x) => x.id === id);
                return `#${i + 1} ${v?.name || v?.filename || id}`;
              }).join(', ')}
            </p>
          </div>
          <TextField
            label="ชื่อไฟล์ที่ได้"
            value={concatName}
            onChange={(e) => setConcatName(e.target.value)}
            placeholder={`รวม ${selectedIds.length} วิดีโอ`}
            maxLength={100}
            disabled={concatLoading}
          />
          <div className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">
            ⚠ ทุกวิดีโอที่เลือกต้องมี audio track — ถ้าวิดีโอเงียบ encoder จะ fail
          </div>
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setConcatOpen(false)}
              disabled={concatLoading}
            >
              ยกเลิก
            </Button>
            <Button type="submit" loading={concatLoading}>
              {concatLoading ? 'กำลัง encode...' : 'รวมไฟล์'}
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={!!renameTarget}
        title="เปลี่ยนชื่อวิดีโอ"
        onClose={() => setRenameTarget(null)}
      >
        <form onSubmit={handleRename} className="space-y-4">
          <TextField
            label="ชื่อใหม่"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            placeholder="เช่น live เปิดตัวสินค้า มี.ค."
            autoFocus
            maxLength={100}
            error={renameErr ?? undefined}
          />
          <div className="text-xs text-slate-500">
            ชื่อไฟล์ต้นฉบับ: <span className="font-mono">{renameTarget?.filename}</span>
          </div>
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setRenameTarget(null)}
              disabled={renameLoading}
            >
              ยกเลิก
            </Button>
            <Button type="submit" loading={renameLoading}>
              บันทึก
            </Button>
          </div>
        </form>
      </Modal>
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
