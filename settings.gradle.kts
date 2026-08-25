// D-21: this file lives at `android/`, NOT the repo root. `git subtree split --prefix=android`
// promotes this directory's contents to the public mirror's root, so anything left at the mono-repo
// root simply does not exist for JitPack. Same for gradlew/gradlew.bat/gradle/wrapper/ and jitpack.yml.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "voice-engine-android"
// The publishable library module.
include(":stt")
// The SECOND publishable library module (plan 08-05, REC-05 rework). Extracted out of :stt so the
// Recorder's dependency graph structurally never names :stt — a compile-time module-boundary
// guarantee, stronger than the R8-mapping-grep proof :verify-recorder-only offered alone. Named in
// android/jitpack.yml alongside :stt (D-23, Pitfall 8: a mismatch here is unrecoverable after a tag).
include(":recorder")
// The THIRD publishable library module (Phase 10, plan 10-03). Composes :recorder for capture and
// adds a Room-backed persisted note queue + WorkManager-driven drain — never :stt (this module has
// zero recognition dependency, mirroring the "server-only, never native" phase note). Named in
// android/jitpack.yml alongside :stt/:recorder (D-23, Pitfall 8: a mismatch here is unrecoverable
// after a tag).
include(":voicenotes")
// The reference host app (D-27/D-28). It is NEVER named in jitpack.yml and carries no
// `maven-publish`, so it is outside the published module set (D-23) — the mirror ships the library
// and the worked example of how to consume it, never the example as an artifact.
include(":demo")
// Synthetic minimal-consumer R8 harness (plan 08-04, REC-05). Same "outside the published module
// set" status as :demo above: never named in jitpack.yml, never carries maven-publish. Its only
// job is to prove, via a real isMinifyEnabled=true release build, that a consumer importing ONLY
// io.github.ygaray.sttengine.recorder.* produces a mapping.txt naming none of the recognition-only
// internal classes — see android/verify-recorder-only/check-r8-mapping.sh.
include(":verify-recorder-only")
