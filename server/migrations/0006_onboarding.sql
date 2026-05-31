-- +goose Up
-- +goose StatementBegin

-- Per-user onboarding wizard state (V1 launch — flat per-device pricing).
-- See docs/planning-artifacts/prd-v1-launch.md §3.12 for step semantics.
ALTER TABLE users ADD COLUMN IF NOT EXISTS onboarding_step TEXT NOT NULL DEFAULT 'welcome';
CREATE INDEX IF NOT EXISTS idx_users_onboarding_step ON users(onboarding_step);

-- Device quota carried on the subscription row. Stripe is the source of truth
-- (subscription.items[0].quantity), but we mirror it locally so quota
-- enforcement on /api/devices/pair can hit one row instead of round-tripping
-- to Stripe. Backfilled to 0 — webhook handler keeps it in sync going forward.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS device_quota INTEGER NOT NULL DEFAULT 0;

-- Stuck-payment email reminder tracker. NULL columns mean reminder not sent
-- yet; populated by the reminder cron when a fired email is queued.
ALTER TABLE users ADD COLUMN IF NOT EXISTS payment_reminder_3d_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS payment_reminder_7d_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS payment_reminder_15d_at TIMESTAMPTZ;

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

DROP INDEX IF EXISTS idx_users_onboarding_step;
ALTER TABLE users          DROP COLUMN IF EXISTS onboarding_step;
ALTER TABLE users          DROP COLUMN IF EXISTS payment_reminder_3d_at;
ALTER TABLE users          DROP COLUMN IF EXISTS payment_reminder_7d_at;
ALTER TABLE users          DROP COLUMN IF EXISTS payment_reminder_15d_at;
ALTER TABLE subscriptions  DROP COLUMN IF EXISTS device_quota;

-- +goose StatementEnd
