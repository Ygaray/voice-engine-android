plugins {
    // Android LIBRARY (not application): no applicationId, no versionName, no Compose.
    // This is the isolated reusable :stt engine (AND-01).
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // D-12: freezes the public surface. Provides :stt:apiDump / :stt:apiCheck (Obstacle 2 — the
    // Kotlin built-in abiValidation extension explicitly excludes Android, so this standalone
    // plugin is the only working option). Plan 11 turns apiCheck into a release gate.
    alias(libs.plugins.binary.compatibility.validator)
    `maven-publish`
}

android {
    namespace = "io.github.ygaray.sttengine"
    compileSdk = 36

    defaultConfig {
        // D-24: minSdk 33, NOT the sibling's 26. checkRecognitionSupport / triggerModelDownload /
        // EXTRA_SEGMENTED_SESSION / onSegmentResults are all API 33. This is precisely what makes
        // the `no-offline-model` preflight (plan 06) reachable rather than hypothetical.
        minSdk = 33
        // No targetSdk / applicationId — a library inherits the consuming app's targetSdk.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric needs merged Android resources on the unit-test classpath for the thin
        // platform-adapter suite.
        unitTests.isIncludeAndroidResources = true
        // Un-stubbed android.jar methods return a default instead of throwing, so a pure-JVM test
        // can touch the platform surface without a Robolectric runtime.
        unitTests.isReturnDefaultValues = true
    }

    // AGP-8 opt-in: declare the single publishable variant + its sources jar. MANDATORY —
    // without this, `from(components["release"])` below fails ("'release' property cannot be
    // found"), the #1 JitPack-Android publish failure.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// TOP-LEVEL kotlin { } — a SIBLING of android { }, deliberately NOT nested inside it (Pitfall 9).
// The sibling backup-engine nests `kotlin { compilerOptions { … } }` inside android { }; that is
// AGP's KotlinAndroidProjectExtension, a different receiver, and explicitApi() there would not do
// what is intended. explicitApi() is what makes the frozen stt.api dump meaningful (D-12).
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The SOLE runtime dependency (D-09). Dispatchers.Main.immediate — required so a recognizer
    // callback already on the main looper is handled without a re-post — lives in the -android
    // artifact, not -core. No Hilt: this library has one construction path and no graph, and Hilt
    // would be a transitive burden on every consumer. No androidx.core either:
    // Context.checkSelfPermission is API 23+ and free (D-11 — the HOST requests the permission).
    implementation(libs.coroutines.android)

    // JVM unit tests — the majority of the suite runs here, behind the injected RecognizerHandle
    // seam, with no device and no Robolectric.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Robolectric — JVM host runner for the thin platform adapter only.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests — the D-04 capability probe (RecognizerCapabilityProbeTest) is the reason
    // these exist; it can only be answered by the real recognizer service on the real device.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// JitPack android-example publishing form. `from(components["release"])` MUST be inside
// afterEvaluate — the "release" software component does not exist at configuration time (it is
// created by the AGP singleVariant("release") opt-in above). groupId/version are what
// publishToMavenLocal writes; JitPack overrides groupId from the repo owner (com.github.Ygaray)
// and version from the resolved ref.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Ygaray"
            // == the MIRROR REPO NAME, not the module name (:stt). Same rule the sibling follows:
            // its module is :backup and its artifactId is backup-engine.
            artifactId = "stt-engine-android"
            version = "0.1.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
