-- +goose Up
-- Latest autopilot progress + outcome reported by the device for a given
-- live session. The portal reads these to render a live timeline; on a
-- failed run the operator sees the friendly Thai message without us
-- exposing the raw debug text. Updated by the WS `autopilot_status`
-- handler (server/ws.go) — one row per live_sessions row, mutated in
-- place rather than appended as events for now (event history can come
-- in a separate table if dashboards want it later).
ALTER TABLE live_sessions
    ADD COLUMN IF NOT EXISTS autopilot_state TEXT,
    ADD COLUMN IF NOT EXISTS autopilot_script TEXT,
    ADD COLUMN IF NOT EXISTS autopilot_step_index INTEGER,
    ADD COLUMN IF NOT EXISTS autopilot_step_label TEXT,
    ADD COLUMN IF NOT EXISTS autopilot_error_user TEXT,
    ADD COLUMN IF NOT EXISTS autopilot_error_debug TEXT,
    ADD COLUMN IF NOT EXISTS autopilot_updated_at TIMESTAMPTZ;

-- +goose Down
ALTER TABLE live_sessions
    DROP COLUMN IF EXISTS autopilot_state,
    DROP COLUMN IF EXISTS autopilot_script,
    DROP COLUMN IF EXISTS autopilot_step_index,
    DROP COLUMN IF EXISTS autopilot_step_label,
    DROP COLUMN IF EXISTS autopilot_error_user,
    DROP COLUMN IF EXISTS autopilot_error_debug,
    DROP COLUMN IF EXISTS autopilot_updated_at;
