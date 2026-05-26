// Auth endpoints — Backoffice talks to /api/admin/auth/* which reads and
// writes the `tkr_admin_session` cookie. This is intentionally distinct from
// Portal's /api/auth/* (which uses `tkr_session`) so an admin can stay logged
// into Backoffice while a Portal session for a different user lives in the
// same browser jar.
//
// The server enforces role='admin' inside POST /api/admin/auth/login and
// returns 403 FORBIDDEN for non-admin accounts — the SPA surfaces that as a
// dedicated message via AdminAuthContext.

import { apiFetch } from './client'
import type { MeResponse, CurrentUser } from '../types/api'

export interface LoginRequest {
  email: string
  password: string
}

export interface ChangePasswordRequest {
  current_password: string
  new_password: string
}

export const authApi = {
  me() {
    return apiFetch<MeResponse>('/admin/auth/me')
  },
  login(req: LoginRequest) {
    return apiFetch<{ user: CurrentUser }>('/admin/auth/login', {
      method: 'POST',
      body: req as unknown as Record<string, unknown>,
    })
  },
  logout() {
    return apiFetch<void>('/admin/auth/logout', { method: 'POST' })
  },
  changePassword(req: ChangePasswordRequest) {
    return apiFetch<{ user: CurrentUser }>('/admin/auth/change-password', {
      method: 'POST',
      body: req as unknown as Record<string, unknown>,
    })
  },
}
