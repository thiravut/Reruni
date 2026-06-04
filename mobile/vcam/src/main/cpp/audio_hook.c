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
#include <android/log.h>

#include "xhook/xhook.h"

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
#define AUDIO_READ_LOG_BUDGET 12
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

// arm64 trampoline: load forced source into x1, then jump to real ctor.
// All other registers (x0=this, x2..x7=remaining args, stack=overflow
// args) flow through untouched. This is the only safe way to mutate one
// arg of a variadic C++ ctor without knowing the rest of the signature.
#if defined(__aarch64__)
__attribute__((naked, used))
static void hooked_AudioRecord_ctor_thunk(void) {
    __asm__ volatile(
        // Force x1 = AUDIO_SOURCE_UNPROCESSED (9).
        "mov    x1, #9                  \n"
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
    // 32-bit ARM equivalent: r1 carries audio_source_t (after r0=this).
    __asm__ volatile(
        "mov    r1, #9                                  \n"
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
        LOGI("AudioRecord::obtainBuffer[ts](this=%p) → rc=%d frames=%zu size=%zu raw=%p%s",
             self, rc,
             audioBuffer ? audioBuffer->frameCount : 0,
             audioBuffer ? audioBuffer->size : 0,
             audioBuffer ? audioBuffer->raw : NULL,
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

    int refresh = xhook_refresh(0);
    LOGI("xhook_refresh = %d (registered %d/%zu specific libs + catch-all)",
         refresh, registered, n_libs);
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
