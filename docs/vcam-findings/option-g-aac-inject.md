# Option G — PC-encoded AAC injection (working solution)

**Status:** Working in dev (2026-06-09). Replaces the speaker-loopback
ceiling that v0.1.2 shipped under. Viewer-side audio is clean — no voice
DSP coloration, no multi-device acoustic cross-talk.

This is the implementation summary; the full investigation trail (8 failed
approaches that preceded this) lives in `phase3-audio-injection-deferred.md`.

---

## TL;DR

```
[PC running tools/aac-server.py]
   ffmpeg → AAC AU bytes (48 kHz stereo, ~256 kbps)
        ↓ WebSocket binary frames
[Mobile: Mp4GWsClient.kt]
        ↓ NativeAudioHook.pushAacFrame()
[Native AAC ring (256 slots in audio_hook.c)]
        ↓
[PLT hook on aacEncEncode in libvolcenginertc.so]
   1. TikTok's encoder runs normally
   2. Hook overwrites its output buffer with our PC AAC
   3. TikTok's transport sends our bytes
        ↓
[CDN → viewers hear PC's audio cleanly]
```

The trick is that voice DSP (libkryptonaudio + WebRTC APM) mangles audio
*upstream* of the encoder. By substituting at the encoder output, the
entire mangling chain is bypassed.

---

## Why earlier approaches failed

Documented in detail in `phase3-audio-injection-deferred.md`. The short
version:

| Approach                              | Why it failed                                          |
|---------------------------------------|--------------------------------------------------------|
| PCM injection into AudioRecord (v0.1.1) | Voice DSP runs after, mangles every non-silent PCM   |
| Speaker-loopback (v0.1.2)             | Works for single-device but multi-device cross-talk    |
| Lyrax in-LIVE music player            | `bytertc` engine not used in basic LIVE                |
| LyraxAudioPlayer via linkmic mode     | Engine not active without actual linkmic               |
| D-lite parallel AudioTrack            | LSPatch classloader isolation blocked subclass         |
| `rtmp_client_push_audio` PLT hook     | Exported but 0 internal callers in basic LIVE          |
| shadowhook for inline hooks           | SIGSEGV at `.init_array` on Samsung A15 Android 16     |
| Java `nativeEncoded` callback         | TikTok uses pure-native encoder, callback never fires  |

The breakthrough was realising `libvolcenginertc.so` imports `aacEncEncode`
(libfdk-aac's main entry point) via PLT:

```sh
$ objdump -R apk/45/.../lib/arm64-v8a/libvolcenginertc.so | grep aacEnc
0000000000e535a0 R_AARCH64_JUMP_SLOT      aacEncClose
0000000000e535a8 R_AARCH64_JUMP_SLOT      aacEncEncode    ← our hook target
0000000000e535b0 R_AARCH64_JUMP_SLOT      aacEncOpen
0000000000e535b8 R_AARCH64_JUMP_SLOT      aacEncoder_SetParam
0000000000e535c0 R_AARCH64_JUMP_SLOT      aacEncInfo
```

PLT hooks (via vendored `xhook`) are cheap and battle-tested — no inline
hook framework needed. xhook rewrites the GOT entry; every cross-`.so`
call to `aacEncEncode` from `libvolcenginertc.so` lands in our wrapper.

---

## TikTok 45.3.2 AAC encoder spec (verified empirically)

Observed via diagnostic logging from `hooked_aacEncEncode` during a real
LIVE on Samsung A15 5G:

| Field          | Value                                           |
|----------------|-------------------------------------------------|
| Codec          | AAC-LC (raw access units, no ADTS wrapper)      |
| Sample rate    | 48000 Hz                                        |
| Channels       | 2 (stereo)                                      |
| Bitrate        | ~256 kbps (frames typically 682-683 bytes)      |
| Frame size     | 1024 samples (~21.33 ms each)                   |
| Encoder cadence| ~50 fps                                         |
| First byte     | `0x21` = `00100001` = `ID_CPE` for stereo       |
| Output buffer  | 1536 bytes (always)                             |

PC encoder must produce frames with matching codec/rate/channels for
viewer-side decoders to play correctly. Bitrate variance is OK (PC's
ffmpeg native AAC produces 442-768 bytes/frame; sounds clean despite
looser CBR vs TikTok's tight 682-683).

---

## Architecture in detail

### PC side — `tools/aac-server.py`

```
MP4 file
   ↓
ffmpeg -i input.mp4 -vn -f s16le -ar 48000 -ac 2  (decode to raw PCM)
   ↓
ffmpeg -c:a aac -profile:a aac_low -b:a 256k -ar 48000 -ac 2 -f adts
   ↓  ADTS-framed AAC bytes
parse ADTS, strip 7-byte header per frame → list of raw AU bytes
   ↓
WebSocket server on 0.0.0.0:8765
   ↓
For each connected client, stream frames at real-time pace
(1024 samples ÷ 48000 Hz = 21.33 ms per frame)
loop at EOS so a single MP4 drives a long LIVE
```

Wire protocol per frame:

```
+----------------+----------------+--------+------------------+
| u32 BE         | u64 BE         | u8     | bytes            |
| payload_length | pts_microseconds| kind  | raw AAC AU       |
+----------------+----------------+--------+------------------+

kind = 0x01 for audio AAC (0x02 reserved for video H.264 in Phase 6)
```

The `pts_us` field is informational on the wire; mobile doesn't use it
for substitution because the hook fires at TikTok's own pts cadence.

Encoder choice (`--encoder` flag):

- `native` (default): ffmpeg's built-in AAC. Looser bitrate variance but
  produces clean viewer audio. Recommended.
- `fdkaac`: pipes through `fdkaac` CLI (`brew install fdk-aac-encoder`)
  for tighter CBR matching TikTok's libfdk-aac spec. Tested 2026-06-09
  and *worse* viewer audio quality despite tighter byte parity — unclear
  why, possibly a subtle encoder-param mismatch.

### Mobile side — Kotlin

`Mp4GWsClient.kt`:

- OkHttp 4.12.0 WebSocket client
- Connects to `vcam_ws_endpoint.txt` (default `ws://192.168.1.100:8765`)
- Receives binary frames, parses wire protocol, calls
  `NativeAudioHook.pushAacFrame(bytes, length)` for each AAC frame
- Exponential-backoff reconnect on disconnect (500 ms → 10 s cap)
- Lifecycle bound to `Mp4AudioProducer.start/stop`

`NativeAudioHook.kt`:

- JNI bridge to `libvcam_native.so`
- Exposes `pushAacFrame`, `clearAacRing`, `aacRingFrames`,
  `setRtmpInjectEnabled` to Kotlin code

### Mobile side — native (`audio_hook.c`)

`g_aac_ring`:

- 256-slot circular ring of variable-size AAC access units
- Each slot: `{ uint32_t size; uint8_t data[8192]; }` (8 KB AAC max)
- SPSC across producer (Java thread via JNI) and consumer
  (audio thread inside libvolcenginertc.so on the hook callback)
- Drop-oldest on overflow

`hooked_aacEncEncode`:

```c
// signature inferred from libfdk-aac aacenc_lib.h
static int hooked_aacEncEncode(void *h, const void *in,
                                const void *out,
                                const void *inargs, void *outargs) {
    int rc = real_aacEncEncode(h, in, out, inargs, outargs);

    // outargs->numOutBytes lives at offset 0
    int numOutBytes = *(const int *)outargs;

    // outDesc->bufs[0] points to the output buffer
    void *outBuf = ((aacenc_bufdesc_t *)out)->bufs[0];
    int   outBufSize = ((aacenc_bufdesc_t *)out)->bufSizes[0];

    if (g_rtmp_inject_enabled && outBuf && numOutBytes > 0) {
        uint8_t scratch[AAC_FRAME_MAX];
        uint32_t aac_size = aac_ring_pop(scratch, sizeof(scratch));
        if (aac_size > 0) {
            uint32_t copy = aac_size <= outBufSize ? aac_size : outBufSize;
            memcpy(outBuf, scratch, copy);
            *(int *)outargs = (int)copy;   // update numOutBytes
        }
        // underrun: ring empty → leave encoder output as-is
    }
    return rc;
}
```

PLT registration:

```c
xhook_register(".*/libvolcenginertc\\.so$", "aacEncEncode",
               (void *)hooked_aacEncEncode,
               (void **)&real_aacEncEncode);
xhook_refresh(0);
```

---

## Required runtime overrides

**FOUR files** must exist in
`/sdcard/Android/data/com.zhiliaoapp.musically/files/`:

| File                          | Value                          | Why                                              |
|-------------------------------|--------------------------------|--------------------------------------------------|
| `vcam_audio_mode.txt`         | `ws_inject`                    | Triggers Mp4GWsClient + AAC substitution         |
| `vcam_ws_endpoint.txt`        | `ws://<PC-IP>:8765`            | PC encoder server address                        |
| `vcam_audio_rate.txt`         | `48000`                        | **Required.** Forces PCM target to 48 kHz       |
| `vcam_audio_amp.txt`          | `0`                            | **Required.** Silences PCM injection payload    |

### Why `vcam_audio_rate.txt = 48000` is required

Default PCM target rate is 72000 Hz (legacy from when AudioRecord on
Samsung A15 was empirically observed to drain at ~72 kHz). With Option G
this is wrong:

- PCM injection upsamples MP4 audio from 48 kHz to 72 kHz, writes to
  AudioRecord buffer
- TikTok's encoder reads from AudioRecord at its configured rate
- If encoder reads at 48 kHz but we feed 72 kHz samples, encoder consumes
  1.5× faster than realtime → fires `aacEncEncode` at ~70 fps
- PC pushes AAC frames at ~47 fps (realtime for 48 kHz)
- Ring drains, partial underrun → some encoder fires get our substitute
  (clean), others fall through to TikTok's original encoded output
  (the 1.5× sped-up mic-derived MP4)
- Viewer hears mix of clean MP4 + sped-up MP4 = "ซ้อน 2 ไฟล์ + เร็ว"

Setting `vcam_audio_rate.txt = 48000` makes PCM injection match the
encoder rate exactly → encoder fires at ~47 fps → PC frame rate matches →
no underrun → 100% substitute.

### Why `vcam_audio_amp.txt = 0` is required

PCM injection (Mp4AudioProducer.runDecodeLoop) was designed for v0.1.1
where the goal was to MAKE TikTok's encoder output the MP4's audio
(distorted but better than nothing). In Option G, we don't need TikTok's
encoder output — we substitute it. But TikTok's encoder MUST FIRE for
our substitution to take effect.

Why must it fire? Because our PLT hook only triggers when something calls
`aacEncEncode`. If TikTok detects "no audio activity" on AudioRecord and
skips the encode, our hook gets no events → ring fills with PC AAC frames
that never get used → viewer hears nothing or hears TikTok's silent
default.

With `vcam_audio_amp.txt = 0`, PCM injection writes SILENT PCM into
AudioRecord (samples are all zero). TikTok's encoder still fires (silence
is still "data"), our hook substitutes the silence-encoded AAC with PC's
real audio. Net result: viewer hears only the PC audio, nothing else.

If amp ≠ 0, PCM injection writes real MP4 audio into AudioRecord →
TikTok's encoder encodes MP4 audio → our hook substitutes with PC's same
MP4 audio. The TWO copies of MP4 audio differ in timing (mic capture vs
WebSocket pace) → viewer hears DOUBLED audio. This is the "ซ้อน 2 ไฟล์"
symptom even after the rate is correct.

### Why the dual-injection design isn't a bug

It looks redundant — why have BOTH PCM injection (deliver MP4 to encoder)
AND AAC substitution (overwrite encoder output)? The answer: the PCM
side is a *trigger*, not a *deliverable*. PCM injection wakes up TikTok's
encoder; AAC substitution provides the actual content viewers hear.

I "fixed" this dual-design on 2026-06-09 and viewer audio went completely
silent because `aacEncEncode` stopped firing. Reverted immediately.

If you ever think "the PCM injection is redundant when we have AAC
substitution" — read this section again. Both are required.

---

## Operator workflow

### One-time setup

1. Install Reruni controller + patched TikTok on Android device (existing
   v0.1.x release process)
2. Set up PC encoder service:
   - `brew install ffmpeg` (and optionally `fdk-aac-encoder` for the
     `--encoder fdkaac` mode)
   - `pip3 install --user --break-system-packages websockets`
3. Get PC LAN IP (`ipconfig getifaddr en0` on macOS)

### Per-session setup

1. **Start PC server** on operator's PC:
   ```
   ./tools/aac-server.py /path/to/staged.mp4
   ```
   Server listens on `0.0.0.0:8765`.

2. **Configure mobile** (one time per device, persisted in TikTok's
   external files dir):
   ```
   adb shell "echo ws_inject > /sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_mode.txt"
   adb shell "echo ws://<PC-IP>:8765 > /sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_ws_endpoint.txt"
   adb shell "echo 48000 > /sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_rate.txt"
   adb shell "echo 0 > /sdcard/Android/data/com.zhiliaoapp.musically/files/vcam_audio_amp.txt"
   adb shell am force-stop com.zhiliaoapp.musically
   ```

   (The `test-audio-rate.sh` script can do mode/endpoint via subcommands
   but the rate/amp overrides still need manual `echo > file` writes.
   Future work: add shortcuts.)

3. **Stage MP4** via Reruni controller (existing flow)

4. **Open TikTok → Go LIVE**

5. **Verify** (optional): `./tools/test-audio-rate.sh ws-tail` shows:
   ```
   Mp4GWsClient: WS open (status=101)
   Mp4GWsClient: received=N frames, ring=256
   NativeAudioHook: AAC SUBSTITUTE #N  was=683 → now=614  outBufSize=1536  ring_left=255
   ```

### Multi-device scaling

Current implementation: one PC server : one phone. Trivial to extend the
WebSocket handler to broadcast the same MP4 to multiple connected phones
— a media library + per-phone routing belongs to a future Phase 8
(server-side migration to existing Go backend).

---

## Diagnostics

### Logs to watch

| Log line                                            | Meaning                                          |
|-----------------------------------------------------|--------------------------------------------------|
| `Mp4GWsClient: WS open (status=101)`                | Connected to PC server                           |
| `Mp4GWsClient: received=N frames, ring=256`         | Ring filling — PC streaming OK                   |
| `AAC SUBSTITUTE #N was=683 → now=614 ring_left=255` | Substitution working                             |
| `AAC underrun #N — passthrough orig`                | Ring empty — PC behind realtime, viewer skip     |
| `aacEncEncode#N rc=0 numOutBytes=682`               | Diagnostic log (first 32 fires only)             |

### Failure modes

| Symptom                          | Likely cause                                            | Fix                                              |
|----------------------------------|---------------------------------------------------------|--------------------------------------------------|
| No audio at all                  | Encoder isn't firing (no `aacEncEncode#N` log)          | Force-stop TikTok, verify `amp=0` set, retry     |
| ลำโพงแตก (DSP coloration)        | PCM injection at non-zero amp running through encoder   | Set `vcam_audio_amp.txt = 0`                     |
| เร็ว + ซ้อน 2 ไฟล์               | PCM target rate ≠ encoder rate → partial substitute     | Set `vcam_audio_rate.txt = 48000`                |
| Viewer hears silence intermittently| `AAC underrun` events — PC behind realtime             | Reduce PC encoder CPU load or buffer more frames |
| Audio glitches at MP4 loop boundary| PC server's `extract_aac_frames` re-loops from position 0 | Either accept (negligible) or pre-encode loop-friendly source |

---

## Files of interest

### Mobile
- `mobile/vcam/src/main/cpp/audio_hook.c`
  - `hooked_aacEncEncode` (line ~720): the PLT hook substituting output buffer
  - `g_aac_ring` (line ~620): 256-slot ring buffer for incoming AAC frames
  - `Java_..._pushAacFrame0` / `clearAacRing0` / `aacRingFrames0`: JNI exports
- `mobile/vcam/src/main/java/com/rerun/tiktokvcam/Mp4GWsClient.kt`
  - OkHttp WebSocket consumer + reconnect logic
- `mobile/vcam/src/main/java/com/rerun/tiktokvcam/NativeAudioHook.kt`
  - JNI bridge wrapping `pushAacFrame` etc.
- `mobile/vcam/src/main/java/com/rerun/tiktokvcam/Mp4AudioProducer.kt`
  - `start()` integration: spawns Mp4GWsClient when mode == `ws_inject`
- `mobile/vcam/build.gradle.kts`
  - OkHttp 4.12.0 dependency

### PC
- `tools/aac-server.py` — encoder + WebSocket server
- `tools/dev-install.sh` — dev iteration script (build + LSPatch + install to A15)
- `tools/test-audio-rate.sh` — operator helper (ws-inject / ws-endpoint / ws-tail subcommands)

### Memory (persistent across sessions)
- `~/.claude/projects/.../memory/project_option_g_aac_inject_works.md`
- This document (`docs/vcam-findings/option-g-aac-inject.md`)

---

## Open work

### Phase 6 — Video inject path
Viewer's video currently comes from TikTok's hardware H.264 encoder
encoding the camera frames we hijack via `Mp4FrameProducer`. The PC
could also encode video to spec and we'd substitute at a video encoder
hook (probably hooking `dlsym` for `bytevc0EncoderEncodeFrame` since
no PLT entry exists for video encoders).

### Phase 7 — Operator UX polish
- Add `vcam_audio_rate.txt` + `vcam_audio_amp.txt` shortcuts to
  `tools/test-audio-rate.sh ws-inject` subcommand so operator doesn't
  need to remember the manual `echo > file` writes
- Pairing UX: QR code or LAN scan instead of hardcoded endpoint file
- Loop boundary handling: pre-render seamless audio loops or crossfade
  on PC server side

### Phase 8 — Server-side migration
Move PC encoder service from operator's laptop to the existing
Reruni/Contabo Go backend:
- Operator uploads MP4 to portal → backend pre-encodes AAC frames once,
  stores in R2
- Mobile connects via `wss://stream.reruni.com/aac?token=...&media=...`
- Multi-tenant: 1 server : many phones (vs current 1 PC : 1 phone)
- See [`memory/project_option_g_aac_inject_works.md`](../../) for
  architecture sketch
