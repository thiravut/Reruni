import { apiFetch } from './client';
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

export function deleteVideo(id: number): Promise<void> {
  return apiFetch<void>(`/videos/${id}`, { method: 'DELETE' });
}
