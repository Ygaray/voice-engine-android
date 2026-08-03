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

rootProject.name = "stt-engine-android"
// Only the publishable library module for now. Plan 07 adds `include(":demo")` — the demo is the
// reference host app and is NEVER named in jitpack.yml (D-23).
include(":stt")
