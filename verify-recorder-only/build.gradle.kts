// android/verify-recorder-only — a synthetic, never-published minimal-consumer harness (plan 08-04,
// repointed at :recorder in plan 08-05's REC-05 rework).
//
// As of plan 08-05, this consumer depends ONLY on project(":recorder") — a module whose own
// dependencies {} block never names :stt. That compile-time dependency alone already proves this
// consumer's classpath contains ZERO :stt bytecode, under ANY build configuration (debug,
// unminified release, or otherwise) — the module-boundary guarantee, not this harness, is now
// REC-05's PRIMARY Android proof (see :recorder/build.gradle.kts's verifyNoRecognitionDependency
// task and :recorder/check-no-recognition-dependency.sh).
//
// The R8-mapping inspection below (check-r8-mapping.sh) is retained as a SECONDARY,
// belt-and-suspenders check layered on top of that compile-time guarantee: it additionally proves
// that even R8's own reachability analysis, in the one minified-release configuration this harness
// builds, retains none of the five recognition-only internal classes. It is no longer the primary
// proof this module exists to provide, and it never was general — see check-r8-mapping.sh's own
// header for the caveats (08-RESEARCH.md Pitfall 4/Assumption A3 and Pitfall 5) it documents rather
// than silently resolves.
//
// NEVER add `maven-publish` here, and never name this module in android/jitpack.yml — same
// "outside the published module set" status as :demo (T-08-09).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.ygaray.sttengine.verifyrecorderonly"
    // Matches :recorder's compileSdk exactly (read from android/recorder/build.gradle.kts) — this
    // harness proves something about :recorder's consumer, so it must build against the same SDK line.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ygaray.sttengine.verifyrecorderonly"
        // Matches :recorder's minSdk exactly — a real consumer of :recorder can never be older than
        // this floor.
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
    // The ONLY dependency (plan 08-05: repointed from :stt to :recorder). MinimalRecorderConsumer.kt's
    // only import is io.github.ygaray.sttengine.recorder.* — never io.github.ygaray.sttengine.SttEngine
    // or createSttEngine. Depending on :recorder alone means this consumer's classpath cannot
    // possibly contain :stt bytecode under ANY build configuration — that is the module-boundary
    // guarantee itself. The R8 reachability analysis below is now a secondary check on top of it.
    implementation(project(":recorder"))
}
