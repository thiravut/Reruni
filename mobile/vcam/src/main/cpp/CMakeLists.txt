cmake_minimum_required(VERSION 3.22)
project(vcam_native LANGUAGES C CXX ASM)

# xhook (PLT hook, vendored from github.com/iqiyi/xhook, MIT license).
# We list source files explicitly so the build doesn't pick up
# the original xh_jni.c — we ship our own JNI bridge below.
set(XHOOK_SOURCES
        xhook/xhook.c
        xhook/xh_core.c
        xhook/xh_elf.c
        xhook/xh_log.c
        xhook/xh_util.c
        xhook/xh_version.c)

# shadowhook compilation reverted — its .init_array initialisation
# crashed TikTok with SIGSEGV in tp-default-10 thread on Samsung A15
# Android 16. Phase 1.7 alternatives:
#   - Dobby (jmpolecat/Dobby): more conservative init, broader Android support
#   - Manual ARM64 prologue patching (no library): write our own minimal
#     inline-hook using mprotect+memcpy for a 4-instruction trampoline
#   - Hook libfdk-aac's encoder output by PLT inside libvolcenginertc.so
#     (we already tried catch-all but maybe specific-lib pattern works)
add_library(vcam_native SHARED
        ${XHOOK_SOURCES}
        audio_hook.c)

target_include_directories(vcam_native PRIVATE xhook)

# xhook drops debug helpers on Android logcat under XH_LOG_TAG so all
# its internal noise is greppable next to ours.
target_compile_definitions(vcam_native PRIVATE
        XH_LOG_TAG="TiktokRerunVCam"
        _GNU_SOURCE
        XHOOK_RB_VERIFY_SETTING=0
        # NDK r26+ removed PAGE_SIZE / PAGE_MASK from <asm/page.h> in
        # preparation for 16 KB page support; xhook predates that and uses
        # them in PAGE_START / PAGE_END / PAGE_COVER. Restore the classic
        # 4 KB defaults — every current Samsung A1x ships 4 KB pages. If we
        # ever hit a 16 KB device we'll switch to runtime sysconf().
        PAGE_SIZE=4096
        PAGE_MASK=0xfffffffffffff000UL)

target_compile_options(vcam_native PRIVATE
        # shadowhook's own CMakeLists uses -Werror -Weverything but its
        # vendored third_party tree triggers warnings under our combined
        # build. Disable warnings-as-errors so the third_party noise
        # doesn't kill our build; we still pass our own -Wall.
        -Wall -Wno-unused-function -Wno-error -fvisibility=hidden)

target_link_libraries(vcam_native
        PRIVATE log)
