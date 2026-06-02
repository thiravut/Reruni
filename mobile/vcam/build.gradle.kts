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
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
}
