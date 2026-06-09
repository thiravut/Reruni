package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// AAC-over-WebSocket streamer — server-side port of `tools/aac-server.py`.
//
// Reruni controller writes a per-session URL into the mobile vcam module's
// override file. The patched TikTok process connects on Go LIVE; this handler
// streams pre-extracted AAC access units (one MP4 frame's worth = 1024
// samples = 21.33 ms) over a binary WS at real-time pace. The vcam module's
// `aacEncEncode` PLT hook substitutes its own encoder output with these
// bytes, bypassing TikTok's voice DSP that would otherwise mangle music.
//
// Wire protocol (matches `Mp4GWsClient.kt::handleFrame` exactly):
//
//   +----------------+----------------+--------+------------------+
//   | u32 BE         | u64 BE         | u8     | bytes            |
//   | payload_length | pts_microsecs  | kind   | raw AAC AU       |
//   +----------------+----------------+--------+------------------+
//
//   kind = 0x01 audio AAC
//
// URL: `wss://<host>/ws/aac?token=<token>`. Token is single-use, generated
// by `registerAACToken` in [startLiveHandler] and stored in an in-memory map
// (no DB column needed — tokens are ephemeral per LIVE session).

const aacWsKindAudio byte = 0x01

// 1024 samples per AAC LC frame at 48 kHz = 21.333... ms per frame.
const aacFrameIntervalNs = int64(1024) * 1_000_000_000 / 48000

// AAC-LC encoder params — must match what TikTok's encoder produces so
// viewer-side decoders accept our substitute frames. Empirically verified
// (see `docs/vcam-findings/option-g-aac-inject.md`).
var aacEncoderArgs = []string{
	"-vn",
	"-c:a", "aac",
	"-profile:a", "aac_low",
	"-b:a", "256k",
	"-ar", "48000",
	"-ac", "2",
	"-f", "adts",
	"pipe:1",
}

type aacSession struct {
	filePath  string
	expiresAt time.Time
}

var (
	aacTokens   = map[string]aacSession{}
	aacTokensMu sync.Mutex
)

// registerAACToken stores filePath against a fresh 32-byte hex token. The
// token is valid for one hour — long enough for Reruni controller to
// receive start_live, write the override file, launch TikTok, and have the
// vcam module connect. After expiry the token is GC'd lazily on the next
// lookup.
func registerAACToken(filePath string) (string, error) {
	abs, err := filepath.Abs(filePath)
	if err != nil {
		return "", fmt.Errorf("abs path: %w", err)
	}
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("token rand: %w", err)
	}
	token := hex.EncodeToString(raw[:])
	aacTokensMu.Lock()
	defer aacTokensMu.Unlock()
	// Opportunistic GC — drop any token that expired before this caller.
	now := time.Now()
	for t, s := range aacTokens {
		if now.After(s.expiresAt) {
			delete(aacTokens, t)
		}
	}
	aacTokens[token] = aacSession{filePath: abs, expiresAt: now.Add(time.Hour)}
	return token, nil
}

func resolveAACToken(token string) (string, bool) {
	aacTokensMu.Lock()
	defer aacTokensMu.Unlock()
	s, ok := aacTokens[token]
	if !ok {
		return "", false
	}
	if time.Now().After(s.expiresAt) {
		delete(aacTokens, token)
		return "", false
	}
	return s.filePath, true
}

// aacWsUpgrader is intentionally permissive — the only legitimate caller is
// the patched TikTok process on the operator's device, authenticated via
// the URL token. CORS isn't applicable to a WS-from-mobile connection.
var aacWsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

func handleAACWebSocket(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}
	filePath, ok := resolveAACToken(token)
	if !ok {
		http.Error(w, "invalid or expired token", http.StatusUnauthorized)
		return
	}
	conn, err := aacWsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("aac_ws: upgrade failed: %v", err)
		return
	}
	defer conn.Close()
	log.Printf("aac_ws: client %s connected; streaming %s", conn.RemoteAddr(), filePath)
	streamAACToClient(conn, filePath)
}

// streamAACToClient runs the file through ffmpeg → ADTS, splits ADTS frames
// out, then loops the resulting AU list to the WS at real-time pace. The
// loop is what lets a 10-second source MP4 drive a multi-hour LIVE.
//
// Mobile can send a text WS message "reset" to restart streaming from
// frame 0 — used at first encoder fire so audio (which is "PC's current
// frame at that real time") begins at MP4 t=0, matching the freshly-
// reset video producer. Without this, the offset between when PC starts
// streaming (WS connect) and when TikTok's broadcast encoder first fires
// is baked into A/V sync forever.
func streamAACToClient(conn *websocket.Conn, filePath string) {
	frames, err := extractAACFrames(filePath)
	if err != nil {
		log.Printf("aac_ws: extract failed for %s: %v", filePath, err)
		_ = conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseInternalServerErr,
				"aac extract failed"),
			time.Now().Add(time.Second),
		)
		return
	}
	if len(frames) == 0 {
		log.Printf("aac_ws: no AAC frames extracted from %s", filePath)
		return
	}
	log.Printf("aac_ws: %d frames cached; streaming at %.2fms/frame",
		len(frames), float64(aacFrameIntervalNs)/1e6)

	// Reset channel — mobile's audio_hook fires this when the broadcast
	// encoder first runs. Server reacts by resetting `start` and `sent`
	// so the very next frame is MP4 audio frame 0.
	resetCh := make(chan struct{}, 4)

	// Drain ping/control frames + detect disconnect. Also watch for the
	// "reset" text message from the client.
	conn.SetReadDeadline(time.Now().Add(48 * time.Hour))
	doneRead := make(chan struct{})
	go func() {
		defer close(doneRead)
		for {
			msgType, payload, err := conn.ReadMessage()
			if err != nil {
				return
			}
			if msgType == websocket.TextMessage && string(payload) == "reset" {
				select {
				case resetCh <- struct{}{}:
				default:
				}
			}
		}
	}()

	sent := int64(0)
	start := time.Now()
	headerBuf := make([]byte, 4+8+1)
	frameIdx := 0
	for {
		select {
		case <-doneRead:
			log.Printf("aac_ws: client disconnected after %d frames", sent)
			return
		case <-resetCh:
			log.Printf("aac_ws: reset received — restarting stream from frame 0 (was sent=%d)", sent)
			sent = 0
			start = time.Now()
			frameIdx = 0
		default:
		}
		if frameIdx >= len(frames) {
			frameIdx = 0
		}
		au := frames[frameIdx]
		frameIdx++

		ptsUs := uint64(sent) * 1024 * 1_000_000 / 48000
		binary.BigEndian.PutUint32(headerBuf[0:4], uint32(len(au)))
		binary.BigEndian.PutUint64(headerBuf[4:12], ptsUs)
		headerBuf[12] = aacWsKindAudio
		if err := conn.WriteMessage(websocket.BinaryMessage,
			append(append([]byte{}, headerBuf...), au...)); err != nil {
			log.Printf("aac_ws: write failed at sent=%d: %v", sent, err)
			return
		}
		sent++
		// Real-time pacing — if we're ahead, sleep; if behind (network
		// latency burst, etc.), skip the sleep and catch up.
		expected := start.Add(time.Duration(sent) * time.Duration(aacFrameIntervalNs))
		if delay := time.Until(expected); delay > 0 {
			// Wake on reset too so we don't sleep past it.
			select {
			case <-time.After(delay):
			case <-resetCh:
				log.Printf("aac_ws: reset received mid-sleep — restarting (was sent=%d)", sent)
				sent = 0
				start = time.Now()
				frameIdx = 0
			case <-doneRead:
				return
			}
		}
		if sent%500 == 0 {
			drift := time.Since(expected)
			log.Printf("aac_ws: sent=%d drift=%+.3fs", sent, drift.Seconds())
		}
	}
}

// extractAACFrames runs the source file through ffmpeg's native AAC encoder
// into ADTS, then parses out the raw access units (stripping each frame's
// 7-byte ADTS header). Returns one byte slice per AU. Decode + re-encode
// path is intentional: source files may have arbitrary audio params, the
// encoder normalises everything to 48000/2/256k AAC-LC.
func extractAACFrames(filePath string) ([][]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	args := append([]string{"-loglevel", "error", "-i", filePath}, aacEncoderArgs...)
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("stdout pipe: %w", err)
	}
	var stderrBuf strings.Builder
	cmd.Stderr = &stderrBuf
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("ffmpeg start: %w", err)
	}
	frames, parseErr := parseADTSStream(stdout)
	waitErr := cmd.Wait()
	if parseErr != nil && parseErr != io.EOF {
		return nil, fmt.Errorf("parse ADTS: %w (ffmpeg stderr: %s)",
			parseErr, stderrBuf.String())
	}
	if waitErr != nil {
		return nil, fmt.Errorf("ffmpeg wait: %w (stderr: %s)",
			waitErr, stderrBuf.String())
	}
	return frames, nil
}

// parseADTSStream reads ADTS-framed AAC and returns the raw AU per frame.
// ADTS frame layout (no CRC variant, 7 bytes):
//
//	bits  0..11  syncword 0xFFF
//	bits 12..15  MPEG version (1) + layer (00) + protection_absent
//	byte 2       profile (2) + sampling_freq_index (4) + private + channel hi
//	bytes 3..5   channel lo (2) + originality+home+copyright (4) +
//	             frame_length (13) + buffer_fullness hi (5)
//	byte 6       buffer_fullness lo + number_of_raw_data_blocks
//
// frame_length spans bits 30..42 (i.e. byte3[1..0], byte4 all, byte5[7..5]).
func parseADTSStream(r io.Reader) ([][]byte, error) {
	var (
		buf    [8192]byte
		carry  []byte
		frames [][]byte
	)
	for {
		n, err := r.Read(buf[:])
		if n > 0 {
			carry = append(carry, buf[:n]...)
			for len(carry) >= 7 {
				if carry[0] != 0xFF || carry[1]&0xF0 != 0xF0 {
					// Resync — skip a byte.
					carry = carry[1:]
					continue
				}
				protectionAbsent := carry[1] & 0x01
				headerLen := 7
				if protectionAbsent == 0 {
					headerLen = 9
				}
				frameLen := (int(carry[3]&0x03) << 11) |
					(int(carry[4]) << 3) |
					(int(carry[5]) >> 5)
				if frameLen <= headerLen {
					// Malformed — drop a byte and resync.
					carry = carry[1:]
					continue
				}
				if len(carry) < frameLen {
					break
				}
				au := make([]byte, frameLen-headerLen)
				copy(au, carry[headerLen:frameLen])
				frames = append(frames, au)
				carry = carry[frameLen:]
			}
		}
		if err == io.EOF {
			return frames, nil
		}
		if err != nil {
			return frames, err
		}
	}
}
