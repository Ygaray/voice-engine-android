// D-21: this file lives at `android/`, NOT the repo root. `git subtree split --prefix=android`
// promotes this directory's contents to the public mirror's root, so anything left at the mono-repo
// root simply does not exist for JitPack. Same for gradlew/gradlew.bat/gradle/wrapper/ and jitpack.yml.
// TEMPORARY — GATE 3 negative test only, reverted immediately after. Applies a file that lives
// ABOVE the split prefix: builds in the mono-repo, unbuildable in the mirror.
apply(from = "../shared-gradle-config.gradle.kts")

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

rootProject.name = "stt-engine-android"
// The publishable library module.
include(":stt")
// The reference host app (D-27/D-28). It is NEVER named in jitpack.yml and carries no
// `maven-publish`, so it is outside the published module set (D-23) — the mirror ships the library
// and the worked example of how to consume it, never the example as an artifact.
include(":demo")
