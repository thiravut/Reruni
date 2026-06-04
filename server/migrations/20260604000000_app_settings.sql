-- +goose Up
-- +goose StatementBegin

-- Generic key/value store for operator-tunable settings that don't deserve
-- their own table. First use: pinning which APK filename in downloadsDir is
-- "current" for /api/downloads/manifest so ops can ship a new version by
-- dropping a file + flipping a setting in the backoffice — no redeploy.
CREATE TABLE IF NOT EXISTS app_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

DROP TABLE IF EXISTS app_settings;

-- +goose StatementEnd
