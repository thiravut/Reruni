package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"strings"
	"time"
)

const ffmpegConcatTimeout = 15 * time.Minute

// runFFmpegConcat re-encodes inputs into a single MP4 at outputPath using
// the ffmpeg concat filter. Re-encode (rather than stream-copy) means
// inputs can have mismatched codecs/resolutions/sample-rates — the filter
// normalizes everything to a single libx264/aac stream.
//
// Caller must clean up outputPath on error.
func runFFmpegConcat(ctx context.Context, inputs []string, outputPath string) error {
	if len(inputs) < 2 {
		return fmt.Errorf("need at least 2 inputs")
	}

	// Apply our own timeout on top of whatever the request context has so a
	// stalled ffmpeg can't pin a goroutine forever.
	ctx, cancel := context.WithTimeout(ctx, ffmpegConcatTimeout)
	defer cancel()

	args := []string{"-y", "-loglevel", "error"}
	for _, in := range inputs {
		args = append(args, "-i", in)
	}

	var filter strings.Builder
	for i := range inputs {
		fmt.Fprintf(&filter, "[%d:v:0][%d:a:0]", i, i)
	}
	fmt.Fprintf(&filter, "concat=n=%d:v=1:a=1[v][a]", len(inputs))

	args = append(args,
		"-filter_complex", filter.String(),
		"-map", "[v]", "-map", "[a]",
		"-c:v", "libx264", "-preset", "fast", "-crf", "23", "-pix_fmt", "yuv420p",
		"-c:a", "aac", "-b:a", "128k", "-ar", "44100",
		"-movflags", "+faststart",
		outputPath,
	)

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		log.Printf("ffmpeg concat failed: %v — output: %s", err, string(out))
		if ctx.Err() == context.DeadlineExceeded {
			return fmt.Errorf("ffmpeg timed out after %s", ffmpegConcatTimeout)
		}
		// First line of stderr is usually the useful one.
		first := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
		if first == "" {
			first = err.Error()
		}
		return fmt.Errorf("ffmpeg: %s", first)
	}
	return nil
}
