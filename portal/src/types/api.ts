// Auto-curated from docs/planning-artifacts/api-contract.md v1
// All datetimes are ISO 8601 UTC strings.

export type Role = 'user' | 'admin';

export type SubscriptionStatus =
  | 'none'
  | 'pending'
  | 'active'
  | 'past_due'
  | 'canceled'
  | 'incomplete';

// Flat per-device pricing (2026-05-23): single tier key 'device'.
// Legacy keys kept in the union so old admin pages don't break.
export type TierKey = 'device' | 'starter' | 'growth' | 'pro';

export type OnboardingStep =
  | 'welcome'
  | 'pick_quantity'
  | 'payment'
  | 'install_apk'
  | 'pair_device'
  | 'first_video'
  | 'complete';

export interface User {
  id: number;
  email: string;
  role: Role;
  must_change_password: boolean;
  created_at: string;
  /** `none` when no subscription row exists. */
  subscription_status?: SubscriptionStatus;
  subscription_tier?: TierKey | '';
  /** Wizard step (PRD §3.12). Absent on legacy users; treat as 'complete'. */
  onboarding_step?: OnboardingStep;
  /** Device quota under flat per-device pricing. */
  device_quota?: number;
  /** Currently paired device count (for the quota badge). */
  devices_paired?: number;
}

export interface Subscription {
  tier: TierKey;
  status: SubscriptionStatus;
  device_quota?: number;
  stripe_subscription_id?: string;
  current_period_start?: string;
  current_period_end?: string;
  cancel_at_period_end: boolean;
}

export interface Tier {
  key: TierKey;
  name: string;
  devices: number;
  price_thb: number;
  stripe_price_id: string;
}

export type InvoiceStatus =
  | 'draft'
  | 'open'
  | 'paid'
  | 'uncollectible'
  | 'void';

export interface Invoice {
  id: string;
  number?: string;
  status: InvoiceStatus | string;
  /** Smallest currency unit (THB → satang). Divide by 100 for THB display. */
  amount_paid: number;
  amount_due: number;
  currency: string;
  /** Unix seconds. */
  created: number;
  paid_at?: number;
  hosted_url?: string;
  pdf?: string;
  period: { start: number; end: number };
}

export interface AuthResponse {
  user: User;
  expires_at: string;
}

export type DeviceStatus = 'idle' | 'pairing' | 'live' | 'error' | 'offline';

export interface Device {
  id: number;
  name: string | null;
  status: DeviceStatus;
  current_video_id?: number | null;
  current_pinned_sku?: string | null;
  last_seen_at: string | null;
  caps?: DeviceCaps | null;
  caps_reported_at?: string | null;
}

export interface DeviceCaps {
  app_version?: string;
  android_sdk?: number;
  device_model?: string;
  sku_tier?: string;
  overlay_permission?: boolean;
  notification_permission?: boolean;
  battery_unrestricted?: boolean;
  accessibility_enabled?: boolean;
  tiktok_installed?: boolean;
  [k: string]: unknown;
}

export interface Video {
  id: number;
  name: string;
  filename: string;
  duration_sec: number | null;
  size_bytes: number | null;
  uploaded_at: string;
  url?: string;
}

export interface PairToken {
  token: string;
  expires_at: string;
  qr_url: string;
}

export interface LiveSession {
  id: number;
  device_id: number;
  video_id: number | null;
  title: string | null;
  caption?: string | null;
  hashtags?: string[];
  pinned_sku: string | null;
  started_at: string;
  ended_at?: string | null;
  end_reason?: 'user_stop' | 'error' | 'tiktok_end' | null;
}

export type BannerSlot =
  | 'top'
  | 'bottom'
  | 'top-left'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-right'
  | 'center';

export type BannerFontSize = 'S' | 'M' | 'L';

export interface Banner {
  id: number;
  video_id: number | null;
  live_session_id: number | null;
  slot: BannerSlot;
  text: string;
  bg_color: string;
  text_color: string;
  font_size: BannerFontSize;
  deadline: string | null;
}

export interface BannerInput {
  video_id?: number;
  live_session_id?: number;
  slot: BannerSlot;
  text: string;
  bg_color: string;
  text_color: string;
  font_size: BannerFontSize;
  deadline?: string | null;
}

export interface CommandRef {
  command_id: string;
  device_id?: number;
}

export interface StartLiveRequest {
  device_ids: number[];
  // Preferred — list of video IDs. Server assigns round-robin per device.
  video_ids?: number[];
  // Legacy — single video; ignored if video_ids is set.
  video_id?: number;
  // Playback repetition. loop_forever wins if both are sent.
  loop_count?: number;
  loop_forever?: boolean;
  title: string;
  caption?: string;
  hashtags?: string[];
  pinned_sku?: string;
}

export interface StartLiveResponse {
  commands: CommandRef[];
}

export interface CommandStatus {
  id: string;
  type: string;
  device_id: number;
  issued_at: string;
  ack_at: string | null;
  status: 'pending' | 'ack' | 'error';
  error: { code: string; message: string } | null;
}

export interface Paginated<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
}

// ---------------- WebSocket envelope ----------------

export interface WsMessage<T = unknown> {
  id: string;
  type: WsPortalServerType | WsPortalClientType | string;
  payload: T;
  timestamp: string;
}

export type WsPortalServerType =
  | 'device_status_changed'
  | 'live_started'
  | 'live_ended'
  | 'command_completed'
  | 'banner_updated'
  | 'error_notice'
  | 'ping';

export type WsPortalClientType = 'subscribe' | 'pong';

export interface WsDeviceStatusChanged {
  device_id: number;
  status: DeviceStatus;
  last_seen_at: string;
}

export interface WsLiveStarted {
  live_session_id: number;
  device_id: number;
  video_id: number;
}

export interface WsLiveEnded {
  live_session_id: number;
  device_id: number;
  end_reason: string;
}

export interface WsCommandCompleted {
  command_id: string;
  status: 'ack' | 'error';
  error?: { code: string; message: string };
}

export interface WsBannerUpdated {
  banner_id: number;
  live_session_id: number;
}

export interface WsErrorNotice {
  device_id: number;
  code: string;
  message: string;
}
