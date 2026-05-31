-- +goose Up
-- +goose StatementBegin

-- 0006_onboarding.sql added onboarding_step with DEFAULT 'welcome' applied
-- to every existing user — including paying customers — which then routes
-- them back through the wizard on next login.
--
-- Backfill anyone who has clearly already onboarded: a Stripe subscription
-- (any status — they have transacted at some point) or at least one paired
-- device. Users signed up between 0006 and this migration who never paid
-- and never paired stay on 'welcome' so the wizard still applies to them.
--
-- Note: this only catches step='welcome'. Paying users who clicked through
-- the first wizard screen sit at 'pick_quantity' / 'payment' — those are
-- covered by 0008_backfill_onboarding_complete_widen.sql.

UPDATE users
SET onboarding_step = 'complete'
WHERE onboarding_step = 'welcome'
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

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

-- One-way data fix — cannot reliably reverse since we don't track which
-- rows were updated. No-op.
SELECT 1;

-- +goose StatementEnd
