# Migrations

Goose-managed SQL migrations, applied automatically at server boot from the
embedded FS (see `server/db.go`).

## Filename format

```
YYYYMMDDHHMMSS_snake_case_description.sql
```

Use the current UTC timestamp at the moment you create the file — not the
date of the change you're describing, not the next sequential integer.
Timestamps are unique per second, so parallel branches don't collide on
filename the way sequential numbering does.

Generate the prefix with:

```sh
date -u +%Y%m%d%H%M%S
```

or let goose do it:

```sh
goose -dir server/migrations create your_change_description sql
```

## Authoring rules

- One logical change per file. Don't bundle unrelated schema edits.
- Write the `Down` block even if it's `SELECT 1;` for data-only fixes — the
  goose template won't run without it.
- Migrations run inside a transaction by default. Wrap raw statements in
  `-- +goose StatementBegin` / `-- +goose StatementEnd` only if you need
  `CREATE INDEX CONCURRENTLY` or other non-transactional DDL.
- Backfills must be idempotent (use `WHERE` clauses that exclude
  already-fixed rows) so re-running on a partially-fixed DB stays safe.

## History note

Pre-2026-06-01 migrations used 4-digit sequential prefixes (`0001_…`).
They were renamed in-place to timestamps when the team setup began; the
`goose_db_version` table on every environment was updated at the same
time so existing applied versions still match their files.
