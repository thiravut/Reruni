import { apiFetch } from './client';
import type { Banner, BannerInput } from '../types/api';

export function listBanners(opts: {
  videoId?: number;
  liveSessionId?: number;
}): Promise<{ items: Banner[] }> {
  const params = new URLSearchParams();
  if (opts.videoId !== undefined) params.set('video_id', String(opts.videoId));
  if (opts.liveSessionId !== undefined)
    params.set('live_session_id', String(opts.liveSessionId));
  return apiFetch<{ items: Banner[] }>(`/banners?${params.toString()}`);
}

export function createBanner(input: BannerInput): Promise<Banner> {
  return apiFetch<Banner>('/banners', { method: 'POST', body: input });
}

export function updateBanner(
  id: number,
  patch: Partial<BannerInput>,
): Promise<Banner> {
  return apiFetch<Banner>(`/banners/${id}`, { method: 'PATCH', body: patch });
}

export function deleteBanner(id: number): Promise<void> {
  return apiFetch<void>(`/banners/${id}`, { method: 'DELETE' });
}
