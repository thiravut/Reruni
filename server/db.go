package main

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/pressly/goose/v3"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// DB wraps *sql.DB so we can keep writing `?` placeholders everywhere in the
// codebase while pgx requires `$1, $2, ...`. The rebind step is cheap and only
// runs on cache misses inside the driver — keep query strings constant.
type DB struct {
	*sql.DB
}

func (d *DB) Exec(query string, args ...any) (sql.Result, error) {
	return d.DB.Exec(rebind(query), args...)
}

func (d *DB) Query(query string, args ...any) (*sql.Rows, error) {
	return d.DB.Query(rebind(query), args...)
}

func (d *DB) QueryRow(query string, args ...any) *sql.Row {
	return d.DB.QueryRow(rebind(query), args...)
}

func (d *DB) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	return d.DB.ExecContext(ctx, rebind(query), args...)
}

func (d *DB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	return d.DB.QueryContext(ctx, rebind(query), args...)
}

func (d *DB) QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	return d.DB.QueryRowContext(ctx, rebind(query), args...)
}

// rebind converts MySQL/SQLite-style `?` placeholders to Postgres `$N`.
// Ignores `?` inside single-quoted string literals.
func rebind(q string) string {
	if !strings.ContainsRune(q, '?') {
		return q
	}
	var b strings.Builder
	b.Grow(len(q) + 8)
	inStr := false
	n := 0
	for i := 0; i < len(q); i++ {
		c := q[i]
		if c == '\'' {
			// handle '' escape inside string literal
			if inStr && i+1 < len(q) && q[i+1] == '\'' {
				b.WriteByte(c)
				b.WriteByte(c)
				i++
				continue
			}
			inStr = !inStr
			b.WriteByte(c)
			continue
		}
		if c == '?' && !inStr {
			n++
			b.WriteByte('$')
			b.WriteString(strconv.Itoa(n))
			continue
		}
		b.WriteByte(c)
	}
	return b.String()
}

// runMigrations applies all pending goose migrations under migrations/.
// Safe to run on every boot — goose tracks applied versions in goose_db_version.
func runMigrations(rawDB *sql.DB) error {
	goose.SetBaseFS(migrationsFS)
	if err := goose.SetDialect("postgres"); err != nil {
		return fmt.Errorf("goose set dialect: %w", err)
	}
	if err := goose.UpContext(context.Background(), rawDB, "migrations"); err != nil {
		return fmt.Errorf("goose up: %w", err)
	}
	return nil
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
	// Onboarding wizard state. One of:
	//   welcome | pick_quantity | payment | install_apk
	//   pair_device | first_video | complete
	// See docs/planning-artifacts/prd-v1-launch.md §3.12.
	OnboardingStep string `json:"onboarding_step,omitempty"`
	// Device quota (= subscriptions.device_quota) and current paired count.
	// Both 0 when user has no active subscription.
	DeviceQuota   int `json:"device_quota"`
	DevicesPaired int `json:"devices_paired"`
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
	// DeviceQuota mirrors Stripe's subscription.items[0].quantity — the
	// number of devices the customer has paid for under flat per-device
	// pricing. Stripe is canonical; we mirror so quota checks stay local.
	DeviceQuota          int        `json:"device_quota"`
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
	ID                string          `json:"id"`
	Name              string          `json:"name"`
	OwnerUserID       int64           `json:"owner_user_id,omitempty"`
	Status            string          `json:"status"`
	CurrentVideoID    *int64          `json:"current_video_id,omitempty"`
	CurrentPinnedSKU  *string         `json:"current_pinned_sku,omitempty"`
	SkuTier           string          `json:"sku_tier,omitempty"`
	CurrentAccountID  *int64          `json:"current_account_id,omitempty"`
	PairedAt          time.Time       `json:"paired_at"`
	LastSeen          *time.Time      `json:"last_seen_at,omitempty"`
	Online            bool            `json:"online,omitempty"`
	Caps              json.RawMessage `json:"caps,omitempty"`
	CapsReportedAt    *time.Time      `json:"caps_reported_at,omitempty"`
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
	Status        string     `json:"status"` // active | cooldown | banned | disabled
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
	ID          int64      `json:"id"`
	DeviceID    string     `json:"device_id"`
	OwnerUserID int64      `json:"owner_user_id,omitempty"`
	VideoID     *int64     `json:"video_id,omitempty"`
	Title       string     `json:"title,omitempty"`
	Caption     string     `json:"caption,omitempty"`
	Hashtags    []string   `json:"hashtags,omitempty"`
	PinnedSKU   string     `json:"pinned_sku,omitempty"`
	StartedAt   time.Time  `json:"started_at"`
	EndedAt     *time.Time `json:"ended_at,omitempty"`
	EndReason   string     `json:"end_reason,omitempty"`
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
