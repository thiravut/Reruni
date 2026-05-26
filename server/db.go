package main

import (
	"database/sql"
	"log"
	"strings"
	"time"
)

// initSchema creates the tables and applies any additive migrations.
// All DDL is idempotent: CREATE TABLE IF NOT EXISTS, and ALTER TABLE ADD COLUMN
// statements swallow "duplicate column" errors so subsequent runs are safe.
func initSchema(db *sql.DB) error {
	// Base CREATE TABLE — safe to re-run.
	stmts := []string{
		// Legacy POC tables (kept as-is for backward compat).
		`CREATE TABLE IF NOT EXISTS videos (
			id           INTEGER PRIMARY KEY AUTOINCREMENT,
			name         TEXT NOT NULL,
			filename     TEXT NOT NULL,
			size_bytes   INTEGER NOT NULL,
			created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS devices (
			id           TEXT PRIMARY KEY,
			name         TEXT,
			paired_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
			last_seen    DATETIME
		)`,

		// New v1 tables per tech-spec.md §5
		`CREATE TABLE IF NOT EXISTS users (
			id                    INTEGER PRIMARY KEY AUTOINCREMENT,
			email                 TEXT NOT NULL UNIQUE,
			password_hash         TEXT NOT NULL,
			role                  TEXT NOT NULL DEFAULT 'user',
			must_change_password  BOOLEAN NOT NULL DEFAULT 0,
			created_at            DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS sessions (
			token       TEXT PRIMARY KEY,
			user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			scope       TEXT NOT NULL DEFAULT 'portal',
			created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
			expires_at  DATETIME NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)`,
		// idx_sessions_scope is created AFTER addCol below so it works on legacy DBs.

		`CREATE TABLE IF NOT EXISTS pair_tokens (
			token         TEXT PRIMARY KEY,
			owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
			expires_at    DATETIME NOT NULL,
			used_at       DATETIME
		)`,
		`CREATE INDEX IF NOT EXISTS idx_pair_tokens_owner ON pair_tokens(owner_user_id)`,

		`CREATE TABLE IF NOT EXISTS live_sessions (
			id              INTEGER PRIMARY KEY AUTOINCREMENT,
			device_id       TEXT NOT NULL,
			owner_user_id   INTEGER NOT NULL REFERENCES users(id),
			video_id        INTEGER REFERENCES videos(id),
			title           TEXT,
			caption         TEXT,
			hashtags        TEXT,
			pinned_sku      TEXT,
			started_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
			ended_at        DATETIME,
			end_reason      TEXT
		)`,
		`CREATE INDEX IF NOT EXISTS idx_lives_device ON live_sessions(device_id)`,
		`CREATE INDEX IF NOT EXISTS idx_lives_owner  ON live_sessions(owner_user_id)`,

		`CREATE TABLE IF NOT EXISTS banners (
			id              INTEGER PRIMARY KEY AUTOINCREMENT,
			owner_user_id   INTEGER NOT NULL REFERENCES users(id),
			video_id        INTEGER REFERENCES videos(id),
			live_session_id INTEGER REFERENCES live_sessions(id),
			slot            TEXT NOT NULL,
			text            TEXT NOT NULL,
			bg_color        TEXT NOT NULL DEFAULT '#000000',
			text_color      TEXT NOT NULL DEFAULT '#FFFFFF',
			font_size       TEXT NOT NULL DEFAULT 'M',
			deadline        DATETIME,
			created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE INDEX IF NOT EXISTS idx_banners_video ON banners(video_id)`,
		`CREATE INDEX IF NOT EXISTS idx_banners_live  ON banners(live_session_id)`,

		`CREATE TABLE IF NOT EXISTS commands (
			id              TEXT PRIMARY KEY,
			owner_user_id   INTEGER NOT NULL REFERENCES users(id),
			device_id       TEXT NOT NULL,
			type            TEXT NOT NULL,
			payload_json    TEXT,
			issued_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
			ack_at          DATETIME,
			status          TEXT NOT NULL DEFAULT 'pending',
			error_code      TEXT,
			error_message   TEXT
		)`,
		`CREATE INDEX IF NOT EXISTS idx_commands_device ON commands(device_id)`,
		`CREATE INDEX IF NOT EXISTS idx_commands_owner  ON commands(owner_user_id)`,

		// Subscriptions — Stripe-backed billing (tech-spec §5).
		`CREATE TABLE IF NOT EXISTS subscriptions (
			id                      INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id                 INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			stripe_customer_id      TEXT NOT NULL,
			stripe_subscription_id  TEXT,
			stripe_price_id         TEXT,
			tier                    TEXT NOT NULL,
			status                  TEXT NOT NULL,
			current_period_start    DATETIME,
			current_period_end      DATETIME,
			cancel_at_period_end    BOOLEAN NOT NULL DEFAULT 0,
			created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
			updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_user      ON subscriptions(user_id)`,
		`CREATE INDEX        IF NOT EXISTS idx_subscriptions_stripe_sub ON subscriptions(stripe_subscription_id)`,

		// Stripe webhook event log — idempotency + audit.
		`CREATE TABLE IF NOT EXISTS stripe_events (
			id                INTEGER PRIMARY KEY AUTOINCREMENT,
			stripe_event_id   TEXT NOT NULL UNIQUE,
			event_type        TEXT NOT NULL,
			payload_json      TEXT,
			processed_at      DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,

		// TikTok account pool — burner accounts the operator has logged into on
		// a specific broadcast device. The autopilot rotates among these to
		// keep livestream throughput up when individual accounts get rate-
		// limited / banned. V1 Lite uses TikTok's built-in switcher (cap 3-5);
		// V3 Pro uses Magisk data-dir-swap to support much larger pools.
		//
		// snapshot_path: V3 only — where /data/data/<tiktok>/* is backed up so
		// the rotator can swap login state without going through TikTok's UI.
		// Empty/null for V1 accounts (just stored as metadata for the operator).
		`CREATE TABLE IF NOT EXISTS tiktok_accounts (
			id              INTEGER PRIMARY KEY AUTOINCREMENT,
			device_id       TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
			owner_user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			label           TEXT NOT NULL,
			username        TEXT,
			status          TEXT NOT NULL DEFAULT 'active',
			snapshot_path   TEXT,
			last_used_at    DATETIME,
			banned_at       DATETIME,
			captcha_count   INTEGER NOT NULL DEFAULT 0,
			notes           TEXT,
			created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_device ON tiktok_accounts(device_id)`,
		`CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_owner  ON tiktok_accounts(owner_user_id)`,
		`CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_status ON tiktok_accounts(status)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}

	// Additive migrations on legacy tables (error-tolerant).
	// users.must_change_password — forced password change after admin reset.
	addCol(db, "users", "must_change_password", "BOOLEAN NOT NULL DEFAULT 0")
	// sessions.scope — separates Portal ('portal') vs Backoffice ('admin') sessions
	// so two cookies can coexist for the same user in the same browser.
	addCol(db, "sessions", "scope", "TEXT NOT NULL DEFAULT 'portal'")
	addCol(db, "videos", "owner_user_id", "INTEGER NOT NULL DEFAULT 0")
	addCol(db, "videos", "duration_sec", "INTEGER")
	addCol(db, "videos", "size_bytes_v2", "INTEGER") // unused; placeholder ignored if already 'size_bytes' exists
	addCol(db, "devices", "owner_user_id", "INTEGER NOT NULL DEFAULT 0")
	addCol(db, "devices", "device_token", "TEXT")
	addCol(db, "devices", "status", "TEXT NOT NULL DEFAULT 'idle'")
	addCol(db, "devices", "current_video_id", "INTEGER")
	addCol(db, "devices", "current_pinned_sku", "TEXT")
	// SKU tier — 'v1_lite' = screen-share + Mobile Gaming + Smart Overlay;
	// 'v3_pro' = rooted device + Magisk VCam + Device camera Go LIVE + data-dir-swap rotation.
	addCol(db, "devices", "sku_tier", "TEXT NOT NULL DEFAULT 'v1_lite'")
	// Active TikTok account from tiktok_accounts pool (null when no rotation
	// has happened yet). Updated by the rotator after a successful switch.
	addCol(db, "devices", "current_account_id", "INTEGER")

	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_videos_owner  ON videos(owner_user_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_devices_owner ON devices(owner_user_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_devices_token ON devices(device_token)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_sessions_scope ON sessions(scope)`)

	return nil
}

// addCol attempts to add a column. SQLite returns "duplicate column name" if
// it already exists; that error is suppressed so init remains idempotent.
func addCol(db *sql.DB, table, column, decl string) {
	q := "ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl
	if _, err := db.Exec(q); err != nil {
		// silent on "duplicate column" — already migrated
		if !strings.Contains(err.Error(), "duplicate column") {
			log.Printf("schema warn: %s — %v", q, err)
		}
	}
}

// -----------------------------------------------------------------------------
// Domain types
// -----------------------------------------------------------------------------

type User struct {
	ID                 int64     `json:"id"`
	Email              string    `json:"email"`
	Role               string    `json:"role"`
	MustChangePassword bool      `json:"must_change_password"`
	CreatedAt          time.Time `json:"created_at"`
	// Billing — populated by enrichUserSubscription before serializing the
	// user to portal/backoffice. `none` when the user has no row at all.
	SubscriptionStatus string `json:"subscription_status,omitempty"`
	SubscriptionTier   string `json:"subscription_tier,omitempty"`
}

// Subscription mirrors the subscriptions table. Surfaced via /api/billing/subscription.
type Subscription struct {
	ID                   int64      `json:"id,omitempty"`
	UserID               int64      `json:"-"`
	StripeCustomerID     string     `json:"-"`
	StripeSubscriptionID string     `json:"stripe_subscription_id,omitempty"`
	StripePriceID        string     `json:"-"`
	Tier                 string     `json:"tier"`
	Status               string     `json:"status"`
	CurrentPeriodStart   *time.Time `json:"current_period_start,omitempty"`
	CurrentPeriodEnd     *time.Time `json:"current_period_end,omitempty"`
	CancelAtPeriodEnd    bool       `json:"cancel_at_period_end"`
	CreatedAt            time.Time  `json:"-"`
	UpdatedAt            time.Time  `json:"-"`
}

type Session struct {
	Token     string
	UserID    int64
	Scope     string // 'portal' | 'admin'
	CreatedAt time.Time
	ExpiresAt time.Time
}

type Video struct {
	ID          int64     `json:"id"`
	Name        string    `json:"name"`
	Filename    string    `json:"filename"`
	SizeBytes   int64     `json:"size_bytes"`
	DurationSec *int64    `json:"duration_sec,omitempty"`
	CreatedAt   time.Time `json:"uploaded_at"`
	URL         string    `json:"url,omitempty"`
}

type Device struct {
	ID                string     `json:"id"`
	Name              string     `json:"name"`
	OwnerUserID       int64      `json:"owner_user_id,omitempty"`
	Status            string     `json:"status"`
	CurrentVideoID    *int64     `json:"current_video_id,omitempty"`
	CurrentPinnedSKU  *string    `json:"current_pinned_sku,omitempty"`
	SkuTier           string     `json:"sku_tier,omitempty"`
	CurrentAccountID  *int64     `json:"current_account_id,omitempty"`
	PairedAt          time.Time  `json:"paired_at"`
	LastSeen          *time.Time `json:"last_seen_at,omitempty"`
	Online            bool       `json:"online,omitempty"`
}

// TikTokAccount is one burner account in a device's rotation pool.
//
// SnapshotPath is the absolute path on the broadcast phone where the
// /data/data/<tiktok>/* tree is backed up. V3 Pro uses it for sub-10s
// account swap; V1 Lite leaves it null and relies on TikTok's built-in
// account switcher.
type TikTokAccount struct {
	ID            int64      `json:"id"`
	DeviceID      string     `json:"device_id"`
	OwnerUserID   int64      `json:"-"`
	Label         string     `json:"label"`
	Username      *string    `json:"username,omitempty"`
	Status        string     `json:"status"`        // active | cooldown | banned | disabled
	SnapshotPath  *string    `json:"snapshot_path,omitempty"`
	LastUsedAt    *time.Time `json:"last_used_at,omitempty"`
	BannedAt      *time.Time `json:"banned_at,omitempty"`
	CaptchaCount  int        `json:"captcha_count"`
	Notes         *string    `json:"notes,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
}

type PairToken struct {
	Token       string    `json:"token"`
	OwnerUserID int64     `json:"-"`
	ExpiresAt   time.Time `json:"expires_at"`
	QrURL       string    `json:"qr_url"`
}

type LiveSession struct {
	ID               int64      `json:"id"`
	DeviceID         string     `json:"device_id"`
	OwnerUserID      int64      `json:"owner_user_id,omitempty"`
	VideoID          *int64     `json:"video_id,omitempty"`
	Title            string     `json:"title,omitempty"`
	Caption          string     `json:"caption,omitempty"`
	Hashtags         []string   `json:"hashtags,omitempty"`
	PinnedSKU        string     `json:"pinned_sku,omitempty"`
	StartedAt        time.Time  `json:"started_at"`
	EndedAt          *time.Time `json:"ended_at,omitempty"`
	EndReason        string     `json:"end_reason,omitempty"`
}

type Banner struct {
	ID            int64      `json:"id"`
	OwnerUserID   int64      `json:"-"`
	VideoID       *int64     `json:"video_id,omitempty"`
	LiveSessionID *int64     `json:"live_session_id,omitempty"`
	Slot          string     `json:"slot"`
	Text          string     `json:"text"`
	BgColor       string     `json:"bg_color"`
	TextColor     string     `json:"text_color"`
	FontSize      string     `json:"font_size"`
	Deadline      *time.Time `json:"deadline,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
}

type Command struct {
	ID           string     `json:"id"`
	OwnerUserID  int64      `json:"-"`
	DeviceID     string     `json:"device_id"`
	Type         string     `json:"type"`
	PayloadJSON  string     `json:"-"`
	IssuedAt     time.Time  `json:"issued_at"`
	AckAt        *time.Time `json:"ack_at,omitempty"`
	Status       string     `json:"status"`
	ErrorCode    *string    `json:"-"`
	ErrorMessage *string    `json:"-"`
}
