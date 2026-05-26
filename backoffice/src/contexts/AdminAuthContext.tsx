// AdminAuthContext — talks to the backoffice-scoped /api/admin/auth/* surface
// which reads/writes `tkr_admin_session`. The server gates issuance on
// role='admin' (returning 403 for everyone else), so the client no longer
// needs to inspect the user payload after login — a 403 is the canonical
// signal that the account exists but lacks backoffice access.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { authApi } from '../api/auth'
import { ApiError } from '../api/client'
import type { CurrentUser } from '../types/api'

interface AdminAuthState {
  user: CurrentUser | null
  loading: boolean
  /** Set when the server returned a real user but role !== 'admin'. */
  forbidden: boolean
  error: string | null
}

interface AdminAuthContextValue extends AdminAuthState {
  login: (email: string, password: string) => Promise<CurrentUser>
  logout: () => Promise<void>
  refresh: () => Promise<void>
  /** Replace the cached user (e.g. after change-password clears the flag). */
  setUser: (user: CurrentUser) => void
}

const Ctx = createContext<AdminAuthContextValue | null>(null)

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AdminAuthState>({
    user: null,
    loading: true,
    forbidden: false,
    error: null,
  })

  const refresh = useCallback(async () => {
    try {
      const { user } = await authApi.me()
      // /api/admin/auth/me only resolves admin-scoped sessions — if the server
      // accepts the cookie at all, the user is authoritatively an admin. The
      // defensive role check below is kept as belt-and-braces so a buggy
      // server upgrade can never silently elevate a non-admin in the UI.
      if (user.role !== 'admin') {
        setState({ user: null, loading: false, forbidden: true, error: null })
      } else {
        setState({ user, loading: false, forbidden: false, error: null })
      }
    } catch (e) {
      // 401 = no/expired admin cookie. Treat as "logged out", not an error.
      if (e instanceof ApiError && e.status === 401) {
        setState({ user: null, loading: false, forbidden: false, error: null })
      } else {
        setState({
          user: null,
          loading: false,
          forbidden: false,
          error: e instanceof Error ? e.message : 'Unable to load session',
        })
      }
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const login = useCallback(async (email: string, password: string) => {
    // The server returns 403 FORBIDDEN with no cookie set when the account is
    // not an admin — surface that as `forbidden=true` so the login page can
    // show "บัญชีนี้ไม่มีสิทธิ์เข้าใช้ Backoffice". 401 is reserved for bad
    // credentials and propagates unchanged for the page's error renderer.
    try {
      const { user } = await authApi.login({ email, password })
      if (user.role !== 'admin') {
        // Should never happen — the server gates issuance on role='admin' —
        // but keep a defensive guard so a broken server can't silently log in
        // a non-admin via Backoffice.
        try {
          await authApi.logout()
        } catch {
          // ignore
        }
        setState({ user: null, loading: false, forbidden: true, error: null })
        throw new ApiError(
          'FORBIDDEN',
          'บัญชีนี้ไม่มีสิทธิ์เข้าใช้ Backoffice',
          403,
        )
      }
      setState({ user, loading: false, forbidden: false, error: null })
      return user
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        setState({ user: null, loading: false, forbidden: true, error: null })
      }
      throw e
    }
  }, [])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      setState({ user: null, loading: false, forbidden: false, error: null })
    }
  }, [])

  const setUser = useCallback((user: CurrentUser) => {
    setState({ user, loading: false, forbidden: false, error: null })
  }, [])

  const value = useMemo<AdminAuthContextValue>(
    () => ({ ...state, login, logout, refresh, setUser }),
    [state, login, logout, refresh, setUser],
  )

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAdminAuth(): AdminAuthContextValue {
  const ctx = useContext(Ctx)
  if (!ctx) {
    throw new Error('useAdminAuth must be used inside <AdminAuthProvider>')
  }
  return ctx
}
