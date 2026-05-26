// Stripe-backed billing API surface — wraps /api/billing/* endpoints
// defined in docs/planning-artifacts/api-contract.md §2.7b.

import { apiFetch } from './client';
import type { Subscription, Tier, TierKey } from '../types/api';

export function getSubscription(): Promise<{ subscription: Subscription | null }> {
  return apiFetch<{ subscription: Subscription | null }>('/billing/subscription');
}

export function getTiers(): Promise<{ tiers: Tier[] }> {
  return apiFetch<{ tiers: Tier[] }>('/billing/tiers');
}

export function createCheckoutSession(
  tier: TierKey,
): Promise<{ checkout_url: string }> {
  return apiFetch<{ checkout_url: string }>('/billing/checkout-session', {
    method: 'POST',
    body: { tier },
  });
}

export function createPortalSession(): Promise<{ portal_url: string }> {
  return apiFetch<{ portal_url: string }>('/billing/portal-session', {
    method: 'POST',
  });
}

export function cancelSubscription(): Promise<{ subscription: Subscription }> {
  return apiFetch<{ subscription: Subscription }>('/billing/cancel', {
    method: 'POST',
  });
}
