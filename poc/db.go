package main

import (
	"database/sql"
	"time"
)

// Minimal schema for the standalone Smart Overlay POC.
// No users table, no owner_user_id, no FK juggling — this is a sandbox.
func initSchema(db *sql.DB) error {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS videos (
			id           INTEGER PRIMARY KEY AUTOINCREMENT,
			name         TEXT NOT NULL,
			filename     TEXT NOT NULL,
			size_bytes   INTEGER NOT NULL,
			created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
		);

		CREATE TABLE IF NOT EXISTS devices (
			id           TEXT PRIMARY KEY,
			name         TEXT,
			paired_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
			last_seen    DATETIME
		);

		CREATE TABLE IF NOT EXISTS pair_tokens (
			token        TEXT PRIMARY KEY,
			created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
			expires_at   DATETIME NOT NULL
		);
	`)
	return err
}

type Video struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	Filename  string    `json:"filename"`
	SizeBytes int64     `json:"size_bytes"`
	CreatedAt time.Time `json:"created_at"`
	URL       string    `json:"url"`
}

type Device struct {
	ID       string     `json:"id"`
	Name     string     `json:"name"`
	PairedAt time.Time  `json:"paired_at"`
	LastSeen *time.Time `json:"last_seen"`
	Online   bool       `json:"online"`
}
