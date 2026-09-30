plugins {
    // Android APPLICATION (not library): this is the reference host app, the thing a consumer runs.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.x ships the Compose compiler as a plugin versioned with Kotlin itself.
    alias(libs.plugins.compose.compiler)

    // DELIBERATELY ABSENT: `maven-publish`. The demo is outside the published module set (D-23).
    // It is also absent from android/jitpack.yml, which names :stt and only :stt — a module named
    // there that settings.gradle.kts does not include (or vice versa) is an unrecoverable failure
    // found only after the mirror tag is immutable.
    //
    // ALSO DELIBERATELY ABSENT: binary-compatibility-validator and `explicitApi()`. Freezing an
    // API surface is for the library; the demo has no consumers and every symbol in it is free to
    // change.
}

android {
    namespace = "io.github.ygaray.sttengine.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ygaray.sttengine.demo"
        // Matches :stt's minSdk 33 (D-24) — the demo cannot install anywhere the library cannot run.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            // Nothing to shrink and nothing to hide; keeping this off means the release APK's
            // merged manifest is a straight read of the source manifests, which is what the
            // "zero receivers in release" assertion checks.
            isMinifyEnabled = false
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
    // The whole point: the demo consumes the library exactly the way a third-party host would —
    // through its public surface only. Nothing here reaches into an internal package, and
    // `explicitApi()` plus the frozen `.api` dump on :stt is what makes that mechanical.
    implementation(project(":stt"))

    // The bill of materials aligns every Compose artifact below; none of them names a version.
    // libs.compose.bom resolves to androidx.compose:compose-bom:2026.06.00 — and 2026.06.00 is not
    // a rounding-down of something newer: a registry probe returned 404 for 2026.07.00, so .06 is
    // the latest that exists. The pin itself lives in gradle/libs.versions.toml, where every other
    // version in this build lives; it is restated here because this is the file a consumer reads.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // setContent + rememberLauncherForActivityResult — the host-side RECORD_AUDIO request (D-11).
    implementation(libs.activity.compose)
    // collectAsStateWithLifecycle() — the documented consumer of D-07's per-event StateFlows.
    implementation(libs.lifecycle.runtime.compose)

    // Phase 19 (D-02): the demo's first unit-tested source — the pure diagnostics helpers in
    // DemoControlReceiver.kt (lastSegmentLanguageOf/segmentLanguagesOf/languageDiagnostics).
    testImplementation(libs.junit)
    // org.json.JSONObject/JSONArray are Android-platform stub classes on the host JVM — without
    // Robolectric (deliberately excluded by this task's design), calling them throws "not mocked"
    // at runtime rather than exercising real JSON behavior. The real, standalone org.json:json JVM
    // implementation (same package/class names, picked up ahead of the mockable android.jar stub
    // on the unit-test classpath) is the standard Android-testing fix — already proven in this
    // repo by :transcription's identical testImplementation(libs.json) (19-REVIEWS.md cycle 3,
    // Codex HIGH: the plain-JUnit test against real org.json without this dependency would fail at
    // runtime rather than validate the diagnostics record).
    testImplementation(libs.json)
}
