import { apiFetch } from './client';
import type { Device, PairToken, Paginated } from '../types/api';

export function listDevices(limit = 50, offset = 0): Promise<Paginated<Device>> {
  return apiFetch<Paginated<Device>>(`/devices?limit=${limit}&offset=${offset}`);
}

export function updateDevice(id: number, name: string): Promise<Device> {
  return apiFetch<Device>(`/devices/${id}`, {
    method: 'PATCH',
    body: { name },
  });
}

export function deleteDevice(id: number): Promise<void> {
  return apiFetch<void>(`/devices/${id}`, { method: 'DELETE' });
}

export function createPairToken(): Promise<PairToken> {
  return apiFetch<PairToken>('/pair/token', { method: 'POST' });
}
