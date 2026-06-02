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

#include <jni.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
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
