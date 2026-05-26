-- +goose Up
-- +goose StatementBegin
CREATE TABLE IF NOT EXISTS users (
    id                   BIGSERIAL PRIMARY KEY,
    email                TEXT NOT NULL UNIQUE,
    password_hash        TEXT NOT NULL,
    role                 TEXT NOT NULL DEFAULT 'user',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope      TEXT NOT NULL DEFAULT 'portal',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user  ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_scope ON sessions(scope);

CREATE TABLE IF NOT EXISTS videos (
    id            BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL DEFAULT 0,
    name          TEXT NOT NULL,
    filename      TEXT NOT NULL,
    size_bytes    BIGINT NOT NULL,
    duration_sec  BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_videos_owner ON videos(owner_user_id);

CREATE TABLE IF NOT EXISTS devices (
    id                 TEXT PRIMARY KEY,
    owner_user_id      BIGINT NOT NULL DEFAULT 0,
    name               TEXT,
    device_token       TEXT,
    status             TEXT NOT NULL DEFAULT 'idle',
    current_video_id   BIGINT,
    current_pinned_sku TEXT,
    sku_tier           TEXT NOT NULL DEFAULT 'v1_lite',
    current_account_id BIGINT,
    paired_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_devices_owner ON devices(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_devices_token ON devices(device_token);

CREATE TABLE IF NOT EXISTS pair_tokens (
    token         TEXT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_pair_tokens_owner ON pair_tokens(owner_user_id);

CREATE TABLE IF NOT EXISTS live_sessions (
    id            BIGSERIAL PRIMARY KEY,
    device_id     TEXT NOT NULL,
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    video_id      BIGINT REFERENCES videos(id),
    title         TEXT,
    caption       TEXT,
    hashtags      TEXT,
    pinned_sku    TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at      TIMESTAMPTZ,
    end_reason    TEXT
);
CREATE INDEX IF NOT EXISTS idx_lives_device ON live_sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_lives_owner  ON live_sessions(owner_user_id);

CREATE TABLE IF NOT EXISTS banners (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT NOT NULL REFERENCES users(id),
    video_id        BIGINT REFERENCES videos(id),
    live_session_id BIGINT REFERENCES live_sessions(id),
    slot            TEXT NOT NULL,
    text            TEXT NOT NULL,
    bg_color        TEXT NOT NULL DEFAULT '#000000',
    text_color      TEXT NOT NULL DEFAULT '#FFFFFF',
    font_size       TEXT NOT NULL DEFAULT 'M',
    deadline        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_banners_video ON banners(video_id);
CREATE INDEX IF NOT EXISTS idx_banners_live  ON banners(live_session_id);

CREATE TABLE IF NOT EXISTS commands (
    id            TEXT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    device_id     TEXT NOT NULL,
    type          TEXT NOT NULL,
    payload_json  TEXT,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ack_at        TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'pending',
    error_code    TEXT,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_commands_device ON commands(device_id);
CREATE INDEX IF NOT EXISTS idx_commands_owner  ON commands(owner_user_id);

CREATE TABLE IF NOT EXISTS subscriptions (
    id                     BIGSERIAL PRIMARY KEY,
    user_id                BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_customer_id     TEXT NOT NULL,
    stripe_subscription_id TEXT,
    stripe_price_id        TEXT,
    tier                   TEXT NOT NULL,
    status                 TEXT NOT NULL,
    current_period_start   TIMESTAMPTZ,
    current_period_end     TIMESTAMPTZ,
    cancel_at_period_end   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_user       ON subscriptions(user_id);
CREATE INDEX        IF NOT EXISTS idx_subscriptions_stripe_sub ON subscriptions(stripe_subscription_id);

CREATE TABLE IF NOT EXISTS stripe_events (
    id              BIGSERIAL PRIMARY KEY,
    stripe_event_id TEXT NOT NULL UNIQUE,
    event_type      TEXT NOT NULL,
    payload_json    TEXT,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tiktok_accounts (
    id            BIGSERIAL PRIMARY KEY,
    device_id     TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label         TEXT NOT NULL,
    username      TEXT,
    status        TEXT NOT NULL DEFAULT 'active',
    snapshot_path TEXT,
    last_used_at  TIMESTAMPTZ,
    banned_at     TIMESTAMPTZ,
    captcha_count INTEGER NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_device ON tiktok_accounts(device_id);
CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_owner  ON tiktok_accounts(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_status ON tiktok_accounts(status);
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP TABLE IF EXISTS tiktok_accounts;
DROP TABLE IF EXISTS stripe_events;
DROP TABLE IF EXISTS subscriptions;
DROP TABLE IF EXISTS commands;
DROP TABLE IF EXISTS banners;
DROP TABLE IF EXISTS live_sessions;
DROP TABLE IF EXISTS pair_tokens;
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS videos;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS users;
-- +goose StatementEnd
