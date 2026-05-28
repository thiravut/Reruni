-- +goose Up
-- +goose StatementBegin
-- Ephemeral videos are created on demand when POST /api/lives/start receives
-- more than one video_id — the server merges them with ffmpeg and stores the
-- result as a regular Video row, but flagged so:
--   - it's filtered out of /api/videos (operators see only their uploads)
--   - the live_ended handler can ref-count and delete it after the last
--     device referencing it closes its session
ALTER TABLE videos
    ADD COLUMN IF NOT EXISTS is_ephemeral BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_videos_ephemeral ON videos(is_ephemeral) WHERE is_ephemeral = TRUE;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP INDEX IF EXISTS idx_videos_ephemeral;
ALTER TABLE videos DROP COLUMN IF EXISTS is_ephemeral;
-- +goose StatementEnd
