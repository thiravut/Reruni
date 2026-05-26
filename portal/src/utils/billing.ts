// Localized labels for subscription tiers + statuses.

import type { SubscriptionStatus, TierKey } from '../types/api';

export function tierLabel(tier: TierKey | string): string {
  switch (tier) {
    case 'starter':
      return 'Starter';
    case 'growth':
      return 'Growth';
    case 'pro':
      return 'Pro';
    default:
      return tier;
  }
}

export function statusLabel(status: SubscriptionStatus | string): string {
  switch (status) {
    case 'none':
      return 'ยังไม่มีแพ็กเกจ';
    case 'pending':
      return 'กำลังรอชำระเงิน';
    case 'active':
      return 'ใช้งานได้';
    case 'past_due':
      return 'ค้างชำระ';
    case 'canceled':
      return 'ยกเลิกแล้ว';
    case 'incomplete':
      return 'ยังไม่สมบูรณ์';
    default:
      return status;
  }
}

export function statusTone(
  status: SubscriptionStatus | string,
): 'green' | 'yellow' | 'red' | 'gray' {
  switch (status) {
    case 'active':
      return 'green';
    case 'pending':
    case 'incomplete':
      return 'yellow';
    case 'past_due':
    case 'canceled':
      return 'red';
    default:
      return 'gray';
  }
}
