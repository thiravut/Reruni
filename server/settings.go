package main

import (
	"database/sql"
	"errors"
	"log"
)

// getSetting returns the stored value for key, or fallback if the row is
// missing. Unexpected errors are logged and treated as missing so a setting
// lookup never breaks the surrounding request path.
func getSetting(key, fallback string) string {
	var v string
	err := db.QueryRow(`SELECT value FROM app_settings WHERE key = ?`, key).Scan(&v)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			log.Printf("getSetting(%q): %v", key, err)
		}
		return fallback
	}
	return v
}

// setSetting upserts a row in app_settings. Empty value is allowed and means
// "clear" — callers (e.g. release pin clear) rely on getSetting falling back.
func setSetting(key, value string) error {
	_, err := db.Exec(`
		INSERT INTO app_settings (key, value, updated_at)
		VALUES (?, ?, NOW())
		ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = NOW()
	`, key, value)
	return err
}
