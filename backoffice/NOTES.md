# Backoffice — implementation notes

Tracking gaps in the API contract / assumptions made during build so the API
agent and Pond can review. (Do NOT edit `docs/planning-artifacts/api-contract.md`
from this side — file issues here instead.)

## Open questions for the API contract (§2.9)

1. **`GET /api/admin/lives` response shape**
   §2.9 says "paginated list with owner info" but the row shape is not enumerated.
   I assumed:
   ```ts
   { id, device_id, device_name?, owner_user_id, owner_email,
     video_id, title, started_at, ended_at, end_reason, status? }
   ```
   `status` is computed client-side (`ended_at ? 'ended' : 'active'`) if the
   server doesn't supply it. Please confirm or extend the contract.

2. **`GET /api/admin/devices` `status` filter values**
   Contract example uses `?status=live`. I expose 4 filter options in UI:
   `online | offline | live | idle`. If backend only recognizes a subset,
   un-supported values will round-trip as 400; UI shows the error.

3. **`GET /api/admin/users` `last_active_at` source**
   Contract names the field but not the source. Assumed = max of
   `sessions.created_at` for that user. If session activity isn't tracked,
   field will arrive `null` and renders as "never".

4. **Sortable columns** — API does not document `sort` / `order` params.
   v1 sorts the current page client-side (works fine at 50 rows per page).
   When backend gains a `sort` param, swap `pickSort` calls for query params.

5. **Reset-password response** — contract returns `{ "temp_password": "..." }`.
   UI shows it once in a modal + copy-to-clipboard. Confirm there is no
   one-time-token flow planned that would change this contract.

6. **Role change endpoint** — contract says `PATCH /api/admin/users/:id/role`,
   portal-backoffice-spec §6.3 says `POST`. I used `PATCH` to match the more
   recently dated contract. API agent — flag if you implement `POST` instead.

7. **CSV export** — listed as optional in Phase C. Skipped for v1.
   Trivial to add later via a `<a href="data:text/csv;…" download>` button.

8. **WebSocket push for admin** — out of scope per portal-backoffice-spec §10.
   Metrics page polls `/api/admin/metrics` every 30 s instead.

## Bootstrap reminders for Pond

To exercise Backoffice end-to-end you need an admin account on the API server:

```bash
export BOOTSTRAP_ADMIN_EMAIL=admin@example.com
export BOOTSTRAP_ADMIN_PASSWORD=changeme
export CORS_ORIGINS=http://localhost:5173,http://localhost:5174
cd server && go run .
```

Then in a new terminal:

```bash
cd backoffice && bun install && bun run dev
# open http://localhost:5174/admin/login
```

## Pre-launch checklist (from agent-task-backoffice.md §6)

- [x] All routes in §4 render
- [x] Admin login works; non-admin user redirected away (login refuses the
      session + logs the user back out)
- [x] Tables paginate (`limit`/`offset` query params)
- [x] Search (Users) + status filter (Devices, Lives)
- [x] Destructive actions trigger confirmation dialog (red button)
- [x] Metrics auto-refresh every 30 s + manual refresh button
- [x] Loading + error states on every page
- [x] No console errors during admin workflows (verified via `bun run build` +
      manual smoke against mocked endpoints)
- [ ] **Pending:** real end-to-end smoke once API agent ships `/api/admin/*`
