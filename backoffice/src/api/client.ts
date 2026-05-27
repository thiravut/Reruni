// Thin fetch wrapper shared by every admin API call.
// Mirrors the pattern proposed for portal/src/api/client.ts in tech-spec.md §8.
//
// Two key behaviors:
// 1. Always send the session cookie (`credentials: 'include'`) — admin auth
//    is identical to portal auth at the transport level.
// 2. Normalize errors into `ApiError` so callers can branch on `.code` /
//    `.status` instead of parsing free-form response bodies.

import type { ApiErrorBody } from '../types/api'

export class ApiError extends Error {
  code: string
  status: number
  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

export interface ApiFetchInit extends Omit<RequestInit, 'body'> {
  body?: BodyInit | Record<string, unknown> | null
  query?: Record<string, string | number | boolean | null | undefined>
}

function buildUrl(path: string, query?: ApiFetchInit['query']): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? ''
  const apiPath = path.startsWith('/api') ? path : `/api${path.startsWith('/') ? '' : '/'}${path}`
  const base = `${apiBase}${apiPath}`
  if (!query) return base
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    usp.set(k, String(v))
  }
  const qs = usp.toString()
  return qs ? `${base}?${qs}` : base
}

export async function apiFetch<T>(path: string, init: ApiFetchInit = {}): Promise<T> {
  const { body, query, headers, ...rest } = init
  const isJsonBody =
    body !== undefined &&
    body !== null &&
    !(body instanceof FormData) &&
    !(body instanceof Blob) &&
    !(body instanceof ArrayBuffer) &&
    !(typeof body === 'string')

  const finalHeaders: HeadersInit = {
    Accept: 'application/json',
    ...(isJsonBody ? { 'Content-Type': 'application/json' } : {}),
    ...(headers ?? {}),
  }

  const res = await fetch(buildUrl(path, query), {
    credentials: 'include',
    headers: finalHeaders,
    body: isJsonBody ? JSON.stringify(body) : (body as BodyInit | null | undefined),
    ...rest,
  })

  // 204 No Content is common for admin destructive endpoints — return undefined.
  if (res.status === 204) return undefined as T

  const text = await res.text()
  const parsed = text ? safeJson(text) : null

  if (!res.ok) {
    const errBody = (parsed ?? {}) as ApiErrorBody
    throw new ApiError(
      errBody.error?.code ?? 'UNKNOWN',
      errBody.error?.message ?? res.statusText ?? 'Request failed',
      res.status,
    )
  }

  return (parsed ?? null) as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}
