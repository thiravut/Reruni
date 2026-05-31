-- +goose Up
-- +goose StatementBegin

-- Cleanup pass for the 2026-05-23 pricing pivot fallout.
--
-- 0006_onboarding.sql introduced two columns whose DEFAULT didn't match
-- reality for pre-existing users:
--
--   users.onboarding_step  DEFAULT 'welcome'  → forced every paying user
--                                               back through the wizard
--                                               on next login.
--   subscriptions.device_quota  DEFAULT 0    → made every pre-pivot
--                                               starter/growth/pro
--                                               subscriber read as
--                                               zero-quota and blocked
--                                               them at first pair.
--
-- Both fixes are idempotent — the WHERE clauses filter rows that already
-- look correct, so re-running on a partially-fixed DB is safe.
--
-- Historical note: this used to be three sequential migrations (0007
-- onboarding_step='welcome', 0008 widening to other steps, 0009 quota
-- backfill). Consolidated here for repo readability — the on-prod
-- goose_db_version rows for 0008 and 0009 were removed by hand at the
-- same time so prod and fresh-dev environments converge.

-- 1) onboarding_step — anyone who has clearly already onboarded.
--    Paying user (has a Stripe subscription id) OR owns a paired device
--    and currently sits at any non-complete step → 'complete'.
UPDATE users
SET onboarding_step = 'complete'
WHERE onboarding_step <> 'complete'
  AND (
    id IN (
      SELECT user_id FROM subscriptions
      WHERE stripe_subscription_id IS NOT NULL
        AND stripe_subscription_id <> ''
    )
    OR id IN (
      SELECT DISTINCT owner_user_id FROM devices
      WHERE owner_user_id IS NOT NULL AND owner_user_id <> 0
    )
  );

-- 2) device_quota — backfill the implied cap for pre-pivot 3-tier
--    subscribers (see commit ef55c54 server/billing.go before the
--    per-device pivot for the source-of-truth tier→devices mapping).
--    Flat-tier 'device' rows are untouched: their quota is the Stripe
--    subscription item quantity, kept in sync by the webhook.
UPDATE subscriptions
SET device_quota = CASE tier
  WHEN 'starter' THEN 10
  WHEN 'growth'  THEN 30
  WHEN 'pro'     THEN 100
END
WHERE tier IN ('starter','growth','pro')
  AND device_quota = 0;

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

-- One-way data fix — cannot reliably reverse. No-op.
SELECT 1;

-- +goose StatementEnd
