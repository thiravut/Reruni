package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// scriptUploadLimit caps the JSON body a publish accepts. Scripts that
// can't fit comfortably here probably want a different distribution mechanism.
const scriptUploadLimit = 256 * 1024

// getScriptHandler serves the latest version of an automation script to the
// Reruni app. Endpoint: GET /api/scripts/{name}
//
// Returns the full JSON document stored in automation_scripts.script. The app
// caches the response and falls back to a bundled baseline in res/raw when
// the server is unreachable or the script is missing here (404), so this
// endpoint is intentionally lightweight: no auth, no schema validation —
// just a passthrough of operator-managed JSONB.
//
// Schema mirrors what ScriptRunner expects (see mobile/app/.../script):
//
//	{ "name": "...", "version": N, "min_tiktok_version": "x.y.z",
//	  "steps": [ {"op": "...", ...}, ... ] }
//
// Operators update scripts via direct SQL (or the admin tooling in
// server/admin.go) — there is no public POST endpoint, so a leaked device
// token can fetch scripts but cannot inject malicious ones.
func getScriptHandler(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if name == "" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "missing script name")
		return
	}

	var scriptJSON []byte
	err := db.QueryRow(
		`SELECT script FROM automation_scripts WHERE name = ?`,
		name,
	).Scan(&scriptJSON)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no script published under that name")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	// Write the JSONB body directly — no re-marshal, no envelope. The app
	// expects the raw document so ScriptRunner can JSONObject() it as-is.
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(scriptJSON)
}

// putScriptHandler is the operator-side admin entry point for publishing a new
// version of an automation script. Endpoint: PUT /api/scripts/{name}
//
// Body is the same JSON document the client receives back — server lifts
// version + min_tiktok_version out of the document for indexing but stores
// the whole payload verbatim.
//
// Auth: admin token (same gating as the rest of /api/admin). Without this
// the server still works — operators can edit the table directly with SQL —
// but a small HTTP entry point makes scripted publishes (CI, internal CLI)
// easier than shelling into the database.
func putScriptHandler(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if name == "" {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "missing script name")
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, scriptUploadLimit))
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "request body too large or unreadable: "+err.Error())
		return
	}

	var parsed struct {
		Name             string `json:"name"`
		Version          int    `json:"version"`
		MinTiktokVersion string `json:"min_tiktok_version"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT", "script JSON malformed: "+err.Error())
		return
	}
	if parsed.Name != name {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT",
			"script `name` field must match URL path")
		return
	}
	if parsed.Version <= 0 {
		writeError(w, http.StatusBadRequest, "INVALID_INPUT",
			"script `version` must be a positive integer")
		return
	}

	_, err = db.Exec(
		`INSERT INTO automation_scripts (name, version, min_tiktok_version, script, updated_at)
		 VALUES (?, ?, ?, ?, NOW())
		 ON CONFLICT (name) DO UPDATE
		   SET version = EXCLUDED.version,
		       min_tiktok_version = EXCLUDED.min_tiktok_version,
		       script = EXCLUDED.script,
		       updated_at = NOW()`,
		name, parsed.Version, parsed.MinTiktokVersion, body,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"name":               name,
		"version":            parsed.Version,
		"min_tiktok_version": parsed.MinTiktokVersion,
	})
}
