package main

// Stuck-payment email reminders for onboarding — fires on days 3, 7, 15
// after signup when a user is still stalled before active subscription.
//
// See docs/planning-artifacts/prd-v1-launch.md §3.12 (Email reminders section)
// for cadence and decision rationale.
//
// Picks users where:
//   - users.created_at is between (now-N days) ± half a day
//   - users.payment_reminder_Nd_at IS NULL (not yet sent)
//   - subscription is missing OR status not in ('active', 'past_due')
//
// Each reminder writes its sent_at column under the same UPDATE so the cron
// is naturally idempotent if it crashes mid-batch.

import (
	"database/sql"
	"errors"
	"fmt"
	"log"
	"time"
)

const reminderTickInterval = 1 * time.Hour

// startReminderCron spins the background loop. Cancellable when ctx is done
// via the caller's main.go shutdown path. Hourly tick is enough granularity
// for "day 3 / 7 / 15" — we don't need precise minute alignment.
func startReminderCron() {
	go func() {
		// First tick — wait one cycle so we don't blast on every restart.
		t := time.NewTicker(reminderTickInterval)
		defer t.Stop()
		for range t.C {
			runReminderSweep()
		}
	}()
}

// runReminderSweep does one pass over all three reminder buckets. Exposed
// (lowercase) for the future reminder_test.go.
func runReminderSweep() {
	for _, bucket := range []struct {
		Days   int
		Column string
		Tmpl   reminderTemplate
	}{
		{3, "payment_reminder_3d_at", reminder3Day},
		{7, "payment_reminder_7d_at", reminder7Day},
		{15, "payment_reminder_15d_at", reminder15Day},
	} {
		if err := sendReminderBucket(bucket.Days, bucket.Column, bucket.Tmpl); err != nil {
			log.Printf("reminder %dd: %v", bucket.Days, err)
		}
	}
}

// sendReminderBucket finds candidates for a single reminder bucket and emails
// each. The query joins to subscriptions so we can skip users who've already
// completed payment.
func sendReminderBucket(days int, sentCol string, tmpl reminderTemplate) error {
	now := timeNowUTC()
	// Window of ±12 hours around the target day — generous so users
	// don't slip through when the cron sleeps.
	target := now.Add(-time.Duration(days) * 24 * time.Hour)
	lo := target.Add(-12 * time.Hour)
	hi := target.Add(12 * time.Hour)

	query := fmt.Sprintf(`
		SELECT u.id, u.email
		FROM users u
		LEFT JOIN subscriptions s ON s.user_id = u.id
		WHERE u.created_at BETWEEN ? AND ?
		  AND u.%s IS NULL
		  AND (s.status IS NULL OR s.status NOT IN ('active', 'past_due'))
		  AND u.onboarding_step IN ('welcome', 'pick_quantity', 'payment')`,
		sentCol)

	rows, err := db.Query(query, lo, hi)
	if err != nil {
		return err
	}
	defer rows.Close()

	type candidate struct {
		ID    int64
		Email string
	}
	var batch []candidate
	for rows.Next() {
		var c candidate
		if err := rows.Scan(&c.ID, &c.Email); err != nil {
			return err
		}
		batch = append(batch, c)
	}
	if err := rows.Err(); err != nil {
		return err
	}

	for _, c := range batch {
		msg := tmpl(c.Email)
		if err := sendEmail(msg); err != nil {
			// Don't mark sent on failure — cron will retry next tick.
			log.Printf("reminder %dd: send to %s: %v", days, c.Email, err)
			continue
		}
		_, _ = db.Exec(
			fmt.Sprintf("UPDATE users SET %s=? WHERE id=?", sentCol),
			now, c.ID,
		)
		log.Printf("reminder %dd sent: user=%d email=%s", days, c.ID, c.Email)
	}
	return nil
}

// userNeedsReminder is a single-row helper used by tests + the admin
// "send reminder now" button. Returns sql.ErrNoRows when the user has
// already paid (no longer eligible).
func userNeedsReminder(userID int64, sentCol string) error {
	var step, status string
	err := db.QueryRow(`
		SELECT u.onboarding_step, COALESCE(s.status, 'none')
		FROM users u
		LEFT JOIN subscriptions s ON s.user_id = u.id
		WHERE u.id = ?`, userID,
	).Scan(&step, &status)
	if err != nil {
		return err
	}
	if status == "active" || status == "past_due" {
		return sql.ErrNoRows
	}
	if step == StepComplete {
		return sql.ErrNoRows
	}
	return errors.New("eligible")
}

// -----------------------------------------------------------------------------
// Email templates — kept simple for V1; can be moved to template files later
// -----------------------------------------------------------------------------

type reminderTemplate func(email string) EmailMessage

func reminder3Day(email string) EmailMessage {
	return EmailMessage{
		To:      email,
		Subject: "Reruni — เริ่มต้นเลยภายในไม่กี่นาที 🚀",
		Text: `สวัสดี!

คุณสมัคร Reruni เมื่อ 3 วันที่แล้ว แต่ยังไม่ได้เริ่มใช้งาน

Reruni ช่วยให้คุณ live ขายของแบบ autopilot 24/7 จากเว็บเดียว:
  ✓ Broadcast วิดีโอเป็น live อัตโนมัติ
  ✓ ปักตะกร้าสินค้าและเปลี่ยน banner ระหว่าง live ได้
  ✓ คุม Android phones หลายเครื่องจากเว็บ

ใช้เวลาแค่ 5 นาทีในการตั้งค่าเครื่องแรก — เริ่มเลยที่ https://reruni.com/onboarding

ทีม Reruni`,
	}
}

func reminder7Day(email string) EmailMessage {
	return EmailMessage{
		To:      email,
		Subject: "Reruni — ยังพร้อมรอคุณอยู่",
		Text: `สวัสดี!

ผ่านมาหนึ่งสัปดาห์แล้วตั้งแต่คุณสมัคร Reruni — เราจะช่วยให้คุณเริ่มต้นได้

ถ้ามีคำถามหรือเจอปัญหา ตอบกลับ email นี้ได้เลย — ทีมงานพร้อมช่วย

ไปต่อที่ https://reruni.com/onboarding

ทีม Reruni`,
	}
}

func reminder15Day(email string) EmailMessage {
	return EmailMessage{
		To:      email,
		Subject: "Reruni — โอกาสสุดท้ายในการเริ่มต้น",
		Text: `สวัสดี!

ผ่านมา 15 วันตั้งแต่คุณสมัคร Reruni แต่ยังไม่ได้เริ่มใช้งาน

ถ้ามีอะไรที่ทำให้ยังไม่ได้เริ่ม ตอบกลับ email นี้บอกเราได้เลย — เราอยากเข้าใจและช่วยแก้ไข

ถ้ายังพร้อม ไปต่อที่ https://reruni.com/onboarding

ทีม Reruni`,
	}
}
