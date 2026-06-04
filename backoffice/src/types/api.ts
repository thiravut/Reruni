// Shared API DTOs — mirrors api-contract.md §2 and §2.9.
// Keep names aligned with portal/src/types if/when that file lands.

export type Role = 'user' | 'admin'

export interface CurrentUser {
  id: number
  email: string
  role: Role
  must_change_password: boolean
}

export interface MeResponse {
  user: CurrentUser
}

export interface AdminUserRow {
  id: number
  email: string
  role: Role
  created_at: string
  device_count: number
  video_count: number
  last_active_at: string | null
}

export interface AdminDeviceRow {
  id: number
  name: string
  owner_email: string
  owner_user_id: number
  status: 'online' | 'offline' | 'live' | 'idle' | string
  last_seen_at: string | null
}

export interface AdminVideoRow {
  id: number
  filename: string
  owner_email: string
  owner_user_id?: number
  size_bytes: number
  uploaded_at: string
  duration_sec?: number | null
}

export interface AdminLiveRow {
  id: number
  device_id: number
  device_name?: string
  owner_user_id: number
  owner_email: string
  video_id: number | null
  title: string | null
  started_at: string
  ended_at: string | null
  end_reason: string | null
  status?: 'active' | 'ended' | string
}

export interface AdminMetrics {
  users_total: number
  users_active_7d: number
  devices_total: number
  devices_online: number
  devices_live: number
  lives_24h: number
  broadcast_hours_24h: number
  disk_used_bytes: number
  uptime_pct_30d: number
}

export interface Paginated<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

// ---- Billing ---------------------------------------------------------------

export type SubscriptionStatus =
  | 'none'
  | 'pending'
  | 'active'
  | 'past_due'
  | 'canceled'
  | 'incomplete'

export type TierKey = 'starter' | 'growth' | 'pro' | 'enterprise'

export interface AdminSubscriptionRow {
  id: number
  user_id: number
  owner_email: string
  tier: TierKey | string
  status: SubscriptionStatus | string
  stripe_subscription_id?: string
  current_period_start?: string
  current_period_end?: string
  cancel_at_period_end: boolean
  created_at: string
  updated_at: string
}

export interface AdminVideosResponse extends Paginated<AdminVideoRow> {
  total_disk_bytes: number
}

export interface ReleaseFile {
  filename: string
  version: string
  size_bytes: number
  mod_time: string
}

export interface ReleaseInfo {
  key: string
  label: string
  prefix: string
  setting_key: string
  active: string
  pinned?: string
  files: ReleaseFile[]
}

export interface ReleasesResponse {
  items: ReleaseInfo[]
}

export interface ResetPasswordResponse {
  temp_password: string
}

export interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
  }
}
