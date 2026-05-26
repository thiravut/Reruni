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
import { deleteVideo, listVideos, uploadVideo } from '../api/videos';
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
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-slate-600 text-left">
                <tr>
                  <Th>ชื่อไฟล์</Th>
                  <Th>ความยาว</Th>
                  <Th>ขนาด</Th>
                  <Th>อัปโหลดเมื่อ</Th>
                  <Th className="text-right">การจัดการ</Th>
                </tr>
              </thead>
              <tbody>
                {videos.map((v) => (
                  <tr key={v.id} className="border-t border-slate-100">
                    <Td>
                      <div className="font-medium text-slate-800 truncate max-w-xs">
                        {v.filename}
                      </div>
                      <div className="text-xs text-slate-500">ID: {v.id}</div>
                    </Td>
                    <Td>{formatDuration(v.duration_sec)}</Td>
                    <Td>{formatBytes(v.size_bytes)}</Td>
                    <Td className="whitespace-nowrap">
                      {formatDateTime(v.uploaded_at)}
                    </Td>
                    <Td className="text-right space-x-1 whitespace-nowrap">
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
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Confirm
        open={!!deleteTarget}
        title="ยืนยันลบวิดีโอ"
        message={`ต้องการลบ "${deleteTarget?.filename}" หรือไม่? หากวิดีโอกำลัง broadcast อยู่จะไม่สามารถลบได้`}
        confirmLabel="ลบ"
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
