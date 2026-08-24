// android/verify-recorder-only — a synthetic, never-published minimal-consumer harness (plan 08-04).
//
// Its ENTIRE job is to prove, mechanically, that a consumer importing ONLY
// io.github.ygaray.sttengine.recorder.* — never io.github.ygaray.sttengine.SttEngine/createSttEngine
// — produces a minified release build whose R8 mapping.txt names no recognition-only internal class
// (SttEngineImpl, RecognizerHandle, PlatformRecognizerHandle, RecognizerIntents, RecognizerBundles).
// This is the BEST CASE the single-:stt-AAR + consumer-R8 approach (D-03) can offer for REC-05 — it
// does not (and per 08-RESEARCH.md's Pitfall 4/Assumption A3 cannot) prove anything for a debug or
// unminified consumer build. See check-r8-mapping.sh's own header for both caveats this harness
// leaves open rather than silently resolving.
//
// NEVER add `maven-publish` here, and never name this module in android/jitpack.yml — same
// "outside the published module set" status as :demo (T-08-09).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.ygaray.sttengine.verifyrecorderonly"
    // Matches :stt's compileSdk exactly (read from android/stt/build.gradle.kts) — this harness
    // proves something about :stt's consumer, so it must build against the same SDK line.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ygaray.sttengine.verifyrecorderonly"
        // Matches :stt's minSdk exactly — a real consumer of :stt can never be older than this floor.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // THE one place in the whole repo where minification is intentionally turned ON: this
            // module exists to prove the best case, not to be a realistic baseline. Nothing here
            // keeps anything alive by accident — proguard-rules.pro is empty on purpose (see that
            // file's own comment); a keep rule here would make check-r8-mapping.sh's assertions
            // meaningless.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The ONLY dependency, and MinimalRecorderConsumer.kt's only import is
    // io.github.ygaray.sttengine.recorder.* — never io.github.ygaray.sttengine.SttEngine or
    // createSttEngine. That single-file, single-package root set is the entire proof surface R8's
    // reachability analysis sees for this module.
    implementation(project(":stt"))
}
