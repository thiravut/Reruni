import { apiFetch, ApiError } from './client';
import type { Paginated, Video } from '../types/api';

export function listVideos(limit = 50, offset = 0): Promise<Paginated<Video>> {
  return apiFetch<Paginated<Video>>(`/videos?limit=${limit}&offset=${offset}`);
}

export function uploadVideo(file: File, name?: string): Promise<Video> {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  return apiFetch<Video>('/videos', {
    method: 'POST',
    body: form,
    isFormData: true,
  });
}

export interface UploadHandle {
  promise: Promise<Video>;
  abort: () => void;
}

// XMLHttpRequest-based upload that surfaces real progress (fetch can't observe
// upload bytes). Returns both the promise and an abort handle so the operator
// can cancel a 500 MB upload they started by mistake.
export function uploadVideoWithProgress(
  file: File,
  onProgress: (pct: number) => void,
  name?: string,
): UploadHandle {
  const xhr = new XMLHttpRequest();
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);

  const promise = new Promise<Video>((resolve, reject) => {
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onload = () => {
      const text = xhr.responseText;
      let body: { error?: { code?: string; message?: string } } | Video | null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch {
        body = null;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as Video);
      } else {
        const err = (body as { error?: { code?: string; message?: string } })
          ?.error;
        reject(
          new ApiError(
            err?.code ?? 'UNKNOWN',
            err?.message ?? `อัปโหลดไม่สำเร็จ (${xhr.status})`,
            xhr.status,
          ),
        );
      }
    };
    xhr.onerror = () =>
      reject(new ApiError('NETWORK_ERROR', 'ไม่สามารถเชื่อมต่อเซิร์ฟเวอร์ได้', 0));
    xhr.onabort = () =>
      reject(new ApiError('UPLOAD_ABORTED', 'ยกเลิกการอัปโหลด', 0));

    xhr.open(
      'POST',
      `${import.meta.env.VITE_API_BASE_URL ?? ''}/api/videos`,
    );
    xhr.withCredentials = true;
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.send(form);
  });

  return { promise, abort: () => xhr.abort() };
}

export function renameVideo(id: number, name: string): Promise<Video> {
  return apiFetch<Video>(`/videos/${id}`, {
    method: 'PATCH',
    body: { name },
  });
}

export function deleteVideo(id: number): Promise<void> {
  return apiFetch<void>(`/videos/${id}`, { method: 'DELETE' });
}
