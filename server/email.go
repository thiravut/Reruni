package main

// Email sender — wraps the Resend HTTP API behind a tiny interface so the
// reminder cron and password-reset paths share one code path.
//
// In V1 launch we ship a no-op fallback (log-only) so the server boots
// without RESEND_API_KEY set; once Pond provisions Resend, drop the key
// into env and the real path activates. No code change required.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

// EmailMessage is the canonical struct callers populate.
type EmailMessage struct {
	To      string
	Subject string
	HTML    string
	Text    string
}

// sendEmail dispatches based on env. Returns nil when no provider is
// configured (no-op + log) so callers don't have to special-case dev.
func sendEmail(msg EmailMessage) error {
	if msg.To == "" {
		return errors.New("sendEmail: empty To")
	}
	apiKey := getenv("RESEND_API_KEY", "")
	if apiKey == "" {
		log.Printf("email (stub, no RESEND_API_KEY): to=%s subj=%q", msg.To, msg.Subject)
		return nil
	}
	return sendViaResend(apiKey, msg)
}

func sendViaResend(apiKey string, msg EmailMessage) error {
	from := getenv("RESEND_FROM", "Reruni <hello@reruni.com>")
	payload := map[string]any{
		"from":    from,
		"to":      []string{msg.To},
		"subject": msg.Subject,
	}
	if msg.HTML != "" {
		payload["html"] = msg.HTML
	}
	if msg.Text != "" {
		payload["text"] = msg.Text
	}
	body, _ := json.Marshal(payload)

	req, err := http.NewRequest("POST", "https://api.resend.com/emails", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+apiKey)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		raw, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("resend %d: %s", resp.StatusCode, string(raw))
	}
	return nil
}
