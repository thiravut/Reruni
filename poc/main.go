// Standalone Smart Overlay POC server.
//
// Self-contained: own DB (./poc.db), own uploads dir (./uploads/), own
// WS hub at /ws/device, own /api/legacy/* HTTP API. No dependency on the
// production API server in ../server.
//
// Mobile devices pair with this server only — scan the QR served by
// /api/legacy/pair, the URL inside points at this server's host:port.
//
// Run: cd poc && go run .
package main

import (
	"database/sql"
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

var (
	db         *sql.DB
	uploadsDir string
)

func main() {
	addr := flag.String("addr", ":8090", "POC listen address")
	dbPath := flag.String("db", "./poc.db", "SQLite database path")
	uploadsFlag := flag.String("uploads", "./uploads", "Directory for uploaded videos")
	flag.Parse()

	if err := os.MkdirAll(*uploadsFlag, 0o755); err != nil {
		log.Fatalf("create uploads dir: %v", err)
	}
	abs, err := filepath.Abs(*uploadsFlag)
	if err != nil {
		log.Fatalf("resolve uploads dir: %v", err)
	}
	uploadsDir = abs

	db, err = sql.Open("sqlite", *dbPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()
	if err := initSchema(db); err != nil {
		log.Fatalf("init schema: %v", err)
	}

	mux := http.NewServeMux()

	// API
	mux.HandleFunc("POST /api/legacy/pair", createPairHandler)
	mux.HandleFunc("GET  /api/legacy/videos", listVideosHandler)
	mux.HandleFunc("POST /api/legacy/videos", uploadVideoHandler)
	mux.HandleFunc("DELETE /api/legacy/videos/{id}", deleteVideoHandler)
	mux.HandleFunc("GET  /api/legacy/devices", listDevicesHandler)
	mux.HandleFunc("POST /api/legacy/devices/{id}/play", playOnDeviceHandler)
	mux.HandleFunc("POST /api/legacy/devices/{id}/start-live", startLiveOnDeviceHandler)

	// WS hub
	mux.HandleFunc("/ws/device", deviceWsHandler)

	// Static files for the device (video downloads + dashboard)
	mux.Handle("/uploads/", http.StripPrefix("/uploads/", http.FileServer(http.Dir(uploadsDir))))
	mux.Handle("/", http.FileServer(http.Dir("./web")))

	log.Printf("Smart Overlay POC")
	log.Printf("  http        %s", *addr)
	log.Printf("  uploads dir %s", uploadsDir)
	log.Printf("  db          %s", *dbPath)
	log.Fatal(http.ListenAndServe(*addr, mux))
}
