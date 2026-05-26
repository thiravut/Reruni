# Agent Task Brief — Backoffice (Admin SPA)

**Agent role:** Frontend developer (React + TypeScript)
**Repo path:** `/Users/pond/Developer/localhost/TikTok/Rerun/backoffice/` (create new)
**Owner:** Pond
**Estimated effort:** ~2 weeks (single agent, full-time AI-assisted)

> **Read these docs FIRST before writing any code:**
> 1. [tech-spec.md](tech-spec.md) — locked decisions (stack, conventions)
> 2. [api-contract.md](api-contract.md) §2.9 — admin endpoints (your source of truth)
> 3. [portal-backoffice-spec.md](portal-backoffice-spec.md) §6 — Backoffice pages
> 4. [agent-task-portal.md](agent-task-portal.md) — mirror portal patterns

---

## 1. Your scope

คุณเป็น **owner ของ Backoffice SPA** ที่ทีม TiktokRerun (admin) ใช้:
- Monitor ทั้ง platform
- Support members (manage users, devices)
- Intervene เมื่อจำเป็น (force-stop live, disconnect device, reset password)
- ดู aggregate metrics

ครอบคลุม:
- Vite + React + TypeScript + Tailwind setup
- Admin auth flow (separate from portal — different login page)
- Pages: AdminLogin, AdminUsers, AdminDevices, AdminVideos, AdminMetrics, AdminLives
- Cross-user data view (admin sees all)

คุณ **ไม่ทำ:** backend, portal (member-facing), mobile companion app

## 2. Audience: Admin user (TiktokRerun team)

- Internal staff — TiktokRerun engineers / support / Pond
- Not a customer-facing UI — prioritize **info density + utility** over UX polish
- Tables, filters, search — not cards + flowers
- Language: ไทยหรืออังกฤษก็ได้ — admin context ไม่ต้อง Thai-first บังคับ (Pond confirms)

## 3. Setup

```bash
cd /Users/pond/Developer/localhost/TikTok/Rerun
bun create vite backoffice -- --template react-ts
cd backoffice
bun add react-router-dom
bun add -d tailwindcss@4 @tailwindcss/vite
```

Configure:
- `vite.config.ts` — proxy `/api` → `http://localhost:8080`, dev port `5174`
- Tailwind setup mirrors portal
- All fetch calls include `credentials: 'include'`

## 4. Pages (per portal-backoffice-spec §6.2)

| Route | Purpose | Priority |
|---|---|---|
| `/admin/login` | Admin email/password (route through same `/api/auth/login` but verify `role==='admin'`) | P0 |
| `/admin/users` | Table: id, email, role, created_at, device count, video count, last active + actions (promote, demote, reset password, delete) | P0 |
| `/admin/devices` | Table: id, name, owner email, status, last_seen + action (force disconnect) | P0 |
| `/admin/videos` | Table: id, filename, owner email, size, uploaded_at — sort by size for disk usage | P1 |
| `/admin/metrics` | Dashboard widgets: users total, active 7d, devices total/online/live, lives 24h, broadcast hours, disk used, uptime % | P0 |
| `/admin/lives` | Table: active + recent lives across all users + action (force stop) | P1 |

## 5. Order of work (must follow)

### Phase A — Foundation (Week 1)
1. Scaffold Vite project (mirror portal setup)
2. Setup Tailwind + base layout (sidebar + main content area, table-heavy)
3. Create `src/api/client.ts` (same pattern as portal — may copy)
4. Create `src/contexts/AdminAuthContext.tsx` (checks `role==='admin'`)
5. Implement `/admin/login` page
6. Create `ProtectedAdminRoute` (redirect non-admin to login)
7. **Checkpoint with Pond:** admin login works; non-admin login attempt rejected

### Phase B — Core admin pages (Week 1-2)
8. `/admin/users` — table with search + pagination + actions
9. `/admin/devices` — table with status filter + force-disconnect action
10. `/admin/metrics` — dashboard widgets fetched from `/api/admin/metrics`
11. `/admin/lives` — active + recent with force-stop
12. **Checkpoint:** all admin operations work end-to-end

### Phase C — Polish (Week 2)
13. `/admin/videos` — disk usage view
14. Filters + sorting on all tables
15. CSV export (optional, simple `<a>` with data URL)
16. Confirmation dialogs for destructive actions
17. Error handling + loading states
18. **Final checkpoint:** Pond can use Backoffice to manage real users + intervene during live

## 6. Definition of Done

- [ ] All routes in §4 render and function
- [ ] Admin login works; user role redirected away
- [ ] Tables show real data from `/api/admin/*` endpoints
- [ ] Pagination works (limit/offset query params)
- [ ] Search/filter works (where applicable)
- [ ] Actions (role change, password reset, force-disconnect, force-stop) trigger correct API calls + confirmation dialog
- [ ] Metrics page refreshes every 30s (or has manual refresh button)
- [ ] No console errors during admin workflows
- [ ] Manual smoke test: admin login → see users → promote user → reset password → see all devices → force-disconnect → see metrics

## 7. UI principles

### Visual
- Info-dense tables (text-sm, tighter padding)
- Sortable column headers (click to sort)
- Sticky table headers when scrolling long lists
- Color-coded status badges: green (online/active), yellow (warning), red (offline/error), gray (idle)
- Confirmation dialogs use red button for destructive actions

### Patterns
- **Always show counts** (e.g., "Showing 1-50 of 234")
- **Always allow refresh** (button or auto every 30s for metrics)
- **Inline edit** where simple (e.g., role dropdown directly in row)
- **Bulk actions later** — v1 single-row actions only

## 8. Coordination with other agents

### What you need from API agent
- All `/api/admin/*` endpoints in [api-contract.md](api-contract.md) §2.9 responding
- `requireAdmin` middleware enforcing role on those endpoints
- Stable contract — propose changes via PR review

### What you need from Portal agent
- Style coherence (similar visual language, even if denser)
- Optionally share `src/types/api.ts` if both find it useful (not required)

### Daily sync to Pond
- Surface admin operations gaps
- Suggest UX improvements based on Pond's actual admin workflows

## 9. Don'ts

- ❌ Same stack guardrails as portal (no TanStack Query, no MUI, no Redux, etc.)
- ❌ Don't fetch from `localhost:8080` directly — use Vite proxy
- ❌ Don't store auth differently from portal (same cookie + same `/api/auth/me`)
- ❌ Don't expose admin features that bypass user data isolation (admin VIEW all is OK; admin should not WRITE to user-owned data without audit trail)
- ❌ Don't add features not in §4 without Pond approval

## 10. Reference snippets

### Admin auth context
```tsx
// src/contexts/AdminAuthContext.tsx
type AdminUser = { id: number; email: string; role: 'admin' };

export function AdminAuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<{ user: AdminUser | null; loading: boolean }>({
    user: null,
    loading: true,
  });

  useEffect(() => {
    apiFetch<{ user: { id: number; email: string; role: string } }>('/auth/me')
      .then(({ user }) => {
        if (user.role !== 'admin') {
          setState({ user: null, loading: false });
        } else {
          setState({ user: user as AdminUser, loading: false });
        }
      })
      .catch(() => setState({ user: null, loading: false }));
  }, []);

  // ... login (call /api/auth/login then check role)
}
```

### Sortable table column
```tsx
function SortableHeader({ label, field, sort, onSort }: {
  label: string;
  field: string;
  sort: { field: string; direction: 'asc' | 'desc' };
  onSort: (field: string) => void;
}) {
  const isActive = sort.field === field;
  return (
    <th
      onClick={() => onSort(field)}
      className="cursor-pointer select-none text-sm font-semibold p-2 hover:bg-gray-100"
    >
      {label} {isActive && (sort.direction === 'asc' ? '↑' : '↓')}
    </th>
  );
}
```

### Confirmation dialog
```tsx
function ConfirmDialog({
  open, title, description, danger, onConfirm, onCancel,
}: { /* ... */ }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg p-6 max-w-md">
        <h2 className="text-lg font-semibold mb-2">{title}</h2>
        <p className="text-sm text-gray-600 mb-4">{description}</p>
        <div className="flex gap-2 justify-end">
          <button onClick={onCancel} className="px-4 py-2 border rounded">ยกเลิก</button>
          <button
            onClick={onConfirm}
            className={`px-4 py-2 rounded text-white ${danger ? 'bg-red-600' : 'bg-blue-600'}`}
          >
            ยืนยัน
          </button>
        </div>
      </div>
    </div>
  );
}
```

## 11. Questions to ask Pond before starting

1. UI language: ไทยหรืออังกฤษ? (admin context flexible)
2. Audit log of admin actions — show in UI or just store backend?
3. Force-stop / force-disconnect — should notify the affected user (email)? v1 = silent?
4. Metrics refresh interval — 30s OK or different?
5. Need bulk actions in v1 (e.g., delete multiple users at once)?
6. Backoffice subdomain at GA: `backoffice.<domain>` or `admin.<domain>`?

When unsure → **stop and ask, don't guess.**
