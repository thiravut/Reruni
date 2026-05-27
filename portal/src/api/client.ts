// Thin fetch wrapper. Always uses /api proxy and includes session cookie.

export class ApiError extends Error {
  code: string;
  status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.code = code;
    this.status = status;
    this.name = 'ApiError';
  }
}

interface FetchOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  isFormData?: boolean;
}

export async function apiFetch<T>(
  path: string,
  options: FetchOptions = {},
): Promise<T> {
  const { body, isFormData, headers, ...rest } = options;

  const finalHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...(headers as Record<string, string> | undefined),
  };

  let requestBody: BodyInit | undefined;
  if (body !== undefined) {
    if (isFormData) {
      requestBody = body as FormData;
    } else {
      finalHeaders['Content-Type'] = 'application/json';
      requestBody = JSON.stringify(body);
    }
  }

  let res: Response;
  try {
    res = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? ''}/api${path}`, {
      credentials: 'include',
      headers: finalHeaders,
      body: requestBody,
      ...rest,
    });
  } catch {
    throw new ApiError('NETWORK_ERROR', 'ไม่สามารถเชื่อมต่อเซิร์ฟเวอร์ได้', 0);
  }

  if (res.status === 204) {
    return undefined as unknown as T;
  }

  const text = await res.text();
  const json = text ? safeJson(text) : null;

  if (!res.ok) {
    const code = json?.error?.code ?? 'UNKNOWN';
    const message =
      json?.error?.message ??
      mapStatusToThaiMessage(res.status) ??
      res.statusText ??
      'เกิดข้อผิดพลาด';
    throw new ApiError(code, message, res.status);
  }

  return (json as T) ?? (undefined as unknown as T);
}

interface ParsedResponse {
  error?: { code?: string; message?: string };
  [key: string]: unknown;
}

function safeJson(text: string): ParsedResponse | null {
  try {
    return JSON.parse(text) as ParsedResponse;
  } catch {
    return null;
  }
}

function mapStatusToThaiMessage(status: number): string | null {
  switch (status) {
    case 400:
      return 'ข้อมูลไม่ถูกต้อง';
    case 401:
      return 'กรุณาเข้าสู่ระบบ';
    case 403:
      return 'ไม่มีสิทธิ์เข้าถึง';
    case 404:
      return 'ไม่พบข้อมูล';
    case 409:
      return 'เกิดความขัดแย้งของข้อมูล';
    case 429:
      return 'ทำรายการบ่อยเกินไป กรุณารอสักครู่';
    case 500:
      return 'เซิร์ฟเวอร์ขัดข้อง';
    case 503:
      return 'บริการไม่พร้อมใช้งาน';
    default:
      return null;
  }
}
