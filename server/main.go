package main

import (
	"database/sql"
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

var (
	db         *DB
	uploadsDir string
	httpAddr   string
)

func main() {
	flag.StringVar(&httpAddr, "addr", getenv("PORT_ADDR", ":8080"), "HTTP listen address")
	flag.StringVar(&uploadsDir, "uploads", getenv("UPLOAD_DIR", "./uploads"), "Directory for uploaded videos")
	dsn := flag.String("db", getenv("DATABASE_URL", ""), "Postgres connection string (or set DATABASE_URL)")
	flag.Parse()

	if *dsn == "" {
		log.Fatal("DATABASE_URL is required (e.g. postgres://rerun:secret@127.0.0.1:5432/rerun?sslmode=disable)")
	}

	if err := os.MkdirAll(uploadsDir, 0o755); err != nil {
		log.Fatalf("create uploads dir: %v", err)
	}
	absUploads, err := filepath.Abs(uploadsDir)
	if err != nil {
		log.Fatalf("resolve uploads dir: %v", err)
	}
	uploadsDir = absUploads

	raw, err := sql.Open("pgx", *dsn)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer raw.Close()

	raw.SetMaxOpenConns(25)
	raw.SetMaxIdleConns(5)
	raw.SetConnMaxLifetime(30 * time.Minute)

	if err := raw.Ping(); err != nil {
		log.Fatalf("db ping: %v", err)
	}

	if err := runMigrations(raw); err != nil {
		log.Fatalf("run migrations: %v", err)
	}

	db = &DB{DB: raw}

	bootstrapAdmin()
	initStripe()

	mux := buildRouter()

	handler := corsMiddleware(loggingMiddleware(mux))

	log.Printf("TiktokRerun server")
	log.Printf("  http        %s", httpAddr)
	log.Printf("  uploads dir %s", uploadsDir)
	log.Printf("  db          (postgres)")
	log.Fatal(http.ListenAndServe(httpAddr, handler))
}

// buildRouter is split out so tests can construct an http.Handler without
// starting the server.
func buildRouter() *http.ServeMux {
	mux := http.NewServeMux()

	// -------------------------------------------------------------------------
	// Health — public, no DB ping; deploy script polls this after restart.
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":"ok"}`))
	})

	// -------------------------------------------------------------------------
	// Auth (public, except logout/me which need a session)
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/auth/signup", signupHandler)
	mux.HandleFunc("POST /api/auth/login", loginHandler)
	mux.HandleFunc("POST /api/auth/logout", requireAuth(logoutHandler))
	mux.HandleFunc("GET /api/auth/me", requireAuth(meHandler))
	mux.HandleFunc("POST /api/auth/change-password", requireAuth(changePasswordHandler))

	// -------------------------------------------------------------------------
	// Billing — Stripe-backed (api-contract §2.7b)
	// Webhook MUST register raw — handler reads io.ReadAll() before any parse
	// to keep the Stripe-Signature HMAC valid. All other billing endpoints
	// require an authenticated session but do NOT require an active sub.
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/billing/webhook", stripeWebhookHandler)
	mux.HandleFunc("GET /api/billing/subscription", requireAuth(getSubscriptionHandler))
	mux.HandleFunc("GET /api/billing/tiers", requireAuth(listTiersHandler))
	mux.HandleFunc("POST /api/billing/checkout-session", requireAuth(createCheckoutSessionHandler))
	mux.HandleFunc("POST /api/billing/portal-session", requireAuth(createPortalSessionHandler))
	mux.HandleFunc("POST /api/billing/cancel", requireAuth(cancelSubscriptionHandler))
	mux.HandleFunc("POST /api/billing/sync", requireAuth(syncSubscriptionHandler))

	// -------------------------------------------------------------------------
	// Videos — feature endpoints require active subscription
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/videos", requireActiveSubscription(listVideosHandler))
	mux.HandleFunc("POST /api/videos", requireActiveSubscription(uploadVideoHandler))
	mux.HandleFunc("DELETE /api/videos/{id}", requireActiveSubscription(deleteVideoHandler))

	// -------------------------------------------------------------------------
	// Devices
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/devices", requireActiveSubscription(listDevicesHandler))
	mux.HandleFunc("PATCH /api/devices/{id}", requireActiveSubscription(patchDeviceHandler))
	mux.HandleFunc("DELETE /api/devices/{id}", requireActiveSubscription(deleteDeviceHandler))

	// -------------------------------------------------------------------------
	// TikTok account pool (per-device burner accounts for rotation)
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/devices/{id}/accounts", requireActiveSubscription(listDeviceAccountsHandler))
	mux.HandleFunc("POST /api/devices/{id}/accounts", requireActiveSubscription(addDeviceAccountHandler))
	mux.HandleFunc("PATCH /api/devices/{id}/accounts/{aid}", requireActiveSubscription(patchDeviceAccountHandler))
	mux.HandleFunc("DELETE /api/devices/{id}/accounts/{aid}", requireActiveSubscription(deleteDeviceAccountHandler))
	mux.HandleFunc("POST /api/devices/{id}/accounts/rotate", requireActiveSubscription(rotateDeviceAccountHandler))

	// -------------------------------------------------------------------------
	// Pair
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/pair/token", requireActiveSubscription(createPairTokenHandler))
	mux.HandleFunc("GET /api/pair/qr", pairQRHandler)

	// -------------------------------------------------------------------------
	// Lives
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/lives/start", requireActiveSubscription(startLiveHandler))
	mux.HandleFunc("POST /api/lives/{device_id}/stop", requireActiveSubscription(stopLiveHandler))
	mux.HandleFunc("POST /api/lives/{device_id}/switch-video", requireActiveSubscription(switchVideoHandler))
	mux.HandleFunc("POST /api/lives/{device_id}/restart", requireActiveSubscription(restartLiveHandler))
	mux.HandleFunc("PATCH /api/lives/{device_id}/volume", requireActiveSubscription(volumeHandler))
	mux.HandleFunc("GET /api/lives/active", requireActiveSubscription(activeLivesHandler))
	mux.HandleFunc("GET /api/lives/history", requireActiveSubscription(liveHistoryHandler))

	// -------------------------------------------------------------------------
	// Products
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/lives/{device_id}/products/pin", requireActiveSubscription(pinProductHandler))
	mux.HandleFunc("POST /api/lives/{device_id}/products/unpin", requireActiveSubscription(unpinProductHandler))

	// -------------------------------------------------------------------------
	// Banners
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/banners", requireActiveSubscription(listBannersHandler))
	mux.HandleFunc("POST /api/banners", requireActiveSubscription(createBannerHandler))
	mux.HandleFunc("PATCH /api/banners/{id}", requireActiveSubscription(patchBannerHandler))
	mux.HandleFunc("DELETE /api/banners/{id}", requireActiveSubscription(deleteBannerHandler))

	// -------------------------------------------------------------------------
	// Commands
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/commands/{id}", requireActiveSubscription(getCommandHandler))

	// -------------------------------------------------------------------------
	// Admin auth (backoffice-scoped — issues/reads tkr_admin_session)
	// Login is public; logout/me/change-password require an admin session.
	// -------------------------------------------------------------------------
	mux.HandleFunc("POST /api/admin/auth/login", adminLoginHandler)
	mux.HandleFunc("POST /api/admin/auth/logout", requireAdmin(adminLogoutHandler))
	mux.HandleFunc("GET /api/admin/auth/me", requireAdmin(adminMeHandler))
	mux.HandleFunc("POST /api/admin/auth/change-password", requireAdmin(adminChangePasswordHandler))

	// -------------------------------------------------------------------------
	// Admin
	// -------------------------------------------------------------------------
	mux.HandleFunc("GET /api/admin/users", requireAdmin(adminListUsersHandler))
	mux.HandleFunc("PATCH /api/admin/users/{id}/role", requireAdmin(adminPatchUserRoleHandler))
	mux.HandleFunc("POST /api/admin/users/{id}/reset-password", requireAdmin(adminResetPasswordHandler))
	mux.HandleFunc("DELETE /api/admin/users/{id}", requireAdmin(adminDeleteUserHandler))
	mux.HandleFunc("GET /api/admin/devices", requireAdmin(adminListDevicesHandler))
	mux.HandleFunc("POST /api/admin/devices/{id}/disconnect", requireAdmin(adminDisconnectDeviceHandler))
	mux.HandleFunc("GET /api/admin/videos", requireAdmin(adminListVideosHandler))
	mux.HandleFunc("GET /api/admin/metrics", requireAdmin(adminMetricsHandler))
	mux.HandleFunc("GET /api/admin/lives", requireAdmin(adminListLivesHandler))
	mux.HandleFunc("POST /api/admin/lives/{id}/force-stop", requireAdmin(adminForceStopLiveHandler))
	mux.HandleFunc("GET /api/admin/subscriptions", requireAdmin(adminListSubscriptionsHandler))
	mux.HandleFunc("POST /api/admin/subscriptions/{user_id}/recheck", requireAdmin(adminRecheckSubscriptionHandler))

	// -------------------------------------------------------------------------
	// WebSocket
	// -------------------------------------------------------------------------
	mux.HandleFunc("/ws/device", deviceWsHandler)
	mux.HandleFunc("/ws/portal", portalWsHandler)

	// -------------------------------------------------------------------------
	// Static
	// -------------------------------------------------------------------------
	mux.Handle("/uploads/", http.StripPrefix("/uploads/", http.FileServer(http.Dir(uploadsDir))))
	// Root and other paths are intentionally unmounted — the portal SPA will
	// take over /, and the POC dashboard lives on its own port (see /poc).

	return mux
}
