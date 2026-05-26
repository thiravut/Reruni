import { apiFetch } from './client';
import type {
  CommandRef,
  LiveSession,
  Paginated,
  StartLiveRequest,
  StartLiveResponse,
} from '../types/api';

export function startLive(req: StartLiveRequest): Promise<StartLiveResponse> {
  return apiFetch<StartLiveResponse>('/lives/start', {
    method: 'POST',
    body: req,
  });
}

export function stopLive(deviceId: number): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/stop`, { method: 'POST' });
}

export function switchVideo(
  deviceId: number,
  videoId: number,
): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/switch-video`, {
    method: 'POST',
    body: { video_id: videoId },
  });
}

export function restartLive(deviceId: number): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/restart`, { method: 'POST' });
}

export function setVolume(deviceId: number, volume: number): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/volume`, {
    method: 'PATCH',
    body: { volume },
  });
}

export function pinProduct(deviceId: number, sku: string): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/products/pin`, {
    method: 'POST',
    body: { sku },
  });
}

export function unpinProduct(deviceId: number): Promise<CommandRef> {
  return apiFetch<CommandRef>(`/lives/${deviceId}/products/unpin`, {
    method: 'POST',
  });
}

export function listActiveLives(): Promise<{ items: LiveSession[] }> {
  return apiFetch<{ items: LiveSession[] }>('/lives/active?limit=50');
}

export function listLiveHistory(
  limit = 50,
  offset = 0,
  deviceId?: number,
): Promise<Paginated<LiveSession>> {
  const params = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  if (deviceId !== undefined) params.set('device_id', String(deviceId));
  return apiFetch<Paginated<LiveSession>>(`/lives/history?${params.toString()}`);
}
