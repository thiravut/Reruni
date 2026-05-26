import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { Modal } from '../components/Modal';
import { Confirm } from '../components/Confirm';
import { Spinner } from '../components/Spinner';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { BannerEditor } from '../components/BannerEditor';
import {
  createBanner,
  deleteBanner,
  listBanners,
  updateBanner,
} from '../api/banners';
import { ApiError } from '../api/client';
import { useToast } from '../contexts/ToastContext';
import type { Banner, BannerInput } from '../types/api';

export function VideoBannersPage() {
  const { id } = useParams<{ id: string }>();
  const videoId = Number(id);
  const toast = useToast();

  const [banners, setBanners] = useState<Banner[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Banner | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Banner | null>(null);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    async function load() {
      try {
        const res = await listBanners({ videoId });
        setBanners(res.items);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'โหลดแบนเนอร์ไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    if (!Number.isNaN(videoId)) load();
  }, [videoId]);

  async function handleCreate(input: BannerInput) {
    setSaving(true);
    try {
      const banner = await createBanner({ ...input, video_id: videoId });
      setBanners((prev) => [...prev, banner]);
      toast.success('สร้างแบนเนอร์เรียบร้อย');
      setCreateOpen(false);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'สร้างแบนเนอร์ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handleEdit(input: BannerInput) {
    if (!editTarget) return;
    setSaving(true);
    try {
      const updated = await updateBanner(editTarget.id, input);
      setBanners((prev) => prev.map((b) => (b.id === updated.id ? updated : b)));
      toast.success('อัปเดตแบนเนอร์เรียบร้อย');
      setEditTarget(null);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'อัปเดตไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteBanner(deleteTarget.id);
      setBanners((prev) => prev.filter((b) => b.id !== deleteTarget.id));
      toast.success('ลบแบนเนอร์เรียบร้อย');
      setDeleteTarget(null);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'ลบไม่สำเร็จ');
    } finally {
      setDeleting(false);
    }
  }

  if (Number.isNaN(videoId)) {
    return (
      <div>
        <PageHeader title="แบนเนอร์" />
        <ErrorBanner message="วิดีโอไม่ถูกต้อง" />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title={`แบนเนอร์ของวิดีโอ #${videoId}`}
        description="แบนเนอร์จะแสดงตลอดการ broadcast วิดีโอนี้"
        actions={
          <>
            <Link to="/videos">
              <Button variant="secondary">← กลับ</Button>
            </Link>
            <Button onClick={() => setCreateOpen(true)}>+ เพิ่มแบนเนอร์</Button>
          </>
        }
      />

      <ErrorBanner message={error} onDismiss={() => setError(null)} />

      {loading ? (
        <Spinner label="กำลังโหลด…" />
      ) : banners.length === 0 ? (
        <EmptyState
          title="ยังไม่มีแบนเนอร์"
          description="เพิ่มแบนเนอร์เพื่อให้แสดงตลอดการ broadcast วิดีโอนี้"
          action={
            <Button onClick={() => setCreateOpen(true)}>+ เพิ่มแบนเนอร์</Button>
          }
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {banners.map((b) => (
            <Card key={b.id} className="p-4 flex flex-col gap-3">
              <div
                className="rounded px-4 py-2 text-center font-semibold"
                style={{ backgroundColor: b.bg_color, color: b.text_color }}
              >
                {b.text}
              </div>
              <dl className="text-xs text-slate-500 flex justify-between">
                <span>ตำแหน่ง: {b.slot}</span>
                <span>ขนาด: {b.font_size}</span>
              </dl>
              <div className="flex gap-2 justify-end">
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => setEditTarget(b)}
                >
                  แก้ไข
                </Button>
                <Button
                  size="sm"
                  variant="danger"
                  onClick={() => setDeleteTarget(b)}
                >
                  ลบ
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="เพิ่มแบนเนอร์ใหม่"
        size="lg"
      >
        <BannerEditor
          videoId={videoId}
          saving={saving}
          onSubmit={handleCreate}
          onCancel={() => setCreateOpen(false)}
        />
      </Modal>

      <Modal
        open={!!editTarget}
        onClose={() => setEditTarget(null)}
        title="แก้ไขแบนเนอร์"
        size="lg"
      >
        {editTarget && (
          <BannerEditor
            videoId={videoId}
            initial={editTarget}
            saving={saving}
            onSubmit={handleEdit}
            onCancel={() => setEditTarget(null)}
          />
        )}
      </Modal>

      <Confirm
        open={!!deleteTarget}
        title="ยืนยันลบแบนเนอร์"
        message={`ลบแบนเนอร์ "${deleteTarget?.text}" หรือไม่?`}
        confirmLabel="ลบ"
        danger
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
