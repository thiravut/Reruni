package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const ffmpegConcatTimeout = 15 * time.Minute

type probedInput struct {
	hasAudio bool
	duration float64 // seconds; only populated when hasAudio is false
}

// probeInput checks whether the file has an audio stream. When it does not,
// the file's duration is returned so the caller can synthesize silence of
// matching length for the concat filter.
func probeInput(ctx context.Context, path string) (probedInput, error) {
	audioOut, err := exec.CommandContext(ctx, "ffprobe",
		"-v", "error",
		"-select_streams", "a",
		"-show_entries", "stream=codec_type",
		"-of", "csv=p=0",
		path,
	).Output()
	if err != nil {
		return probedInput{}, fmt.Errorf("ffprobe audio: %w", err)
	}
	if strings.TrimSpace(string(audioOut)) != "" {
		return probedInput{hasAudio: true}, nil
	}

	durOut, err := exec.CommandContext(ctx, "ffprobe",
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=nokey=1:noprint_wrappers=1",
		path,
	).Output()
	if err != nil {
		return probedInput{}, fmt.Errorf("ffprobe duration: %w", err)
	}
	d, err := strconv.ParseFloat(strings.TrimSpace(string(durOut)), 64)
	if err != nil {
		return probedInput{}, fmt.Errorf("parse duration %q: %w", string(durOut), err)
	}
	return probedInput{duration: d}, nil
}

// runFFmpegConcat re-encodes inputs into a single MP4 at outputPath using
// the ffmpeg concat filter. Re-encode (rather than stream-copy) means
// inputs can have mismatched codecs/resolutions/sample-rates — the filter
// normalizes everything to a single libx264/aac stream.
//
// Inputs without an audio track get silent audio synthesized in-filter so
// concat=a=1 still has N audio streams to splice.
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

	probes := make([]probedInput, len(inputs))
	for i, in := range inputs {
		p, err := probeInput(ctx, in)
		if err != nil {
			return fmt.Errorf("probe input %d: %w", i, err)
		}
		probes[i] = p
	}

	args := []string{"-y", "-loglevel", "error"}
	for _, in := range inputs {
		args = append(args, "-i", in)
	}

	var filter strings.Builder
	for i, p := range probes {
		if !p.hasAudio {
			fmt.Fprintf(&filter, "anullsrc=channel_layout=stereo:sample_rate=44100:d=%.3f[a%d];", p.duration, i)
		}
	}
	for i, p := range probes {
		fmt.Fprintf(&filter, "[%d:v:0]", i)
		if p.hasAudio {
			fmt.Fprintf(&filter, "[%d:a:0]", i)
		} else {
			fmt.Fprintf(&filter, "[a%d]", i)
		}
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
