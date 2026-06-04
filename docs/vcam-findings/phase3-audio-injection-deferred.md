# Phase 3 — Audio Injection: Findings & V2 Roadmap

**Status (2026-06-04):** deferred to V2. Video injection ships in V1; LIVE
broadcasts use the real microphone (operator voices the stream).

This document captures everything we learned during ~16 iterations of
trying to substitute MP4 PCM into TikTok LIVE's broadcast audio so the
next engineer (likely future-Pond) can pick up where we stopped without
re-running diagnostics that already have answers.

---

## Pipeline map

```
MP4 file (vcam-staged)
   │
   ▼
Mp4AudioProducer (Kotlin)
   ├─ MediaCodec decoder
   ├─ Linear resampler (48→48 or 48→44.1)
   ├─ Java ring buffer (512 KB)            ◀── consumed by Java AudioRecord.read hooks
   └─ JNI writePcm0 → native ring (256 KB) ◀── consumed by substitute_audio_buffer
                                                    │
                                                    ▼
                                          obtainBuffer[ts] PLT hook
                                          overwrites audioBuffer->raw
                                                    │
                                                    ▼
                                          (TikTok mixer + WebRTC pre-processors)
                                                    │
                                                    ▼
                                          LyraxPublisherImpl AAC encoder
                                          (HE-AACv1, 64 kbps stereo, 48 kHz)
                                                    │
                                                    ▼
                                          VolcEngine RTC over WebRTC ICE
                                                    │
                                                    ▼
                                          TikTok CDN → viewers
```

The substitute path is *technically working*: a silence test (memset 0
into the buffer) produced silence on the viewer side, proving every byte
we write reaches the broadcast. Every non-zero substitution, however,
produces the same "blown-speaker" artefact regardless of content.

---

## What we proved

| # | Claim | Evidence |
|---|-------|----------|
| 1 | Mp4AudioProducer outputs valid PCM16 LE stereo at the configured target | `mp4_decoded.raw` (producer-side dump) played cleanly at `s16le 44100 stereo` |
| 2 | obtainBuffer raw is PCM16 LE stereo at 48 kHz | Mic capture dump played cleanly at `s16le 48000 stereo`; hex pattern (`fe ff fd ff …`) is canonical small-int16 noise floor; float reinterpretation gave NaN |
| 3 | Native AudioRecord sample rate = 48000 | `AR_DUMP` of the AudioRecord object found `0x0000BB80` at `+0x140` (`mSampleRate` field), plus matches at `+0x23c` and `+0x280` |
| 4 | mFormat = `PCM_16_BIT` (1), mChannelCount = 2, mChannelMask = `IN_STEREO` (0xC) | AR_DUMP at offsets `+0x144`, `+0x148`, `+0x15c` |
| 5 | Silence substitution → broadcast silence | Sine + music substitution → broadcast distortion |
| 6 | TikTok's WebRTC processor switches (ANS, APM, AEC, EchoMode) are already `false` from TikTok's side | `TtRtcAudioHook` logged every enable* call: TikTok itself requests `false` |
| 7 | TikTok's audio encoder config (Lyrax) is `aacCodecProfile=AACHEv1, bitrateKbps=64, channelNum=Stereo, sampleRate=48000` | `TtRtcEncoderHook` log of `LyraxPublisherImpl.setAudioEncoderConfig(...)` |
| 8 | Encoder config is *identical* across TikTok v43.9.3 (trill) and v45.3.2 (musically) | Cross-version recon, same Samsung A15 device |
| 9 | The audio mixer combines `hasMediaPlayer + hasMic + hasSpecialAudio` content sources before encoding | `LyraxPublisherImpl.setAudioContentConfig({hasMediaPlayer=true, hasMic=true, hasScreenAudio=false, hasSpecialAudio=true})` |
| 10 | Disabling the non-mic mix sources caused progressive A/V drift | Pond observed "ภาพและเสียงเลื่อนไปเรื่อย ๆ" after content-config override |

---

## What we ruled out as root cause

- **PCM format** — proven correct (claims 2, 4)
- **Sample rate** — proven 48 kHz (claim 3); 44.1 kHz attempt was worse
- **Amplitude / AGC overshoot** — sine at 0x100 amp (0.78 % FS) also distorted
- **Java-side audio source** — `audio_source_t = UNPROCESSED` via ctor PLT
  trampoline didn't change AR_DUMP's source field, suggesting either the
  ctor PLT entry isn't on TikTok's call path or the field offset is
  different in their AOSP fork
- **WebRTC APM / ANS / AEC / AGC** — already disabled by TikTok (claim 6)
- **AAC codec profile** — `AACHEv1 → AACLC` rewrite confirmed applied
  downstream (`LyraxAudioEncoderConfig{aacCodecProfile=AACLC,...}` logged
  after rewrite) but distortion unchanged
- **Bitrate** — 64 → 128 rewrite applied, distortion unchanged
- **TikTok version** — v43.9.3 = v45.3.2 in encoder behaviour (claim 8)
- **Voice-shaping pre-processor** — HPF + peak limiter + mono downmix on
  Mp4AudioProducer side made onset cleaner for ~5 s then degraded
  (suggests ANS-style adaptation downstream, but where?)

---

## What we couldn't reach

These are the suspected layers that distortion lives in, ordered by
remaining-effort-vs-likelihood:

1. **Samsung Exynos audio HAL DSP**
   *Hypothesis:* the HAL applies voice-tuned AGC/ANS *before* AudioFlinger
   sees the data, so by the time `obtainBuffer` returns we're past the
   suppressor. Substituting after this point lets the rest of the chain
   re-process our signal as if it were also mic input, but the HAL
   wouldn't have left the same conditioning behind that downstream layers
   expect.
   *To prove:* run on a non-Samsung device (Pixel ideally — closest to
   AOSP HAL). AVD didn't work (TikTok detects emulator + refuses to
   launch).
   *Effort:* hardware-bound — need to borrow / buy a Pixel 6a/7a/8a.

2. **TikTok core processor not exposed via JNI**
   *Hypothesis:* a C++ processor inside `libvolcenginertc.so` operates on
   raw PCM between `obtainBuffer` and the AAC encoder, but it's reached
   through a static `Process(...)` call (`SAMICoreProcess` was found in
   `libaudioeffect.so` but the surrounding methods are TTS-context, so
   probably not the LIVE broadcast path).
   *To prove:* inline-hook PLT-invisible functions. `libbytehook.so` is
   right there in the APK and is TikTok's own hook library.
   *Effort:* 3-7 days of disassembly + tooling.

3. **Server-side re-encode**
   *Hypothesis:* TikTok's LIVE CDN transcodes the incoming RTC stream
   before fan-out to viewers, and the transcoder is voice-tuned. In this
   model the device-side encoder config doesn't matter — Pond's clip
   would look "voice-codec-mangled" regardless of what we change
   client-side, which fits the observed pattern.
   *To prove:* either get a viewer-side WebRTC trace (impossible without
   TikTok cooperation) or run two devices side-by-side where one is on a
   different region/CDN.
   *Effort:* unknown — likely unsolvable without insider info.

---

## What's left in the codebase

The audio code stays in tree, *not wired up*. To re-enable for V2 R&D,
uncomment the five `try { … }` blocks in
[`HookEntry.kt`](../../mobile/vcam/src/main/java/com/rerun/tiktokvcam/HookEntry.kt).

| File | Purpose | Status |
|------|---------|--------|
| `AudioRecordHook.kt` | Java `AudioRecord` ctor + `read*` hooks | Not loaded |
| `Mp4AudioProducer.kt` | Decoder + resampler + Java/native ring producer | Not loaded |
| `NativeAudioHook.kt` | JNI bridge to `libvcam_native.so` (ring write, install0, refresh0) | Not loaded |
| `cpp/audio_hook.c` | xhook PLT hooks on `obtainBuffer[ts]`, `releaseBuffer`, `AudioRecord` ctor + diagnostic dumps + sine generator | Built but no install call |
| `TtLivestreamerHook.kt` | `AudioRecordProcessor` JNI native-method log | Not loaded |
| `TtRtcAudioHook.kt` | `AudioDeviceModule` enable* method overrides | Not loaded |
| `TtRtcEncoderHook.kt` | LyraxPublisher encoder config logger (rewrites disabled, pure recon left in) | Not loaded |

`AudioLoopbackPlayer` + `AudioLoopbackReceiver` (in the controller app)
were also written but turned out to be incompatible with the multi-device
operating model — Pond's setup has 10+ phones broadcasting in the same
room, so any phone's speaker would bleed into every other phone's mic.
Code left in place since it works fine for a single device (e.g.,
debugging a one-phone live).

---

## Recommended V2 attack order

1. **Pixel device test first** — cheapest path to either confirm or kill
   the HAL hypothesis. If it works clean: ship V2 with Pixel-only
   compatibility on the audio-injection tier.
2. **If HAL ruled out, inline-hook `SAMICoreProcess`** — it's a generic
   audio frame processor; hooking it and dumping the buffer at the call
   site tells us whether the LIVE path even touches SAMI.
3. **If neither helps, abandon software fix** — pivot to per-phone
   acoustic isolation (headphone earcup pressed against the mic). ~50-100
   ฿ per phone, scales to N devices, no version risk.

A clean Pixel test would take an evening if Pond can borrow one — that's
the only step worth doing before deciding direction.
