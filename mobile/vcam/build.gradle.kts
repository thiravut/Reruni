import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rerun.tiktokvcam"
    compileSdk = 34
    // Pin NDK + CMake to the versions already on disk under
    // ~/Library/Android/sdk so AGP doesn't try to re-download via the
    // SDK Manager (Pond's installed SDK XML is v4 which AGP 8.x can't
    // parse — install hangs on "Preparing Install NDK…" forever).
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.rerun.tiktokvcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Native audio hook lives in src/main/cpp. xhook (MIT, vendored) +
        // a tiny C bridge that hooks OpenSL ES imports in TikTok's RTC libs
        // so we can intercept LIVE-broadcast audio capture — which goes
        // through native code that Java AudioRecord hooks can't reach.
        ndk {
            // arm64 only — the AudioRecord ctor PLT hook uses a naked arm64
            // trampoline (forces audio_source_t to UNPROCESSED via x1
            // rewrite + tail-call), and the arm32 equivalent would need a
            // PIC-safe literal pool that's not worth the build complexity
            // for devices we don't target. All real test devices (Samsung
            // A1x, Pixel 6a) are arm64.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Use whatever CMake the AGP toolchain already has bundled
            // (drop the version requirement that triggered SDK-Manager
            // re-download attempts).
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Xposed API — compileOnly because the framework provides at runtime
    compileOnly(files("libs/api-82.jar"))

    // Option G WebSocket client: OkHttp's built-in WebSocket. Mp4GWsClient
    // streams PC-encoded AAC frames into the mobile AAC ring; the
    // aacEncEncode PLT hook then substitutes per-frame. OkHttp is small
    // (~600 KB) and supports binary frames.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
