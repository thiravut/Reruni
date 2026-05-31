-- +goose Up
-- Server-driven automation scripts shipped to the Reruni app at runtime.
-- The full script lives in the JSONB column; `version` + `min_tiktok_version`
-- are duplicated as columns so we can list/filter without parsing.
--
-- The client (mobile/app, ScriptStore) ships a baseline copy of each script
-- bundled in res/raw — the row in this table overrides that bundle the next
-- time the device launches. Missing rows return 404; the device keeps
-- running on the bundled baseline.
CREATE TABLE automation_scripts (
    name               TEXT PRIMARY KEY,
    version            INTEGER     NOT NULL DEFAULT 1,
    min_tiktok_version TEXT        NOT NULL DEFAULT '',
    script             JSONB       NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- +goose Down
DROP TABLE automation_scripts;
