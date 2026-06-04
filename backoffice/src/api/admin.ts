// Typed wrappers for every /api/admin/* endpoint listed in
// api-contract.md §2.9. Components import from here so the URL strings live
// in exactly one place.

import { apiFetch } from './client'
import type {
  AdminDeviceRow,
  AdminLiveRow,
  AdminMetrics,
  AdminSubscriptionRow,
  AdminUserRow,
  AdminVideosResponse,
  Paginated,
  ReleasesResponse,
  ResetPasswordResponse,
  Role,
  SubscriptionStatus,
} from '../types/api'

export interface PageParams {
  limit?: number
  offset?: number
}

export const adminApi = {
  // Users
  listUsers(params: PageParams & { q?: string } = {}) {
    return apiFetch<Paginated<AdminUserRow>>('/admin/users', { query: { ...params } })
  },
  setUserRole(id: number, role: Role) {
    return apiFetch<AdminUserRow>(`/admin/users/${id}/role`, {
      method: 'PATCH',
      body: { role },
    })
  },
  resetUserPassword(id: number) {
    return apiFetch<ResetPasswordResponse>(`/admin/users/${id}/reset-password`, {
      method: 'POST',
    })
  },
  deleteUser(id: number) {
    return apiFetch<void>(`/admin/users/${id}`, { method: 'DELETE' })
  },

  // Devices
  listDevices(params: PageParams & { status?: string } = {}) {
    return apiFetch<Paginated<AdminDeviceRow>>('/admin/devices', { query: { ...params } })
  },
  disconnectDevice(id: number) {
    return apiFetch<void>(`/admin/devices/${id}/disconnect`, { method: 'POST' })
  },

  // Videos
  listVideos(params: PageParams = {}) {
    return apiFetch<AdminVideosResponse>('/admin/videos', { query: { ...params } })
  },

  // Metrics
  getMetrics() {
    return apiFetch<AdminMetrics>('/admin/metrics')
  },

  // Lives
  listLives(params: PageParams & { status?: 'active' | 'ended' } = {}) {
    return apiFetch<Paginated<AdminLiveRow>>('/admin/lives', { query: { ...params } })
  },
  forceStopLive(id: number) {
    return apiFetch<void>(`/admin/lives/${id}/force-stop`, { method: 'POST' })
  },

  // Subscriptions
  listSubscriptions(params: PageParams & { status?: SubscriptionStatus | string } = {}) {
    return apiFetch<Paginated<AdminSubscriptionRow>>('/admin/subscriptions', {
      query: { ...params },
    })
  },
  recheckSubscription(userID: number) {
    return apiFetch<{ subscription: AdminSubscriptionRow; synced: boolean }>(
      `/admin/subscriptions/${userID}/recheck`,
      { method: 'POST' },
    )
  },

  // Releases (setup-guide downloads)
  listReleases() {
    return apiFetch<ReleasesResponse>('/admin/downloads/releases')
  },
  setActiveRelease(key: string, filename: string) {
    return apiFetch<{ ok: boolean }>(`/admin/downloads/releases/${key}`, {
      method: 'POST',
      body: { filename },
    })
  },
}
