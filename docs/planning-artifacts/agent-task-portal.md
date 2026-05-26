# Agent Task Brief — Portal (Member-facing SPA)

**Agent role:** Frontend developer (React + TypeScript)
**Repo path:** `/Users/pond/Developer/localhost/TikTok/Rerun/portal/` (create new)
**Owner:** Pond
**Estimated effort:** ~3 weeks (single agent, full-time AI-assisted)

> **Read these docs FIRST before writing any code:**
> 1. [tech-spec.md](tech-spec.md) — locked decisions (stack, conventions, validation rules)
> 2. [api-contract.md](api-contract.md) — your source of truth for API integration
> 3. [portal-backoffice-spec.md](portal-backoffice-spec.md) §5 — Portal pages + routes
> 4. [prd.md](prds/prd-TiktokRerun-2026-05-24/prd.md) §2 (persona, JTBD, user journeys) — understand WHO you're building for

---

## 1. Your scope

คุณเป็น **owner ของ Portal SPA** ที่ลูกค้า (solo seller) ใช้จัดการ devices, videos, lives, banners

ครอบคลุม:
- Vite + React + TypeScript + Tailwind setup
- Auth flow (login, signup, logout, protected routes)
- Pages: Dashboard, Devices, Videos, Live Config, History
- Real-time updates via `/ws/portal`
- Banner editor UI (static + dynamic)
- Mobile-responsive layout

คุณ **ไม่ทำ:** backend API, admin pages, mobile companion app

## 2. Persona reminder

**ตอง** — solo TikTok Shop seller, 24-38 ปี, มี 3-15 บัญชี, **คนเดียว** ทำทุกอย่าง
- ไม่ใช่ designer, ไม่ใช่ enterprise user
- ต้องการ workflow เร็ว, fewer clicks
- ใช้ desktop เป็น primary; เปิดมือถือดู status ก่อนนอน
- ภาษาหลัก: **ไทย**

→ UI ควร **simple, direct, Thai-first.**

## 3. Setup

```bash
cd /Users/pond/Developer/localhost/TikTok/Rerun
bun create vite portal -- --template react-ts
cd portal
bun add react-router-dom
bun add -d tailwindcss@4 @tailwindcss/vite
```

Configure:
- `vite.config.ts` — proxy `/api` → `http://localhost:8080`, `/ws` → `ws://localhost:8080`
- `tailwind.config.ts` — minimal, Thai font fallback `'Sukhumvit Set', 'Sarabun', sans-serif`
- All fetch calls include `credentials: 'include'`
- Dev port: `5173`

## 4. Pages (per portal-backoffice-spec §5.2)

| Route | Purpose | Priority |
|---|---|---|
| `/login` | Login form → POST `/api/auth/login` → redirect `/dashboard` | P0 |
| `/signup` | Signup form + ToS checkbox → POST `/api/auth/signup` → redirect `/dashboard` | P0 |
| `/dashboard` | Overview: count widgets (devices online/total, videos count, active lives count) | P0 |
| `/devices` | Device list + status badges + pair-new-device button (gen QR via `/api/pair/token`) | P0 |
| `/videos` | Video library: upload, list, edit name, delete | P0 |
| `/videos/:id/banners` | Banner editor for a video (static banners) | P1 |
| `/live` | Start-live config: pick device(s), video, title/caption/hashtags, pinned product | P0 |
| `/live/active` | Active lives view + mid-live controls (switch video, pin product, update banner) | P0 |
| `/history` | Past lives (last 90 days) — list + filter by device + filter by date | P2 |

## 5. Order of work (must follow)

### Phase A — Foundation (Week 1)
1. Scaffold Vite project, install dependencies
2. Setup Tailwind + Thai font + global CSS
3. Create `src/api/client.ts` (fetch wrapper)
4. Create `src/contexts/AuthContext.tsx`
5. Create layout with top nav + side nav (or top tabs)
6. Create `ProtectedRoute` component
7. Implement `/login` and `/signup` pages
8. **Checkpoint with Pond:** login flow works against backend

### Phase B — Core pages (Week 1-2)
9. `/dashboard` — basic counts (fetch `/api/devices`, `/api/videos`, `/api/lives/active`)
10. `/devices` — list + pair flow (show QR modal)
11. `/videos` — upload (multipart), list, delete
12. `/live` (start-live form) — multi-device select, video picker, metadata form
13. **Checkpoint:** can complete UJ-1 (pair device) + UJ-2 (start live) end-to-end

### Phase C — Real-time + active live control (Week 2)
14. `src/hooks/useWebSocket.ts` — connect to `/ws/portal`, auto-reconnect
15. Wire up live status updates on `/devices` and `/dashboard`
16. `/live/active` page — show active lives, switch video button, pin product button
17. **Checkpoint:** can complete UJ-3 (pin product mid-live) + UJ-4 (switch video)

### Phase D — Banners (Week 2-3)
18. Banner editor component (text, color, slot, font size, optional deadline)
19. `/videos/:id/banners` — manage static banners attached to a video
20. Inline banner controls on `/live/active` for dynamic banners
21. **Checkpoint:** can create, update, delete banners; see banners reflected on broadcasts

### Phase E — History + polish (Week 3)
22. `/history` page — list + filters
23. Loading states, error handling for all forms
24. Mobile-responsive review (test on phone-sized viewport)
25. Final UX polish per persona priorities
26. **Final checkpoint:** all 5 UJs from PRD §2.4 complete end-to-end

## 6. Definition of Done

- [ ] All routes in §4 render and function
- [ ] Auth flow works: signup, login, logout, session persists across refresh
- [ ] Protected routes redirect to `/login` when no session
- [ ] All forms validate per tech-spec §3 rules client-side
- [ ] WebSocket connection establishes + reconnects on disconnect
- [ ] Device status updates show in UI in real-time (< 2s latency)
- [ ] Banner editor creates/edits/deletes banners successfully
- [ ] Mobile responsive: layouts work at 360px+ width
- [ ] No console errors during happy-path flows
- [ ] Manual smoke test: signup → upload video → pair device → start live → pin product → switch video → stop live → see in history

## 7. UI principles

### Visual design
- Color palette: Tailwind default + accent color (Pond confirms — default to `bg-blue-600`)
- Spacing: generous padding (`p-4` minimum on cards)
- Typography: Sukhumvit Set / Sarabun for Thai readability
- Icons: heroicons via inline SVG (no icon library dependency)

### Patterns
- **Always show loading state** during async actions (button spinner, skeleton list)
- **Always show error message** when API fails — use error code from response
- **Confirmation dialog** before destructive actions (delete video, unpair device)
- **Toast notifications** for non-blocking feedback (banner updated, command sent)
- **Inline help text** next to non-obvious fields (e.g., "วิดีโอจะ broadcast วนซ้ำ")

### Component conventions
- Files: `PascalCase.tsx` for components, `camelCase.ts` for utilities
- Props: TypeScript interfaces, no `any`
- State: lift state up; Context for cross-page (auth, theme); useState for local
- Side effects: useEffect with cleanup; no race conditions on unmount

## 8. Coordination with other agents

### What you provide
- Working SPA at `http://localhost:5173`
- Working signup → login → dashboard flow
- Feedback to API agent on contract clarity

### What you need from API agent
- All endpoints in [api-contract.md](api-contract.md) §2 responding
- `/ws/portal` WebSocket pushing real-time updates
- Stable API contract — propose changes via PR review

### What you need from Backoffice agent
- Coordinate on shared types if you choose to create `src/types/api.ts` shared package (optional for v1)
- Style conventions consistent enough for brand cohesion

### Communication
- Daily progress to Pond
- Surface API contract gaps as soon as found
- Pull from `api-contract.md` to generate type definitions (consider `quicktype` or hand-write)

## 9. Don'ts

- ❌ Don't introduce TanStack Query / SWR / Redux / Zustand
- ❌ Don't use MUI / Ant Design / Chakra / shadcn (handle Tailwind + custom)
- ❌ Don't use Next.js (project is Vite-only for v1)
- ❌ Don't use form libraries (react-hook-form, formik) for v1 — controlled components
- ❌ Don't fetch from `localhost:8080` directly — use Vite proxy (`/api/...`)
- ❌ Don't store auth token in localStorage — relies on cookie set by backend
- ❌ Don't write English text in user-facing UI — Thai-first
- ❌ Don't add a feature not in §4 table without Pond approval

## 10. Reference snippets

### API client wrapper
```ts
// src/api/client.ts
export class ApiError extends Error {
  constructor(public code: string, message: string, public status: number) {
    super(message);
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(
      body.error?.code ?? 'UNKNOWN',
      body.error?.message ?? res.statusText,
      res.status
    );
  }
  return res.json();
}
```

### Auth context skeleton
```tsx
// src/contexts/AuthContext.tsx
type User = { id: number; email: string; role: 'user' | 'admin' };
type AuthState = { user: User | null; loading: boolean };

const AuthContext = createContext<{
  state: AuthState;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}>(null!);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({ user: null, loading: true });

  useEffect(() => {
    apiFetch<{ user: User }>('/auth/me')
      .then(({ user }) => setState({ user, loading: false }))
      .catch(() => setState({ user: null, loading: false }));
  }, []);

  // ... login, signup, logout implementations
}
```

### Protected route
```tsx
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { state } = useContext(AuthContext);
  if (state.loading) return <div>Loading...</div>;
  if (!state.user) return <Navigate to="/login" />;
  return <>{children}</>;
}
```

### WebSocket hook
```ts
export function useWebSocket(onMessage: (msg: WsMessage) => void) {
  useEffect(() => {
    let ws: WebSocket;
    let backoff = 1000;

    function connect() {
      ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/portal`);
      ws.onopen = () => { backoff = 1000; };
      ws.onmessage = (e) => onMessage(JSON.parse(e.data));
      ws.onclose = () => {
        setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, 30000);
      };
    }
    connect();
    return () => ws.close();
  }, [onMessage]);
}
```

## 11. Questions to ask Pond before starting

1. Brand color preference? (default `blue-600` ถ้าไม่ระบุ)
2. Logo / app icon: มีไฟล์ส่งให้ หรือ text-only "TiktokRerun"?
3. Should `/history` page be a stub redirect ("coming soon") for MVP, or full list view?
4. Sign-up requires invite code, or full self-serve open?
5. ToS text content — Pond writes or use placeholder?
6. After login: redirect to `/dashboard` always, or last visited page?

When unsure → **stop and ask, don't guess.**
