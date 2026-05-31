-- +goose Up
-- +goose StatementBegin
-- loop_count controls playback repetition of the video(s) assigned to a
-- live session. NULL means "loop forever". A positive integer means "play
-- the video playlist N full passes, then stop". Default = 1 (play once)
-- preserves the pre-feature behaviour.
ALTER TABLE live_sessions
    ADD COLUMN IF NOT EXISTS loop_count INTEGER;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE live_sessions DROP COLUMN IF EXISTS loop_count;
-- +goose StatementEnd
