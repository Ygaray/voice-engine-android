// Root build file — plugins declared here (apply false) and applied in the :stt module.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Declared now, applied by the :demo module plan 07 adds. Kept here so the demo's arrival is a
    // one-line settings.gradle.kts change, not a root-build edit.
    alias(libs.plugins.android.application) apply false
    // Same reason, same module: from Kotlin 2.0 the Compose compiler is a Gradle plugin, not a
    // pinned androidx artifact, so :demo cannot enable `buildFeatures.compose` without it.
    alias(libs.plugins.compose.compiler) apply false
    // D-12 / Obstacle 2: the STANDALONE binary-compatibility-validator. Kotlin's built-in
    // `abiValidation` extension explicitly excludes Android (no JAR is published), so this is the
    // only working source of `:stt:apiDump` / `:stt:apiCheck`.
    alias(libs.plugins.binary.compatibility.validator) apply false
}
