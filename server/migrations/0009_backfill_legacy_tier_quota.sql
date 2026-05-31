-- +goose Up
-- +goose StatementBegin

-- 0006_onboarding.sql added subscriptions.device_quota DEFAULT 0. The new
-- quota check (createPairTokenHandler → checkDeviceQuota) reads this column
-- as authoritative, so every pre-pivot 3-tier subscriber (starter/growth/pro)
-- now reads as quota=0 and is blocked at first pair attempt — even though
-- they paid for a plan with an implicit device cap.
--
-- Backfill the implied cap from the pre-pivot tier catalog (see commit
-- ef55c54 server/billing.go before the per-device pivot):
--   starter → 10 devices
--   growth  → 30 devices
--   pro     → 100 devices
--
-- Flat-tier ('device') subscriptions are untouched — their quota is the
-- Stripe subscription item quantity, kept in sync by the webhook.

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
