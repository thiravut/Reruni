-- +goose Up
-- +goose StatementBegin

-- 0007 only backfilled users still sitting at the wizard's first step
-- ('welcome'). Paying users who clicked the "Begin" button before the
-- portal fix shipped advanced to 'pick_quantity' (and a few who tried to
-- re-checkout sit at 'payment') — those rows were missed.
--
-- Widen the rule to any non-complete step: if the user has ever transacted
-- through Stripe, they have already onboarded.

UPDATE users
SET onboarding_step = 'complete'
WHERE onboarding_step <> 'complete'
  AND id IN (
    SELECT user_id FROM subscriptions
    WHERE stripe_subscription_id IS NOT NULL
      AND stripe_subscription_id <> ''
  );

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

-- One-way data fix — cannot reliably reverse. No-op.
SELECT 1;

-- +goose StatementEnd
