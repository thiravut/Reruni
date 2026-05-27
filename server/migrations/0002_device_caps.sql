-- +goose Up
-- +goose StatementBegin
-- caps_json holds a JSON blob the mobile companion reports on every
-- WS open: permission states, installed apps, app version, etc. The
-- portal renders this as readiness badges + setup guidance per device.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS caps_json TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS caps_reported_at TIMESTAMPTZ;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE devices DROP COLUMN IF EXISTS caps_reported_at;
ALTER TABLE devices DROP COLUMN IF EXISTS caps_json;
-- +goose StatementEnd
