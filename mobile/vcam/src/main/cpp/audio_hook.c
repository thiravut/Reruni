/*
 * Native audio hook for the Reruni vcam LSPosed module.
 *
 * Phase 1 scope (this revision): wire up an xhook PLT hook on OpenSL ES'
 * `slCreateEngine` symbol inside libvolcenginertc.so (ByteDance's RTC
 * engine TikTok LIVE uses for its broadcast audio capture). Hook is
 * log-only — when it fires we know the native hook infrastructure is
 * working end-to-end and we can extend to RegisterCallback / Enqueue
 * intercepts in Phase 2 without re-doing build wiring.
 *
 * Why xhook over LSPlant's native helper: we only need PLT-level
 * redirection of imports from another lib; we do NOT need to hook
 * functions inside TikTok's own code. xhook's API is tiny
 * (xhook_register + xhook_refresh) and battle-tested in Chinese super-
 * apps. Apache/MIT licensed — see xhook/LICENSE.
 */

#include <errno.h>
#include <jni.h>
#include <math.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <android/log.h>

#include "xhook/xhook.h"
// shadowhook include removed — caused TikTok SIGSEGV on Samsung A15
// Android 16 (signal handler / .init_array conflict). Phase 1.7 will
// try a different inline-hook strategy (manual prologue patching or
// vendoring a more conservative library like dobby).

#define TAG "TiktokRerunVCam"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  TAG, "NativeAudioHook: " fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  TAG, "NativeAudioHook: " fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, "NativeAudioHook: " fmt, ##__VA_ARGS__)

// ---------------------------------------------------------------------------
// Function pointer types we'll replace. We don't include OpenSL ES headers
// directly — they're large and we only need an opaque handle. The first arg
// to slCreateEngine is an OUT pointer to an SLObjectItf which we treat as
// void**; everything else flows through unchanged.
// ---------------------------------------------------------------------------

typedef int sl_result_t;
typedef void *sl_objectitf_t;

typedef sl_result_t (*slCreateEngine_t)(
        sl_objectitf_t *p_engine,
        unsigned int num_options,
        const void *engine_options,
        unsigned int num_interfaces,
        const void *interface_ids,
        const unsigned int *interface_required);

static slCreateEngine_t real_slCreateEngine = NULL;

static sl_result_t hooked_slCreateEngine(
        sl_objectitf_t *p_engine,
        unsigned int num_options,
        const void *engine_options,
        unsigned int num_interfaces,
        const void *interface_ids,
        const unsigned int *interface_required) {
    LOGI("→ slCreateEngine(num_options=%u, num_interfaces=%u)",
         num_options, num_interfaces);
    if (real_slCreateEngine == NULL) {
        LOGE("real_slCreateEngine NULL — refusing call");
        return -1;
    }
    sl_result_t result = real_slCreateEngine(
            p_engine, num_options, engine_options,
            num_interfaces, interface_ids, interface_required);
    LOGI("← slCreateEngine returned %d, engine=%p",
         result, p_engine ? *p_engine : NULL);
    return result;
}

// ---------------------------------------------------------------------------
// android::AudioRecord::read — the C++ entry point inside libaudioclient.so
// that both OpenSL ES BufferQueueRecord and AAudio's legacy fallback path
// route through. Mangled symbol on arm64-v8a:
//   _ZN7android11AudioRecord4readEPvmb
//     _ZN7android         namespace android
//     11AudioRecord        class AudioRecord
//     4read                method read
//     E                    end of nested name
//     Pv m b               args: void*, size_t (m), bool (b)
// Return is ssize_t (== long on Android). On Android 12+ the signature
// became (void*, size_t, bool) — `b` is the blocking flag.
//
// We register this against the RTC libs' GOT entries so when their internal
// code calls AudioRecord::read(), the call lands here instead. After the
// real call we know the buffer + size that was filled — that's our
// substitution point in Phase 3.
//
// IMPORTANT: there are two overload signatures historically. Older AOSP
// (pre-N) was `read(void*, size_t)`; modern is `read(void*, size_t, bool)`.
// We register both manglings; only the one TikTok's libs actually import
// will resolve (xhook silently skips PLT entries that don't exist).
// ---------------------------------------------------------------------------

typedef long (*AudioRecord_read3_t)(void *self, void *buffer, size_t size, int blocking);
typedef long (*AudioRecord_read2_t)(void *self, void *buffer, size_t size);

static AudioRecord_read3_t real_AudioRecord_read3 = NULL;
static AudioRecord_read2_t real_AudioRecord_read2 = NULL;

// Per-call budget so we don't spam logcat once the broadcast is live.
// AudioRecord::read fires ~50–100 Hz; log the first N then go silent.
// Bumped to 64 for the 2026-06-04 sample-rate diagnostic — Pond's hypothesis
// is the v0.1.1 path was right but PCM played at wrong rate. More log
// samples = more reliable rate estimate from frames/elapsed-ms.
#define AUDIO_READ_LOG_BUDGET 64
static int audio_read_log_count = 0;

static long hooked_AudioRecord_read3(void *self, void *buffer, size_t size, int blocking) {
    if (real_AudioRecord_read3 == NULL) {
        LOGE("real_AudioRecord_read3 NULL — refusing call");
        return -1;
    }
    long got = real_AudioRecord_read3(self, buffer, size, blocking);
    if (audio_read_log_count < AUDIO_READ_LOG_BUDGET) {
        audio_read_log_count++;
        LOGI("AudioRecord::read(this=%p, buf=%p, size=%zu, blocking=%d) → %ld%s",
             self, buffer, size, blocking, got,
             audio_read_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing)" : "");
    }
    return got;
}

static long hooked_AudioRecord_read2(void *self, void *buffer, size_t size) {
    if (real_AudioRecord_read2 == NULL) {
        LOGE("real_AudioRecord_read2 NULL — refusing call");
        return -1;
    }
    long got = real_AudioRecord_read2(self, buffer, size);
    if (audio_read_log_count < AUDIO_READ_LOG_BUDGET) {
        audio_read_log_count++;
        LOGI("AudioRecord::read[2arg](this=%p, buf=%p, size=%zu) → %ld%s",
             self, buffer, size, got,
             audio_read_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing)" : "");
    }
    return got;
}

// AAudio path — modern Android RTC engines often use AAudioStream_read
// directly. libaaudio.so is usually loaded by the system early. Same
// PLT-hook treatment so we cover both paths in a single revision.
//   aaudio_result_t AAudioStream_read(AAudioStream*, void*, int32_t, int64_t);
typedef int32_t (*AAudioStream_read_t)(void *stream, void *buffer, int32_t num_frames, int64_t timeout_ns);
static AAudioStream_read_t real_AAudioStream_read = NULL;
static int aaudio_read_log_count = 0;

static int32_t hooked_AAudioStream_read(void *stream, void *buffer, int32_t num_frames, int64_t timeout_ns) {
    if (real_AAudioStream_read == NULL) {
        LOGE("real_AAudioStream_read NULL — refusing call");
        return -1;
    }
    int32_t got = real_AAudioStream_read(stream, buffer, num_frames, timeout_ns);
    if (aaudio_read_log_count < AUDIO_READ_LOG_BUDGET) {
        aaudio_read_log_count++;
        LOGI("AAudioStream_read(stream=%p, buf=%p, frames=%d, timeout=%lld) → %d%s",
             stream, buffer, num_frames, (long long) timeout_ns, got,
             aaudio_read_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing)" : "");
    }
    return got;
}

// ---------------------------------------------------------------------------
// android::AudioRecord::obtainBuffer / releaseBuffer — the *real* PCM transfer
// happens via this buffer protocol on the AudioRecord client side, NOT via
// the higher-level read() method. OpenSL ES SimpleBufferQueueRecord uses
// obtainBuffer() to get a chunk of PCM from AudioFlinger's shared memory,
// memcpy()s it into the client's enqueued buffer, then releaseBuffer()s.
// If our PLT hook fires, we have the buffer pointer + size at exactly the
// moment the PCM is available to overwrite.
//
// Mangled symbols:
//   obtainBuffer(Buffer*, int32_t waitCount, size_t* nonContig)
//     _ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiPm
//   releaseBuffer(const Buffer*)
//     _ZN7android11AudioRecord13releaseBufferEPKNS0_6BufferE
//
// AudioRecord::Buffer layout (frameworks/av AudioRecord.h):
//   struct Buffer {
//       size_t frameCount;       // [in] requested / [out] actual
//       size_t size;             // bytes (== frameCount * frameSize)
//       union { void* raw; short* i16; int8_t* i8; };
//       int64_t sequence;
//       audio_format_t format;
//   };
// frameCount and size are filled by the framework after obtainBuffer.
// raw points into AudioFlinger's shared-mem ring buffer — this is where
// the PCM the mic captured lives. Overwriting raw[0..size) before
// releaseBuffer() makes the client see our audio instead of the mic's.
// ---------------------------------------------------------------------------

// Minimal mirror of android::AudioRecord::Buffer. We only need the first
// three fields; the rest of the struct stays opaque so we don't care about
// its layout drifting across Android versions.
typedef struct {
    size_t frameCount;
    size_t size;
    void *raw;
    // ... remaining fields untouched
} ar_buffer_t;

// Both obtainBuffer overloads exist on Android. The AudioRecord internal
// thread (AudioRecordThread::threadLoop) uses the timespec variant; client
// code occasionally uses the waitCount variant. We hook both. PLT hits on
// just the timespec one mean RTC's audio path goes via AudioRecordThread
// (most likely — its callback mode is what releaseBuffer firing implies).
// AudioRecord native C++ constructor — Android 12+ signature. First arg is
// `this` (implicit), second is audio_source_t. By PLT-hooking the ctor we
// can force every AudioRecord that TikTok creates to use UNPROCESSED source
// instead of VOICE_COMMUNICATION (the default for live streaming), which
// disables HAL-side AGC/ANS/AEC. The full argument list is huge; we only
// need to mutate arg #2 (audio_source_t) so we declare a varargs-style
// thunk that forwards everything else unchanged.
//
// Mangled symbol (Android 12+):
//   _ZN7android11AudioRecordC1E14audio_source_tj14audio_format_t20audio_channel_mask_t...
//
// Source values (audio.h):
//   1 = MIC, 4 = VOICE_CALL, 5 = CAMCORDER, 6 = VOICE_RECOGNITION,
//   7 = VOICE_COMMUNICATION, 9 = UNPROCESSED, 10 = VOICE_PERFORMANCE.
// UNPROCESSED disables the entire HAL DSP chain.

#define AUDIO_SOURCE_UNPROCESSED 9

// On arm64, the AAPCS passes the first 8 integer/pointer args in x0..x7
// and 8 float args in v0..v7. We can't safely express the full signature,
// so we use a generic thunk: hook captures the source arg from x1 and
// rewrites it in place before calling the real ctor. Because we use a
// 2-arg prototype here, the calling convention preserves x0 (this) and
// x1 (audio_source_t) for our use, but the real call needs ALL the
// original args intact — so we use a tail-call trick via a small
// assembly trampoline (inline below).

typedef void (*AudioRecord_ctor_t)(void *self, int audio_source);
static AudioRecord_ctor_t real_AudioRecord_ctor = NULL;

// arm64 trampoline: rewrite two args of the variadic ctor (audio_source_t
// at x1, sampleRate at x2), then jump to real ctor. All other registers
// (x0=this, x3..x7=remaining args, stack=overflow args) flow through
// untouched. This is the only safe way to mutate args of a variadic C++
// ctor without knowing the rest of the signature.
//
// Why we also force sampleRate (2026-06-05): the native broadcast AR was
// being created at ~72 kHz, forcing us to resample our 44.1 kHz MP4
// source up to 72 kHz with linear/cubic interp. The aliased images and
// AAC encoder mangling that followed produced the "ทุ้ม + ลำโพงแตก"
// distortion Pond heard. Forcing the AR to 48000 Hz (TikTok's encoder
// native rate per phase3 doc) means:
//   * our resample becomes 44.1 → 48 = 1.088×, vs 44.1 → 72 = 1.633×
//     — far less aliasing
//   * TikTok's internal resample to encoder rate disappears entirely
//   * the audio path stays at 48 kHz from our buffer write through
//     encoder input — only one lossy step (the unavoidable AAC encode)
#if defined(__aarch64__)
__attribute__((naked, used))
static void hooked_AudioRecord_ctor_thunk(void) {
    __asm__ volatile(
        // Force x1 = AUDIO_SOURCE_UNPROCESSED (9).
        "mov    x1, #9                  \n"
        // Force w2 (sampleRate) = 48000 (0xBB80).
        "movz   w2, #0xBB80             \n"
        // Load real ctor address into x16 (scratch).
        "adrp   x16, real_AudioRecord_ctor              \n"
        "ldr    x16, [x16, #:lo12:real_AudioRecord_ctor]\n"
        // Tail-call: real ctor returns to original caller.
        "br     x16                     \n"
    );
}
#elif defined(__arm__)
__attribute__((naked, used))
static void hooked_AudioRecord_ctor_thunk(void) {
    // 32-bit ARM equivalent: r1 = audio_source_t (after r0=this),
    // r2 = sampleRate.
    __asm__ volatile(
        "mov    r1, #9                                  \n"
        "movw   r2, #0xBB80                             \n"  // 48000
        "ldr    r12, =real_AudioRecord_ctor             \n"
        "ldr    r12, [r12]                              \n"
        "bx     r12                                     \n"
    );
}
#else
// Fallback (e.g., x86 emulator): just call through without modification.
static void hooked_AudioRecord_ctor_thunk(void *self, int audio_source) {
    if (real_AudioRecord_ctor) real_AudioRecord_ctor(self, AUDIO_SOURCE_UNPROCESSED);
}
#endif

typedef int (*AudioRecord_obtainBuffer_t)(void *self, ar_buffer_t *audioBuffer, int32_t waitCount, size_t *nonContig);
typedef int (*AudioRecord_obtainBufferTs_t)(void *self, ar_buffer_t *audioBuffer,
                                            const void *requested, void *elapsed,
                                            size_t *nonContig);
typedef void (*AudioRecord_releaseBuffer_t)(void *self, const ar_buffer_t *audioBuffer);

static AudioRecord_obtainBuffer_t   real_AudioRecord_obtainBuffer   = NULL;
static AudioRecord_obtainBufferTs_t real_AudioRecord_obtainBufferTs = NULL;
static AudioRecord_releaseBuffer_t  real_AudioRecord_releaseBuffer  = NULL;
static int obtain_log_count = 0;
static int obtain_ts_log_count = 0;
static int release_log_count = 0;

// Phase 2c validation switch: when set, we memset the buffer to zero
// in the obtainBuffer after-hook to silence the broadcast. Phase 2d
// confirmed this works — viewer-side heard silence. Flipped to 0 now
// that Phase 3 (native PCM ring) is wired.
#define VCAM_AUDIO_SILENCE_TEST 0

// Passthrough flag — when set (via JNI from Mp4AudioProducer "speaker"
// mode), obtainBuffer skips PCM substitution entirely. The mic buffer
// keeps whatever the hardware captured, which for the acoustic-loopback
// diagnostic is the MP4 audio being played through the device speaker.
// Atomic so the audio thread reads it without locking.
static _Atomic int g_passthrough = 0;

// ---------------------------------------------------------------------------
// Native PCM ring buffer. Java side (Mp4AudioProducer) pushes resampled PCM
// into this via the writePcm JNI export below; the audio thread inside
// libaudioclient.so reads from it in the obtainBuffer after-hook, replacing
// the mic data with our MP4 audio.
//
// Lockless single-producer / single-consumer. The producer (Java decode
// thread) is normal-priority; the consumer (libaudioclient AudioRecordThread)
// is real-time. We use atomic head/tail with relaxed memory ordering — a
// stale read just means we underflow and pad with silence, which is what
// happens naturally between MP4 loop boundaries anyway.
//
// 256 KB ≈ 1.5 s of 44.1 kHz stereo PCM16; large enough to absorb decoder
// latency variation, small enough that we never get too far ahead of the
// broadcast on a stalled write.
// ---------------------------------------------------------------------------

#define PCM_RING_SIZE  (1u << 18)
#define PCM_RING_MASK  (PCM_RING_SIZE - 1u)

static uint8_t g_pcm_ring[PCM_RING_SIZE];
static _Atomic uint32_t g_ring_write_pos = 0;
static _Atomic uint32_t g_ring_read_pos  = 0;

static inline size_t pcm_ring_available(void) {
    uint32_t w = atomic_load_explicit(&g_ring_write_pos, memory_order_relaxed);
    uint32_t r = atomic_load_explicit(&g_ring_read_pos,  memory_order_relaxed);
    return (size_t)(w - r);  // unsigned wraparound handles uint32 overflow
}

// Returns bytes copied (clamped to whatever's available; remainder must be
// silence-padded by the caller).
static size_t pcm_ring_read(uint8_t *dst, size_t want) {
    uint32_t w = atomic_load_explicit(&g_ring_write_pos, memory_order_acquire);
    uint32_t r = atomic_load_explicit(&g_ring_read_pos,  memory_order_relaxed);
    uint32_t avail = w - r;
    size_t copy = (avail < want) ? (size_t)avail : want;

    uint32_t off = r & PCM_RING_MASK;
    size_t tail = PCM_RING_SIZE - off;
    if (copy <= tail) {
        memcpy(dst, g_pcm_ring + off, copy);
    } else {
        memcpy(dst,         g_pcm_ring + off, tail);
        memcpy(dst + tail,  g_pcm_ring,       copy - tail);
    }
    atomic_store_explicit(&g_ring_read_pos, r + (uint32_t)copy, memory_order_release);
    return copy;
}

// Forward decls so pcm_ring_write can snapshot to disk for the producer dump.
static void prod_capture_write(const void *src, size_t len);

// Forward decls for Option B (rtmp_client_push_audio/video PLT hooks). The
// definitions live after the JNI exports at the bottom of the file; the
// install0 below registers the hooks first, so we declare them up top.
typedef void (*rtmp_client_push_audio_t)(void *handle, void *data,
                                          uint32_t size, uint32_t pts);
typedef void (*rtmp_client_push_video_t)(void *handle, void *data,
                                          uint32_t size, uint32_t pts);
static rtmp_client_push_audio_t real_rtmp_client_push_audio;
static rtmp_client_push_video_t real_rtmp_client_push_video;
static void hooked_rtmp_client_push_audio(void *handle, void *data,
                                          uint32_t size, uint32_t pts);
static void hooked_rtmp_client_push_video(void *handle, void *data,
                                          uint32_t size, uint32_t pts);

// Option G: aacEncEncode PLT hook. libfdk-aac is bundled separately as
// libfdk-aac.so and libvolcenginertc.so imports `aacEncEncode` via PLT
// (confirmed by `objdump -R libvolcenginertc.so | grep aacEnc`). Hooking
// it intercepts every AAC encode call from TikTok's encoder — we see the
// PCM input + the encoded AAC output, and can overwrite the output bytes
// with our PC-encoded AAC (Option G architecture). Voice DSP runs
// UPSTREAM of the encoder so substitution AT THE ENCODER OUTPUT bypasses
// DSP entirely.
//
// libfdk-aac signature (Frontends/aacenc/aacenc_lib.h):
//   AACENC_ERROR aacEncEncode(HANDLE_AACENCODER hAacEncoder,
//                              const AACENC_BufDesc *inBufDesc,
//                              const AACENC_BufDesc *outBufDesc,
//                              const AACENC_InArgs  *inargs,
//                                    AACENC_OutArgs *outargs);
//
// We treat all but the first arg as opaque pointers; the only struct we
// need to know is AACENC_OutArgs (so we can read numOutBytes after the
// call). To keep this compile-time-decoupled from libfdk-aac headers,
// we mirror just the offset of numOutBytes (first 4 bytes of the struct).
typedef int (*aacEncEncode_t)(void *h, const void *in, const void *out,
                              const void *inargs, void *outargs);
static aacEncEncode_t real_aacEncEncode;
static int hooked_aacEncEncode(void *h, const void *in, const void *out,
                               const void *inargs, void *outargs);

// Producer side: drop-oldest on overflow so the audio thread always reads
// the freshest PCM. Called from Java (Mp4AudioProducer) per decoded chunk.
static void pcm_ring_write(const uint8_t *src, size_t len) {
    if (len == 0) return;
#if !SHIP_BUILD
    // Mirror every push into the producer dump file so Pond can listen to
    // exactly what we're feeding the ring (as PCM16 stereo 48 kHz).
    prod_capture_write(src, len);
#endif
    if (len > PCM_RING_SIZE) {
        // Truncate to the most recent chunk; older audio is stale anyway.
        src += (len - PCM_RING_SIZE);
        len = PCM_RING_SIZE;
    }
    uint32_t w = atomic_load_explicit(&g_ring_write_pos, memory_order_relaxed);
    uint32_t r = atomic_load_explicit(&g_ring_read_pos,  memory_order_acquire);
    uint32_t avail = w - r;
    if ((size_t)avail + len > PCM_RING_SIZE) {
        // Drop oldest bytes to make room.
        uint32_t drop = (uint32_t)((size_t)avail + len - PCM_RING_SIZE);
        atomic_store_explicit(&g_ring_read_pos, r + drop, memory_order_release);
    }
    uint32_t off = w & PCM_RING_MASK;
    size_t tail = PCM_RING_SIZE - off;
    if (len <= tail) {
        memcpy(g_pcm_ring + off, src, len);
    } else {
        memcpy(g_pcm_ring + off, src,        tail);
        memcpy(g_pcm_ring,       src + tail, len - tail);
    }
    atomic_store_explicit(&g_ring_write_pos, w + (uint32_t)len, memory_order_release);
}

// Diagnostic: log first N pulls so we can confirm the ring is being fed
// when the broadcast starts. Cap so we don't spam logcat once steady-state.
static int pcm_pull_log_count = 0;
#define PCM_PULL_LOG_BUDGET 16

// ---------------------------------------------------------------------------
// Diagnostic mode switch.
//
//   0 — production: drain native ring into buffer (PCM16 stereo @ 48 kHz)
//   1 — MIC CAPTURE: dump first ~6 MB of obtainBuffer raw bytes to file
//   2 — SINE WAVE: 440 Hz PCM16 stereo 48 kHz at amp 0x2000 (~ -12 dBFS),
//        bypassing Mp4AudioProducer / ring entirely. If viewer hears a
//        clean tone → data path is fine, distortion is content/pacing
//        related. If sine also distorts → data path itself is broken
//        (timing, format alignment, or TikTok DSP fundamentally rejecting
//        non-mic-like signals).
// ---------------------------------------------------------------------------
#define DIAG_MODE 0

// Ship build: skip writing diagnostic .raw dumps to the app's external
// storage. Diagnostic builds for V2 R&D can flip this to 0 to get back
// mic_capture.raw + mp4_decoded.raw + FORMAT_DUMP/AR_DUMP log lines.
#define SHIP_BUILD 1

#if DIAG_MODE == 2
static double g_sine_phase = 0.0;
#define SINE_FREQ_HZ 440.0
#define SINE_RATE_HZ 48000.0
// Very low amplitude — ~0.78 % of full-scale. Far below any AGC compression
// or NS attenuation thresholds. If even this distorts on viewer side, the
// problem isn't level/AGC but something fundamental in the data path or
// the WebRTC preprocessing chain corrupting *any* non-zero signal.
#define SINE_AMP_INT16 0x100
#endif

// Capture path — Android 11+ scoped storage blocks raw /sdcard writes
// for apps without MANAGE_EXTERNAL_STORAGE. App-private external storage
// (TikTok's own files dir) is always writable without any permission and
// is pullable from the host via `adb pull` without root.
#define CAPTURE_DIR  "/sdcard/Android/data/com.zhiliaoapp.musically/files"
#define CAPTURE_PATH CAPTURE_DIR "/mic_capture.raw"
#define CAPTURE_MAX_BYTES (6u * 1024u * 1024u)  // ≈32 s @ PCM16 stereo 48 kHz, ≈16 s @ FLOAT stereo

static FILE *g_capture_fp = NULL;
static size_t g_capture_written = 0;
static int g_capture_closed = 0;

static void capture_open_lazy(void) {
    if (g_capture_fp != NULL || g_capture_closed) return;
    // The files dir is auto-created by Android when the app launches, but
    // mkdir cheaply in case we beat it.
    mkdir(CAPTURE_DIR, 0775);
    g_capture_fp = fopen(CAPTURE_PATH, "wb");
    if (g_capture_fp == NULL) {
        LOGW("capture_open(%s) failed: %s", CAPTURE_PATH, strerror(errno));
        g_capture_closed = 1;
        return;
    }
    LOGI("capture: writing mic raw to %s (max %u bytes)", CAPTURE_PATH, CAPTURE_MAX_BYTES);
}

static void capture_write(const void *raw, size_t size) {
    if (g_capture_closed) return;
    if (g_capture_fp == NULL) capture_open_lazy();
    if (g_capture_fp == NULL) return;
    if (g_capture_written >= CAPTURE_MAX_BYTES) {
        fflush(g_capture_fp);
        fclose(g_capture_fp);
        g_capture_fp = NULL;
        g_capture_closed = 1;
        LOGI("capture: closed after %zu bytes — pull %s and run ffplay 4 ways",
             g_capture_written, CAPTURE_PATH);
        return;
    }
    size_t to_write = size;
    if (g_capture_written + to_write > CAPTURE_MAX_BYTES) {
        to_write = CAPTURE_MAX_BYTES - g_capture_written;
    }
    size_t wrote = fwrite(raw, 1, to_write, g_capture_fp);
    g_capture_written += wrote;
    // Flush periodically so a crash doesn't lose the in-memory tail.
    if ((g_capture_written & 0x3FFFFu) == 0) fflush(g_capture_fp);
}

// Producer-side capture — what Mp4AudioProducer feeds into the native ring.
// Lets Pond listen to it offline as PCM16 stereo 48 kHz to verify the
// decoder + resampler pipeline works regardless of what format the broadcast
// expects on the consumer side.
#define PROD_CAPTURE_PATH CAPTURE_DIR "/mp4_decoded.raw"
static FILE *g_prod_fp = NULL;
static size_t g_prod_written = 0;
static int g_prod_closed = 0;

static void prod_capture_write(const void *src, size_t len) {
    if (g_prod_closed) return;
    if (g_prod_fp == NULL) {
        mkdir(CAPTURE_DIR, 0775);
        g_prod_fp = fopen(PROD_CAPTURE_PATH, "wb");
        if (g_prod_fp == NULL) {
            LOGW("prod_capture(%s) failed: %s", PROD_CAPTURE_PATH, strerror(errno));
            g_prod_closed = 1;
            return;
        }
        LOGI("prod_capture: writing producer PCM to %s", PROD_CAPTURE_PATH);
    }
    if (g_prod_written >= CAPTURE_MAX_BYTES) {
        fflush(g_prod_fp);
        fclose(g_prod_fp);
        g_prod_fp = NULL;
        g_prod_closed = 1;
        LOGI("prod_capture: closed after %zu bytes", g_prod_written);
        return;
    }
    size_t to_write = (g_prod_written + len > CAPTURE_MAX_BYTES)
            ? CAPTURE_MAX_BYTES - g_prod_written : len;
    g_prod_written += fwrite(src, 1, to_write, g_prod_fp);
    if ((g_prod_written & 0x3FFFFu) == 0) fflush(g_prod_fp);
}

// AudioRecord object dump — when obtainBuffer first fires, scan the first
// 256 bytes of the AudioRecord instance for the sample-rate / channel-mask /
// format fields. AOSP's class layout puts mSampleRate, mFrameCount, mFormat,
// mChannelCount, mChannelMask early in the object; we just search for
// known constants to pin them down.
//
// Recognizable sample-rate hex:
//   0x0000BB80 = 48000     0x0000AC44 = 44100
//   0x00007D00 = 32000     0x00005622 = 22050
//   0x00003E80 = 16000     0x00001F40 =  8000
//   0x00017700 = 96000     0x0002EE00 = 192000
// Recognizable format constants (audio_format_t in audio.h):
//   AUDIO_FORMAT_PCM_16_BIT          = 0x00000001
//   AUDIO_FORMAT_PCM_8_BIT           = 0x00000002
//   AUDIO_FORMAT_PCM_32_BIT          = 0x00000003
//   AUDIO_FORMAT_PCM_8_24_BIT        = 0x00000004
//   AUDIO_FORMAT_PCM_FLOAT           = 0x00000005
//   AUDIO_FORMAT_PCM_24_BIT_PACKED   = 0x00000006
static int g_audiorecord_dumped = 0;
static void dump_audiorecord_object(const void *self) {
    if (g_audiorecord_dumped || !self) return;
    g_audiorecord_dumped = 1;
    const uint32_t *p = (const uint32_t *) self;
    LOGI("AR_DUMP this=%p, first 1024 bytes as uint32 (look for 0xBB80=48k, 0xAC44=44.1k, 0x3E80=16k, 0x7D00=32k):", self);
    for (int row = 0; row < 32; row++) {
        LOGI("AR_DUMP [+0x%03x] %08x %08x %08x %08x  %08x %08x %08x %08x",
             row * 32,
             p[row*8+0], p[row*8+1], p[row*8+2], p[row*8+3],
             p[row*8+4], p[row*8+5], p[row*8+6], p[row*8+7]);
    }
    // Also scan the whole 1024-byte range and flag any uint32 that matches
    // a known sample-rate constant. Removes the eyeball search step.
    static const struct { uint32_t v; const char *name; } kRates[] = {
        {  8000, "8k"},  {11025, "11.025k"}, {16000, "16k"},
        {22050, "22.05k"}, {32000, "32k"},  {44100, "44.1k"},
        {48000, "48k"},  {88200, "88.2k"},  {96000, "96k"},
        {176400, "176.4k"}, {192000, "192k"},
    };
    for (int i = 0; i < 256; i++) {
        for (size_t k = 0; k < sizeof(kRates)/sizeof(kRates[0]); k++) {
            if (p[i] == kRates[k].v) {
                LOGI("AR_DUMP MATCH at +0x%03x (offset %d): %u (= %s)",
                     i * 4, i * 4, p[i], kRates[k].name);
            }
        }
    }
}

// Format diagnostic — interprets the first ~16 bytes of mic data as both
// int16 and float32 and logs the values. Whichever interpretation produces
// sensible mic-noise values (int16 in ±5000 range OR float32 in ±0.05
// range, depending on level) tells us the actual PCM format the native
// AudioRecord uses. No offline ffplay required.
static int g_format_dump_done = 0;
static void format_hex_dump_once(const ar_buffer_t *audioBuffer) {
    if (g_format_dump_done >= 4) return;
    if (!audioBuffer || !audioBuffer->raw || audioBuffer->size < 32) return;
    g_format_dump_done++;
    const uint8_t *b = (const uint8_t *) audioBuffer->raw;
    const int16_t *s = (const int16_t *) audioBuffer->raw;
    const float   *f = (const float   *) audioBuffer->raw;
    LOGI("FORMAT_DUMP[%d] hex:    %02x %02x %02x %02x  %02x %02x %02x %02x  %02x %02x %02x %02x  %02x %02x %02x %02x",
         g_format_dump_done,
         b[0],  b[1],  b[2],  b[3],  b[4],  b[5],  b[6],  b[7],
         b[8],  b[9],  b[10], b[11], b[12], b[13], b[14], b[15]);
    LOGI("FORMAT_DUMP[%d] int16:  %6d %6d %6d %6d %6d %6d %6d %6d",
         g_format_dump_done,
         s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]);
    LOGI("FORMAT_DUMP[%d] float:  %+.6f %+.6f %+.6f %+.6f",
         g_format_dump_done, f[0], f[1], f[2], f[3]);
}

// Drains MP4 PCM from the native ring into the AudioRecord buffer, padding
// any remainder with silence. This is the actual substitution — without it
// the broadcast just relays mic audio. Underflow (ring empty / Mp4 not
// started yet) produces silence, which TikTok handles cleanly.
static void substitute_audio_buffer(ar_buffer_t *audioBuffer) {
    if (!audioBuffer || !audioBuffer->raw || audioBuffer->size == 0) return;
    // Passthrough: leave whatever the mic hardware captured in the buffer
    // untouched. Used by Mp4AudioProducer's "speaker" diagnostic mode so
    // the acoustic loopback (speaker → air → mic) reaches the encoder
    // through the broadcast's normal mic pathway.
    if (atomic_load_explicit(&g_passthrough, memory_order_acquire)) return;
#if DIAG_MODE == 1
    // Mic-capture mode: log hex+typed interpretations of the first few raw
    // buffers (Pond pastes log → I read format directly), snapshot what
    // TikTok handed us to disk (Pond can ffplay it as a backup), and leave
    // the buffer alone so the broadcast still carries real mic audio.
    format_hex_dump_once(audioBuffer);
    capture_write(audioBuffer->raw, audioBuffer->size);
    if (pcm_pull_log_count < PCM_PULL_LOG_BUDGET) {
        pcm_pull_log_count++;
        LOGI("capture#%d: size=%zu total_written=%zu",
             pcm_pull_log_count, audioBuffer->size, g_capture_written);
    }
    return;
#elif DIAG_MODE == 2
    // Sine-wave mode: ignore the ring, write a 440 Hz tone directly into the
    // buffer in PCM16 stereo little-endian at the confirmed native rate.
    int16_t *out = (int16_t *) audioBuffer->raw;
    size_t frames = audioBuffer->size / 4;   // PCM16 stereo = 4 bytes/frame
    for (size_t i = 0; i < frames; i++) {
        double y = sin(2.0 * M_PI * SINE_FREQ_HZ * g_sine_phase / SINE_RATE_HZ);
        int16_t s = (int16_t)(y * SINE_AMP_INT16);
        out[2 * i]     = s;
        out[2 * i + 1] = s;
        g_sine_phase += 1.0;
        if (g_sine_phase > SINE_RATE_HZ) g_sine_phase -= SINE_RATE_HZ;
    }
    if (pcm_pull_log_count < PCM_PULL_LOG_BUDGET) {
        pcm_pull_log_count++;
        LOGI("sine#%d: size=%zu frames=%zu phase=%.1f",
             pcm_pull_log_count, audioBuffer->size, frames, g_sine_phase);
    }
    return;
#endif
    size_t want = audioBuffer->size;
    size_t got = pcm_ring_read((uint8_t *)audioBuffer->raw, want);
    if (got < want) {
        memset((uint8_t *)audioBuffer->raw + got, 0, want - got);
    }
    if (pcm_pull_log_count < PCM_PULL_LOG_BUDGET) {
        pcm_pull_log_count++;
        LOGI("substitute: want=%zu got=%zu pad=%zu ring_avail_after=%zu%s",
             want, got, want - got, pcm_ring_available(),
             pcm_pull_log_count == PCM_PULL_LOG_BUDGET ? " (silencing log)" : "");
    }
}

static int hooked_AudioRecord_obtainBuffer(void *self, ar_buffer_t *audioBuffer, int32_t waitCount, size_t *nonContig) {
    if (real_AudioRecord_obtainBuffer == NULL) return -1;
    int rc = real_AudioRecord_obtainBuffer(self, audioBuffer, waitCount, nonContig);
    if (obtain_log_count < AUDIO_READ_LOG_BUDGET) {
        obtain_log_count++;
        LOGI("AudioRecord::obtainBuffer[wait](this=%p, wait=%d) → rc=%d frames=%zu size=%zu raw=%p%s",
             self, waitCount, rc,
             audioBuffer ? audioBuffer->frameCount : 0,
             audioBuffer ? audioBuffer->size : 0,
             audioBuffer ? audioBuffer->raw : NULL,
             obtain_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing log)" : "");
    }
    if (rc == 0) substitute_audio_buffer(audioBuffer);
    return rc;
}

static int hooked_AudioRecord_obtainBufferTs(void *self, ar_buffer_t *audioBuffer,
                                              const void *requested, void *elapsed,
                                              size_t *nonContig) {
    if (real_AudioRecord_obtainBufferTs == NULL) return -1;
    int rc = real_AudioRecord_obtainBufferTs(self, audioBuffer, requested, elapsed, nonContig);
    if (obtain_ts_log_count < AUDIO_READ_LOG_BUDGET) {
        obtain_ts_log_count++;
        // Log wall-clock nanoseconds so a downstream awk over consecutive
        // log lines can compute the actual sample rate:
        //   rate_hz = frames / (elapsed_ns / 1e9)
        // If TikTok asks for 48000 but native really runs at e.g. 32000,
        // our 48 kHz PCM plays 1.5× faster — Pond's reported symptom.
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        long long now_ns = (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        LOGI("AudioRecord::obtainBuffer[ts](this=%p) → rc=%d frames=%zu size=%zu raw=%p t=%lldns%s",
             self, rc,
             audioBuffer ? audioBuffer->frameCount : 0,
             audioBuffer ? audioBuffer->size : 0,
             audioBuffer ? audioBuffer->raw : NULL,
             now_ns,
             obtain_ts_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing log)" : "");
    }
    if (rc == 0) {
#if !SHIP_BUILD
        dump_audiorecord_object(self);
#endif
        substitute_audio_buffer(audioBuffer);
    }
    return rc;
}

static void hooked_AudioRecord_releaseBuffer(void *self, const ar_buffer_t *audioBuffer) {
    if (release_log_count < AUDIO_READ_LOG_BUDGET) {
        release_log_count++;
        LOGI("AudioRecord::releaseBuffer(this=%p, frames=%zu size=%zu raw=%p)%s",
             self,
             audioBuffer ? audioBuffer->frameCount : 0,
             audioBuffer ? audioBuffer->size : 0,
             audioBuffer ? audioBuffer->raw : NULL,
             release_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing log)" : "");
    }
    if (real_AudioRecord_releaseBuffer != NULL) {
        real_AudioRecord_releaseBuffer(self, audioBuffer);
    }
}

// ---------------------------------------------------------------------------
// MediaCodec NDK encoder hooks — if VolcEngine RTC uses MediaCodec for AAC
// encoding (very common pattern; their video path already uses MediaCodec),
// then PCM passes through AMediaCodec_queueInputBuffer's buffer arg.
//
// The buffer pointer comes from a prior AMediaCodec_getInputBuffer call,
// so we hook getInputBuffer to capture the buffer/size, then on
// queueInputBuffer we know what to overwrite.
//
// Signatures (libmediandk.so / NdkMediaCodec.h):
//   uint8_t* AMediaCodec_getInputBuffer(AMediaCodec*, size_t idx, size_t* out_size);
//   media_status_t AMediaCodec_queueInputBuffer(AMediaCodec*, size_t idx,
//                                                off_t offset, size_t size,
//                                                uint64_t time, uint32_t flags);
// ---------------------------------------------------------------------------

typedef uint8_t *(*AMediaCodec_getInputBuffer_t)(void *codec, size_t idx, size_t *out_size);
typedef int (*AMediaCodec_queueInputBuffer_t)(void *codec, size_t idx, long offset,
                                              size_t size, uint64_t time, uint32_t flags);

static AMediaCodec_getInputBuffer_t   real_AMediaCodec_getInputBuffer   = NULL;
static AMediaCodec_queueInputBuffer_t real_AMediaCodec_queueInputBuffer = NULL;

static int mediacodec_get_log_count = 0;
static int mediacodec_queue_log_count = 0;

static uint8_t *hooked_AMediaCodec_getInputBuffer(void *codec, size_t idx, size_t *out_size) {
    if (real_AMediaCodec_getInputBuffer == NULL) return NULL;
    uint8_t *buf = real_AMediaCodec_getInputBuffer(codec, idx, out_size);
    if (mediacodec_get_log_count < AUDIO_READ_LOG_BUDGET) {
        mediacodec_get_log_count++;
        LOGI("AMediaCodec_getInputBuffer(codec=%p, idx=%zu) → buf=%p size=%zu%s",
             codec, idx, buf, out_size ? *out_size : 0,
             mediacodec_get_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing)" : "");
    }
    return buf;
}

static int hooked_AMediaCodec_queueInputBuffer(void *codec, size_t idx, long offset,
                                                size_t size, uint64_t time, uint32_t flags) {
    if (mediacodec_queue_log_count < AUDIO_READ_LOG_BUDGET) {
        mediacodec_queue_log_count++;
        LOGI("AMediaCodec_queueInputBuffer(codec=%p, idx=%zu, off=%ld, size=%zu, pts=%llu, flags=0x%x)%s",
             codec, idx, offset, size, (unsigned long long) time, flags,
             mediacodec_queue_log_count == AUDIO_READ_LOG_BUDGET ? " (silencing)" : "");
    }
    if (real_AMediaCodec_queueInputBuffer == NULL) return -1;
    return real_AMediaCodec_queueInputBuffer(codec, idx, offset, size, time, flags);
}

// ---------------------------------------------------------------------------
// dlsym hook — diagnostic. Logs every audio-related symbol resolution that
// TikTok's libs perform at runtime. This is how we discover the actual
// intercept point: if libvolcenginertc.so calls dlsym(handle, "<symbol>")
// for an audio function, the symbol name appears in our log.
//
// Filter: only log if the symbol name contains "audio", "Audio", "AAudio",
// "OpenSL", "SL_", "PCM", "Record", "Capture", "Encode", "Codec", "FLAC",
// "AAC", "Opus" — otherwise the log floods at ~100/s during steady state.
// ---------------------------------------------------------------------------

typedef void *(*dlsym_t)(void *handle, const char *name);
static dlsym_t real_dlsym = NULL;

#define DLSYM_LOG_BUDGET 200
static int dlsym_log_count = 0;

static int audio_symbol_match(const char *name) {
    if (!name) return 0;
    // Cheap substring check, case-sensitive — names are stable mangling.
    static const char *const kHits[] = {
        "audio", "Audio", "AAudio", "OpenSL", "SL_", "slCreate",
        "PCM", "pcm", "Record", "Capture", "Encode", "Codec",
        "AAC", "Opus", "FLAC", "AudioRecord", "obtainBuffer",
        "releaseBuffer", "MediaCodec", "BufferQueue", "Enqueue",
        // Phase 6 video encoder recon strings removed — they added log
        // volume without yielding useful info, and removing them coincides
        // with restoring the working Option G state.
    };
    for (size_t i = 0; i < sizeof(kHits)/sizeof(kHits[0]); i++) {
        if (strstr(name, kHits[i])) return 1;
    }
    return 0;
}

static void *hooked_dlsym(void *handle, const char *name) {
    if (real_dlsym == NULL) return NULL;
    void *result = real_dlsym(handle, name);
    if (name && audio_symbol_match(name) && dlsym_log_count < DLSYM_LOG_BUDGET) {
        dlsym_log_count++;
        LOGI("dlsym(handle=%p, '%s') → %p%s",
             handle, name, result,
             dlsym_log_count == DLSYM_LOG_BUDGET ? " (silencing)" : "");
    }
    return result;
}

// ---------------------------------------------------------------------------
// Target libs — every native lib in TikTok we've spotted importing OpenSL ES.
// xhook can register pending hooks before the lib is dlopen()'d; once the lib
// loads its imports get rewritten on the next xhook_refresh() call.
// ---------------------------------------------------------------------------
static const char *const k_audio_target_libs[] = {
        "libvolcenginertc.so",
        "libvolcenginertc_plugin.so",
        "libnativeaudio.so",
        "libkryptonaudio.so",
        "libaudioeffect.so",
};

// Logs every .so currently mapped in our (== TikTok's) process by reading
// /proc/self/maps. Lets us see which RTC / audio libs actually loaded
// without trying to peek at /proc/PID/maps from adb shell (SELinux blocks
// that on production builds).
static void dump_loaded_libs(const char *interesting_substring) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) {
        LOGW("fopen(/proc/self/maps) failed");
        return;
    }
    char line[1024];
    int matched = 0;
    while (fgets(line, sizeof(line), f)) {
        char *so = strstr(line, ".so");
        if (!so) continue;
        if (interesting_substring && interesting_substring[0] != '\0') {
            if (!strstr(line, interesting_substring)) continue;
        }
        // Trim to filename
        char *slash = strrchr(line, '/');
        if (!slash) continue;
        // Truncate at first whitespace after .so
        char *end = strchr(slash, '\n');
        if (end) *end = '\0';
        LOGI("  loaded: %s", slash);
        matched++;
        if (matched > 80) {
            LOGI("  ...(truncated)");
            break;
        }
    }
    fclose(f);
    LOGI("loaded libs (filter='%s'): %d match(es)",
         interesting_substring ? interesting_substring : "<all .so>", matched);
}

JNIEXPORT jboolean JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_install0(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    // Diagnostic dumps before hooking so the next iteration knows whether
    // the audio libs are actually loaded by the time install0 runs.
    LOGI("/proc/self/maps audio/RTC libs:");
    dump_loaded_libs("audio");
    dump_loaded_libs("rtc");
    dump_loaded_libs("OpenSL");
    dump_loaded_libs("volcen");

    int registered = 0;
    const size_t n_libs = sizeof(k_audio_target_libs) / sizeof(k_audio_target_libs[0]);
    for (size_t i = 0; i < n_libs; i++) {
        char pattern[256];
        snprintf(pattern, sizeof(pattern), ".*/%s$", k_audio_target_libs[i]);
        int rc = xhook_register(
                pattern,
                "slCreateEngine",
                (void *) hooked_slCreateEngine,
                (void **) &real_slCreateEngine);
        if (rc == 0) {
            LOGI("registered slCreateEngine hook for %s", k_audio_target_libs[i]);
            registered++;
        } else {
            LOGW("xhook_register(%s, slCreateEngine) = %d",
                 k_audio_target_libs[i], rc);
        }
    }

    // Catch-all pattern — any .so anywhere that imports slCreateEngine.
    // Useful when the audio code lives in a lib we didn't spot during recon
    // (or the path differs between Samsung / Pixel / etc).
    int rc_any = xhook_register(
            ".*\\.so$",
            "slCreateEngine",
            (void *) hooked_slCreateEngine,
            (void **) &real_slCreateEngine);
    LOGI("xhook_register(.*\\.so$, slCreateEngine) = %d", rc_any);

    // -----------------------------------------------------------------
    // android::AudioRecord::read PLT hooks. We register against the
    // catch-all (.*\.so$) plus the RTC libs explicitly. xhook silently
    // skips libs that don't import the symbol so this is cheap.
    //
    // Two mangled variants — modern (3-arg, blocking flag) is the live
    // signature on Android 12+; we also register the legacy 2-arg form
    // in case a vendor build pinned an older header.
    // -----------------------------------------------------------------
    int rc_read3 = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecord4readEPvmb",
            (void *) hooked_AudioRecord_read3,
            (void **) &real_AudioRecord_read3);
    LOGI("xhook_register(.*\\.so$, AudioRecord::read 3-arg) = %d", rc_read3);

    int rc_read2 = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecord4readEPvm",
            (void *) hooked_AudioRecord_read2,
            (void **) &real_AudioRecord_read2);
    LOGI("xhook_register(.*\\.so$, AudioRecord::read 2-arg) = %d", rc_read2);

    // AAudio path — modern, no name mangling because it's a C API.
    int rc_aaudio = xhook_register(
            ".*\\.so$",
            "AAudioStream_read",
            (void *) hooked_AAudioStream_read,
            (void **) &real_AAudioStream_read);
    LOGI("xhook_register(.*\\.so$, AAudioStream_read) = %d", rc_aaudio);

    // AudioRecord C++ constructor — force every AudioRecord TikTok creates
    // to use AUDIO_SOURCE_UNPROCESSED (9) instead of VOICE_COMMUNICATION
    // (7). The latter is what TikTok asks for on LIVE and it triggers the
    // HAL's mandatory AGC/ANS/AEC chain, which corrupts every non-voice
    // signal we substitute into the broadcast PCM. UNPROCESSED bypasses
    // the entire DSP chain so our raw MP4 PCM reaches the encoder intact.
    //
    // Trampoline rewrites x1 (audio_source_t) to 9, then tail-calls the
    // real ctor with the rest of x2..x7 + stack args preserved.
    int rc_ctor = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecordC1E14audio_source_tj14audio_format_t20audio_channel_mask_tRKNS_7content22AttributionSourceStateEmRKNS_2wpINS0_20IAudioRecordCallbackEEEj15audio_session_tNS0_13transfer_typeE19audio_input_flags_tPK18audio_attributes_ti28audio_microphone_direction_tf",
            (void *) hooked_AudioRecord_ctor_thunk,
            (void **) &real_AudioRecord_ctor);
    LOGI("xhook_register(.*\\.so$, AudioRecord ctor C1) = %d", rc_ctor);

    // AudioRecord buffer protocol — the actual PCM transfer path inside
    // libaudioclient.so. OpenSL ES recorder uses this under the hood.
    int rc_obtain = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiPm",
            (void *) hooked_AudioRecord_obtainBuffer,
            (void **) &real_AudioRecord_obtainBuffer);
    LOGI("xhook_register(.*\\.so$, AudioRecord::obtainBuffer[wait]) = %d", rc_obtain);

    int rc_obtain_ts = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pm",
            (void *) hooked_AudioRecord_obtainBufferTs,
            (void **) &real_AudioRecord_obtainBufferTs);
    LOGI("xhook_register(.*\\.so$, AudioRecord::obtainBuffer[ts]) = %d", rc_obtain_ts);

    int rc_release = xhook_register(
            ".*\\.so$",
            "_ZN7android11AudioRecord13releaseBufferEPKNS0_6BufferE",
            (void *) hooked_AudioRecord_releaseBuffer,
            (void **) &real_AudioRecord_releaseBuffer);
    LOGI("xhook_register(.*\\.so$, AudioRecord::releaseBuffer) = %d", rc_release);

    // MediaCodec NDK — if VolcEngine RTC uses MediaCodec for AAC, PCM
    // passes through getInputBuffer / queueInputBuffer.
    int rc_mc_get = xhook_register(
            ".*\\.so$",
            "AMediaCodec_getInputBuffer",
            (void *) hooked_AMediaCodec_getInputBuffer,
            (void **) &real_AMediaCodec_getInputBuffer);
    LOGI("xhook_register(.*\\.so$, AMediaCodec_getInputBuffer) = %d", rc_mc_get);

    int rc_mc_queue = xhook_register(
            ".*\\.so$",
            "AMediaCodec_queueInputBuffer",
            (void *) hooked_AMediaCodec_queueInputBuffer,
            (void **) &real_AMediaCodec_queueInputBuffer);
    LOGI("xhook_register(.*\\.so$, AMediaCodec_queueInputBuffer) = %d", rc_mc_queue);

    // dlsym — diagnostic. Logs audio-related symbol resolutions so we can
    // discover what TikTok's RTC code actually looks up at runtime. Scope
    // restricted to libvolcenginertc.so + friends to keep volume bounded;
    // the system libs would flood logcat.
    for (size_t i = 0; i < n_libs; i++) {
        char pattern[256];
        snprintf(pattern, sizeof(pattern), ".*/%s$", k_audio_target_libs[i]);
        int rc_dl = xhook_register(
                pattern,
                "dlsym",
                (void *) hooked_dlsym,
                (void **) &real_dlsym);
        LOGI("xhook_register(%s, dlsym) = %d", k_audio_target_libs[i], rc_dl);
    }

    // Option B: rtmp_client_push_audio / push_video. libvolcenginertc_plugin.so
    // imports both via PLT (R_AARCH64_JUMP_SLOT confirmed via objdump -R).
    // Register against catch-all so any future caller is also intercepted.
    int rc_rtmp_audio = xhook_register(
            ".*\\.so$",
            "rtmp_client_push_audio",
            (void *) hooked_rtmp_client_push_audio,
            (void **) &real_rtmp_client_push_audio);
    LOGI("xhook_register(.*\\.so$, rtmp_client_push_audio) = %d", rc_rtmp_audio);

    int rc_rtmp_video = xhook_register(
            ".*\\.so$",
            "rtmp_client_push_video",
            (void *) hooked_rtmp_client_push_video,
            (void **) &real_rtmp_client_push_video);
    LOGI("xhook_register(.*\\.so$, rtmp_client_push_video) = %d", rc_rtmp_video);

    // Option G: aacEncEncode PLT hook on libvolcenginertc.so. This is the
    // libfdk-aac entry point for AAC encoding — libvolcenginertc.so imports
    // it (R_AARCH64_JUMP_SLOT confirmed via objdump -R). Every AAC encode
    // call from TikTok's encoder transits this PLT slot, giving us a clean
    // intercept BEFORE the encoded bytes reach the transport layer. Voice
    // DSP runs upstream of the encoder so substitution here bypasses DSP
    // entirely.
    //
    // Phase 1.8 = diagnostic-only (log fires + first bytes of each frame).
    // Phase 3 = enable substitution when g_rtmp_inject_enabled is set.
    int rc_aac_enc = xhook_register(
            ".*/libvolcenginertc\\.so$",
            "aacEncEncode",
            (void *) hooked_aacEncEncode,
            (void **) &real_aacEncEncode);
    LOGI("xhook_register(libvolcenginertc.so, aacEncEncode) = %d", rc_aac_enc);
    // Also register catch-all so plugin builds that re-import via PLT are
    // covered.
    int rc_aac_enc_any = xhook_register(
            ".*\\.so$",
            "aacEncEncode",
            (void *) hooked_aacEncEncode,
            (void **) &real_aacEncEncode);
    LOGI("xhook_register(.*\\.so$, aacEncEncode) = %d", rc_aac_enc_any);

    int refresh = xhook_refresh(0);
    LOGI("xhook_refresh = %d (registered %d/%zu specific libs + catch-all)",
         refresh, registered, n_libs);

    // shadowhook inline-hook attempt was reverted — its .init_array hook
    // setup crashed TikTok with SIGSEGV on Samsung A15 Android 16.
    // Phase 1.7 will try a different approach (manual ARM64 inline
    // patching, or vendoring Dobby which has a more conservative init).
    return registered > 0 ? JNI_TRUE : JNI_FALSE;
}

// Triggered from Kotlin so we can re-scan after the LIVE flow starts (the
// RTC libs are typically dlopen()'d lazily; calling this once the operator
// has tapped "Go LIVE" picks up the new mappings).
JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_refresh0(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    LOGI("manual refresh requested — re-scanning loaded libs");
    dump_loaded_libs("audio");
    dump_loaded_libs("rtc");
    dump_loaded_libs("volcen");
    int refresh = xhook_refresh(0);
    LOGI("xhook_refresh (manual) = %d", refresh);
}

// Pushes a chunk of decoded MP4 PCM into the native ring buffer. Called
// from Mp4AudioProducer.appendToRing per decoded chunk; the audio thread
// inside libaudioclient.so drains it in obtainBuffer's after-hook.
//
// Data must be PCM16 little-endian at whatever rate/channels TikTok's
// AudioRecord is configured for — Mp4AudioProducer already resamples to
// the configured target before calling us, so we just memcpy.
JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_writePcm0(
        JNIEnv *env, jclass clazz, jbyteArray data, jint length) {
    (void) clazz;
    if (data == NULL || length <= 0) return;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (src == NULL) return;
    pcm_ring_write((const uint8_t *) src, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
}

// Diagnostic: how many bytes are currently buffered in the native ring.
// Lets Mp4AudioProducer skip a push when the ring is already saturated, so
// the producer doesn't run ahead of the broadcast and waste decoded PCM.
JNIEXPORT jint JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_ringAvailable0(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) pcm_ring_available();
}

// Toggle the obtainBuffer hook's substitution. When set to 1, the hook
// leaves the mic-captured PCM in the buffer untouched; when 0 (default),
// it overwrites with our ring contents. Used for the "speaker" loopback
// diagnostic mode in Mp4AudioProducer.
JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_setPassthrough0(
        JNIEnv *env, jclass clazz, jboolean passthrough) {
    (void) env;
    (void) clazz;
    atomic_store_explicit(&g_passthrough,
                          passthrough ? 1 : 0,
                          memory_order_release);
    LOGI("passthrough mode = %s", passthrough ? "ON (skip substitute)" : "OFF (substitute)");
}

// ---------------------------------------------------------------------------
// Option B: RTMP-layer AAC injection.
//
// libvolcenginertc.so exports rtmp_client_push_audio — this is the entry
// point where pre-encoded AAC frames are handed to the RTMP muxer just
// before they go on the wire. libvolcenginertc_plugin.so imports the symbol
// via PLT (R_AARCH64_JUMP_SLOT relocation confirmed), so PLT-hooking it
// catches the production audio path without inline hooks.
//
// Strategy:
//  - Diagnostic mode (always-on): log size + first 8 bytes + pts for the
//    first ~64 calls so we can verify the hook fires and inspect the AAC
//    payload format TikTok pushes.
//  - Injection mode (gated by g_rtmp_inject_enabled): pop next pre-encoded
//    AAC frame from g_aac_ring and substitute payload pointer + size while
//    keeping the handle + pts intact. TikTok continues to encode mic input
//    (typically silence in this mode); the encoder's frame cadence stays
//    canonical and we just swap the bits that go into the RTMP packet.
//
// Function signature inferred from disassembly at libvolcenginertc.so
// 0x9c9b98 — small thunk that builds a small metadata header on the stack
// then tail-calls an internal push routine:
//
//   void rtmp_client_push_audio(void* handle, void* data, uint32_t size,
//                                uint32_t pts);
//
// The handle is the rtmp_client opaque pointer (created via
// rtmp_client_create). `data` is the AAC payload. `size` is the byte
// count. `pts` is the presentation timestamp in milliseconds (FLV-style).
// ---------------------------------------------------------------------------

// Definitions for the typedefs + globals forward-declared near the top
// of the file. real_* fall back to NULL because they have no initialiser
// in the forward decl (static storage = zero-init by default).
static _Atomic int g_rtmp_inject_enabled = 0;

#define RTMP_LOG_BUDGET 64
static int rtmp_audio_log_count = 0;
static int rtmp_video_log_count = 0;
static int rtmp_audio_inject_count = 0;
static int rtmp_audio_underrun_count = 0;

// AAC frame ring — fixed-capacity circular buffer of variable-size AAC
// access units. Producer (Java Mp4AacProducer) pushes one access unit per
// MP4 audio sample; consumer (rtmp_client_push_audio hook) pops one per
// outgoing RTMP audio packet. SPSC across producer/audio threads.
//
// 256 slots × max 8 KB/frame = 2 MB worst case. Typical AAC-LC frame at
// 128 kbps stereo is ~340 bytes, so this holds ~5 seconds of audio —
// enough to ride out producer hiccups without underrun.
#define AAC_RING_SLOTS    256
#define AAC_FRAME_MAX     8192

typedef struct {
    uint32_t size;     // 0 = empty slot
    uint8_t data[AAC_FRAME_MAX];
} aac_slot_t;

static aac_slot_t g_aac_ring[AAC_RING_SLOTS];
static _Atomic uint32_t g_aac_write = 0;
static _Atomic uint32_t g_aac_read  = 0;

static inline uint32_t aac_ring_count(void) {
    uint32_t w = atomic_load_explicit(&g_aac_write, memory_order_relaxed);
    uint32_t r = atomic_load_explicit(&g_aac_read,  memory_order_relaxed);
    return w - r;  // unsigned wrap handles overflow
}

// Producer-side push. Drop-oldest on overflow so we never block the Java
// pre-extract loop. Returns 1 on accept, 0 on truncation (size > MAX).
static int aac_ring_push(const uint8_t *data, uint32_t size) {
    if (size == 0 || size > AAC_FRAME_MAX) return 0;
    uint32_t w = atomic_load_explicit(&g_aac_write, memory_order_relaxed);
    uint32_t r = atomic_load_explicit(&g_aac_read,  memory_order_acquire);
    if (w - r >= AAC_RING_SLOTS) {
        // Full → drop oldest by advancing read cursor.
        atomic_store_explicit(&g_aac_read, r + 1, memory_order_release);
    }
    aac_slot_t *slot = &g_aac_ring[w & (AAC_RING_SLOTS - 1)];
    memcpy(slot->data, data, size);
    slot->size = size;
    atomic_store_explicit(&g_aac_write, w + 1, memory_order_release);
    return 1;
}

// Consumer-side pop into caller's stable buffer (the rtmp_client expects
// the data pointer to remain valid until the call returns — we copy into
// the caller's thread-local scratch). Returns size, or 0 if empty.
static uint32_t aac_ring_pop(uint8_t *out, uint32_t out_max) {
    uint32_t r = atomic_load_explicit(&g_aac_read,  memory_order_relaxed);
    uint32_t w = atomic_load_explicit(&g_aac_write, memory_order_acquire);
    if (r == w) return 0;
    aac_slot_t *slot = &g_aac_ring[r & (AAC_RING_SLOTS - 1)];
    uint32_t size = slot->size;
    if (size == 0 || size > out_max) {
        // Stale or oversize — skip.
        atomic_store_explicit(&g_aac_read, r + 1, memory_order_release);
        return 0;
    }
    memcpy(out, slot->data, size);
    atomic_store_explicit(&g_aac_read, r + 1, memory_order_release);
    return size;
}

static void hooked_rtmp_client_push_audio(void *handle, void *data,
                                          uint32_t size, uint32_t pts) {
    // Diagnostic log: first byte often distinguishes ADTS (0xFF) from raw
    // AAC AU (top bits typically not all 1s) from FLV audio tag header
    // (0xAF for AAC sequence header / raw frame).
    if (rtmp_audio_log_count < RTMP_LOG_BUDGET) {
        rtmp_audio_log_count++;
        const uint8_t *b = (const uint8_t *) data;
        uint8_t b0 = (data && size > 0) ? b[0] : 0;
        uint8_t b1 = (data && size > 1) ? b[1] : 0;
        uint8_t b2 = (data && size > 2) ? b[2] : 0;
        uint8_t b3 = (data && size > 3) ? b[3] : 0;
        uint8_t b4 = (data && size > 4) ? b[4] : 0;
        uint8_t b5 = (data && size > 5) ? b[5] : 0;
        uint8_t b6 = (data && size > 6) ? b[6] : 0;
        uint8_t b7 = (data && size > 7) ? b[7] : 0;
        LOGI("rtmp_push_audio#%d handle=%p size=%u pts=%u "
             "bytes[0..7]=%02x %02x %02x %02x %02x %02x %02x %02x%s",
             rtmp_audio_log_count, handle, size, pts,
             b0, b1, b2, b3, b4, b5, b6, b7,
             rtmp_audio_log_count == RTMP_LOG_BUDGET ? " (silencing)" : "");
    }

    if (atomic_load_explicit(&g_rtmp_inject_enabled, memory_order_acquire)) {
        // Pop the next pre-encoded AAC frame from our ring. Use stack
        // scratch so the buffer lifetime covers the real call exactly.
        uint8_t scratch[AAC_FRAME_MAX];
        uint32_t aac_size = aac_ring_pop(scratch, sizeof(scratch));
        if (aac_size > 0) {
            rtmp_audio_inject_count++;
            if (rtmp_audio_inject_count <= 8 || rtmp_audio_inject_count % 100 == 0) {
                LOGI("INJECT#%d size=%u (was %u) pts=%u remaining=%u",
                     rtmp_audio_inject_count, aac_size, size, pts,
                     aac_ring_count());
            }
            if (real_rtmp_client_push_audio) {
                real_rtmp_client_push_audio(handle, scratch, aac_size, pts);
            }
            return;
        } else {
            rtmp_audio_underrun_count++;
            if (rtmp_audio_underrun_count <= 8 || rtmp_audio_underrun_count % 200 == 0) {
                LOGW("underrun#%d (no AAC ready) — passthrough orig size=%u",
                     rtmp_audio_underrun_count, size);
            }
        }
    }

    if (real_rtmp_client_push_audio) {
        real_rtmp_client_push_audio(handle, data, size, pts);
    }
}

static void hooked_rtmp_client_push_video(void *handle, void *data,
                                          uint32_t size, uint32_t pts) {
    // Diagnostic only — we never modify video at the RTMP layer.
    if (rtmp_video_log_count < RTMP_LOG_BUDGET) {
        rtmp_video_log_count++;
        const uint8_t *b = (const uint8_t *) data;
        uint8_t b0 = (data && size > 0) ? b[0] : 0;
        uint8_t b1 = (data && size > 1) ? b[1] : 0;
        uint8_t b2 = (data && size > 2) ? b[2] : 0;
        uint8_t b3 = (data && size > 3) ? b[3] : 0;
        LOGI("rtmp_push_video#%d handle=%p size=%u pts=%u bytes[0..3]=%02x %02x %02x %02x%s",
             rtmp_video_log_count, handle, size, pts, b0, b1, b2, b3,
             rtmp_video_log_count == RTMP_LOG_BUDGET ? " (silencing)" : "");
    }
    if (real_rtmp_client_push_video) {
        real_rtmp_client_push_video(handle, data, size, pts);
    }
}

JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_setRtmpInjectEnabled0(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    (void) env;
    (void) clazz;
    atomic_store_explicit(&g_rtmp_inject_enabled,
                          enabled ? 1 : 0,
                          memory_order_release);
    LOGI("RTMP AAC injection = %s (ring=%u frames queued)",
         enabled ? "ENABLED" : "disabled", aac_ring_count());
}

JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_pushAacFrame0(
        JNIEnv *env, jclass clazz, jbyteArray data, jint length) {
    (void) clazz;
    if (data == NULL || length <= 0 || length > AAC_FRAME_MAX) return;
    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (src == NULL) return;
    aac_ring_push((const uint8_t *) src, (uint32_t) length);
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_clearAacRing0(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // Single-writer reset is safe: even if the consumer reads stale w/r
    // values mid-update it just sees an empty or full ring momentarily.
    atomic_store_explicit(&g_aac_read,  0, memory_order_release);
    atomic_store_explicit(&g_aac_write, 0, memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_rerun_tiktokvcam_NativeAudioHook_aacRingFrames0(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) aac_ring_count();
}

// ---------------------------------------------------------------------------
// Option G — aacEncEncode PLT hook (Phase 1.8 diagnostic + Phase 3
// substitution).
//
// libfdk-aac's AACENC_OutArgs layout (we only need offset 0 = numOutBytes):
//   struct AACENC_OutArgs {
//       INT numOutBytes;       // offset 0  — bytes written to output buffer
//       INT numInSamples;      // offset 4
//       INT numAncBytes;       // offset 8
//       INT bitResState;       // offset 12
//   };
//
// AACENC_BufDesc layout (we need outBufDesc->bufs[0] which is the output
// pointer):
//   struct AACENC_BufDesc {
//       INT numBufs;
//       void **bufs;
//       INT *bufferIdentifiers;
//       INT *bufSizes;
//       INT *bufElSizes;
//   };
//
// On arm64 (AArch64 ABI, LP64) INT == 32-bit, pointers == 64-bit. Struct
// padding follows natural alignment.
typedef struct {
    int   numBufs;       // offset 0  (4 bytes)
    int   _pad0;         // offset 4  (alignment)
    void **bufs;         // offset 8  (8 bytes — points to array of pointers)
    int  *bufferIdentifiers;
    int  *bufSizes;
    int  *bufElSizes;
} aacenc_bufdesc_t;

// Per-call budget so logcat doesn't flood during steady-state.
#define AAC_ENC_LOG_BUDGET 32
static int aac_enc_log_count = 0;
static int aac_enc_substitute_count = 0;
static int aac_enc_underrun_count = 0;

static int hooked_aacEncEncode(void *h, const void *in, const void *out,
                               const void *inargs, void *outargs) {
    if (real_aacEncEncode == NULL) return -1;
    int rc = real_aacEncEncode(h, in, out, inargs, outargs);

    // Read numOutBytes from outargs (first int field).
    int numOutBytes = 0;
    if (outargs) numOutBytes = *(const int *)outargs;

    // Try to grab the output buffer pointer for diagnostic + substitution.
    void *outBuf = NULL;
    int   outBufSize = 0;
    const aacenc_bufdesc_t *outDesc = (const aacenc_bufdesc_t *) out;
    if (outDesc && outDesc->numBufs > 0 && outDesc->bufs) {
        outBuf = outDesc->bufs[0];
        if (outDesc->bufSizes) outBufSize = outDesc->bufSizes[0];
    }

    // Diagnostic log of the first ~32 fires so we can confirm the hook
    // is on the right path + inspect frame format.
    if (aac_enc_log_count < AAC_ENC_LOG_BUDGET) {
        aac_enc_log_count++;
        const uint8_t *b = (const uint8_t *) outBuf;
        uint8_t b0 = (outBuf && numOutBytes > 0) ? b[0] : 0;
        uint8_t b1 = (outBuf && numOutBytes > 1) ? b[1] : 0;
        uint8_t b2 = (outBuf && numOutBytes > 2) ? b[2] : 0;
        uint8_t b3 = (outBuf && numOutBytes > 3) ? b[3] : 0;
        uint8_t b4 = (outBuf && numOutBytes > 4) ? b[4] : 0;
        uint8_t b5 = (outBuf && numOutBytes > 5) ? b[5] : 0;
        uint8_t b6 = (outBuf && numOutBytes > 6) ? b[6] : 0;
        uint8_t b7 = (outBuf && numOutBytes > 7) ? b[7] : 0;
        LOGI("aacEncEncode#%d rc=%d numOutBytes=%d outBuf=%p outBufSize=%d "
             "bytes[0..7]=%02x %02x %02x %02x %02x %02x %02x %02x%s",
             aac_enc_log_count, rc, numOutBytes, outBuf, outBufSize,
             b0, b1, b2, b3, b4, b5, b6, b7,
             aac_enc_log_count == AAC_ENC_LOG_BUDGET ? " (silencing)" : "");
    }

    // Phase 3 substitution: if RTMP-inject mode is on AND we have an AAC
    // frame ready in g_aac_ring AND TikTok's encoder produced a non-empty
    // output buffer, overwrite the bytes in place.
    if (atomic_load_explicit(&g_rtmp_inject_enabled, memory_order_acquire) &&
        outBuf && outBufSize > 0 && numOutBytes > 0) {
        uint8_t scratch[AAC_FRAME_MAX];
        uint32_t aac_size = aac_ring_pop(scratch, sizeof(scratch));
        if (aac_size > 0) {
            uint32_t copy = aac_size <= (uint32_t) outBufSize
                            ? aac_size : (uint32_t) outBufSize;
            memcpy(outBuf, scratch, copy);
            // Update numOutBytes in outargs (first int).
            if (outargs) *(int *) outargs = (int) copy;
            aac_enc_substitute_count++;
            if (aac_enc_substitute_count <= 8 ||
                aac_enc_substitute_count % 100 == 0) {
                LOGI("AAC SUBSTITUTE #%d  was=%d → now=%u  outBufSize=%d  ring_left=%u",
                     aac_enc_substitute_count, numOutBytes, copy, outBufSize,
                     aac_ring_count());
            }
        } else {
            aac_enc_underrun_count++;
            if (aac_enc_underrun_count <= 8 ||
                aac_enc_underrun_count % 200 == 0) {
                LOGW("AAC underrun #%d — passthrough orig %d bytes",
                     aac_enc_underrun_count, numOutBytes);
            }
        }
    }
    return rc;
}
