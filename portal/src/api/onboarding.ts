// Onboarding wizard API surface — wraps /api/onboarding/* endpoints.
// See docs/planning-artifacts/prd-v1-launch.md §3.12.

import { apiFetch } from './client';

export type OnboardingStep =
  | 'welcome'
  | 'pick_quantity'
  | 'payment'
  | 'install_apk'
  | 'pair_device'
  | 'first_video'
  | 'complete';

export interface OnboardingState {
  step: OnboardingStep;
  skippable: boolean;
  subscription_status: 'none' | 'pending' | 'active' | 'past_due' | 'canceled';
  device_quota: number;
  devices_paired: number;
  has_first_video: boolean;
}

export function getOnboarding(): Promise<OnboardingState> {
  return apiFetch<OnboardingState>('/onboarding');
}

export function advanceOnboarding(to: OnboardingStep): Promise<{ step: OnboardingStep }> {
  return apiFetch<{ step: OnboardingStep }>('/onboarding/advance', {
    method: 'POST',
    body: { to },
  });
}

export function skipOnboarding(): Promise<{ step: OnboardingStep }> {
  return apiFetch<{ step: OnboardingStep }>('/onboarding/skip', {
    method: 'POST',
  });
}

// Token-gated APK download URL — caller appends auth cookies automatically
// because it's same-origin.
export const COMPANION_APK_URL = '/api/downloads/companion-apk';
