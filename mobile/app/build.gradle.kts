import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rerun.tiktokrerun"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rerun.tiktokrerun"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Don't compress the TFLite model — Interpreter mmaps it from the APK.
    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Media3 ExoPlayer (looping video playback)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Networking — WebSocket + HTTP download for web control
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines (used by SharedFlow / lifecycleScope)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR code scanning (pair token via camera)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // TFLite — CAPTCHA solver (YOLOv5 jigsaw/arrow detector)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
