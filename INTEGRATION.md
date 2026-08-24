# INTEGRATION.md — adopt `stt-engine-android` in a new Android app

Operational checklist for wiring the library into a **consumer app**. The public surface every step
references is in **[`API.md`](API.md)**; the reuse rules are in **[`CLAUDE.md`](CLAUDE.md)**. The
**worked reference is the `:demo` module in this repository** — every step below names the exact file
that implements it, and every snippet is lifted from there rather than written fresh, so the docs and
a build-verified app cannot drift apart silently.

**Prerequisites (the host must already have):**

- `minSdk 33` or higher, `compileSdk 36`, JDK 17 (see `README.md` for why 33 is the floor)
- A way to request a runtime permission — an Activity, or Compose's
  `rememberLauncherForActivityResult`. **The host requests the microphone; the library only checks
  it.** This is the one contract asymmetry against the TypeScript client (see `API.md`).
- Nothing else. There is no auth, no account, no network and no server to configure. The library's
  only transitive dependency is `kotlinx-coroutines-android`, and it arrives at `compile` scope
  automatically because every member of the engine interface is a `Flow`.

---

## 1. Add the JitPack repository

In **`settings.gradle.kts`** → `dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

## 2. Depend on the library — pin an immutable tag

In your app module's **`build.gradle.kts`**:

```kotlin
implementation("com.github.Ygaray:stt-engine-android:v0.1.0")   // immutable tag — NEVER a branch snapshot
```

Pin a tag or a commit SHA. **Never `main-SNAPSHOT` or any moving branch ref** — a moving coordinate
means the bytes you tested and the bytes your users run are not the same bytes, and there is no way
to tell after the fact which is which. The artifact id is `stt-engine-android`: it equals the mirror
repository name, not the Gradle module name (`:stt`).

## 3. Declare and request the microphone permission

**Declare it.** `RECORD_AUDIO` already merges into your app from the library's own manifest, so this
line is technically redundant — declare it anyway, in your **`AndroidManifest.xml`**, because a host
reading its own manifest must see the permission it is responsible for. Reference:
`demo/src/main/AndroidManifest.xml`.

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**Request it.** The library has no Activity and cannot ask. Reference:
`demo/src/main/kotlin/io/github/ygaray/sttengine/demo/MainActivity.kt` — this snippet is lifted from
there whole:

```kotlin
val micPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
) {
    // Start either way. On a grant this is the session the user asked for; on a
    // denial the engine reports `mic-denied` in the loud banner, which is a better
    // answer than a button that silently does nothing.
    viewModel.start()
}

DictationScreen(
    viewModel = viewModel,
    onStartRequested = {
        if (hasMicPermission(context)) {
            viewModel.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    },
)
```

with the check itself (same file — `Context.checkSelfPermission` is API 23+ and free, so no
`androidx.core` dependency is added for one call):

```kotlin
private fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
```

**A host that skips this does not crash.** Calling `start()` without the grant emits `mic-denied` on
`errors` and returns normally — a typed event, never a thrown exception.

## 4. Construct the engine with the factory

One call, one entry point. Reference:
`demo/src/main/kotlin/io/github/ygaray/sttengine/demo/DictationViewModel.kt`:

```kotlin
val engine = createSttEngine(
    context = getApplication(),
    config = SttConfig(language = spec.language),
    diagnosticLogging = spec.diagnosticLogging,
)
```

`diagnosticLogging` defaults to `false` and **must stay false in a shipped build** — the trace it
writes contains the user's dictated speech. The demo only reaches that argument from its debug source
set, which is a structural separation rather than a runtime flag; copy that shape if you need
diagnostic runs.

Configuration is fixed at construction. A language change or a diagnostic run mints a **new** engine
rather than mutating a live one, so if your host offers either, hold the engine in something the UI
observes. Reference: `DictationViewModel.engine` is a `StateFlow<SttEngine>` for exactly this reason.

## 5. Collect the flows

`state` and `partial` are `StateFlow`s a recomposing host reads directly. The three
channel-and-conflation-backed streams get **one collector each**, at the highest point in the tree,
keyed on the engine instance. Reference:
`demo/src/main/kotlin/io/github/ygaray/sttengine/demo/DictationScreen.kt`:

```kotlin
val state by engine.state.collectAsStateWithLifecycle()
val partial by engine.partial.collectAsStateWithLifecycle()

LaunchedEffect(engine, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { engine.finals.collect(viewModel::onSegmentCommitted) }
        launch { engine.errors.collect(viewModel::onEngineError) }
        launch { engine.level.collect(viewModel::onLevel) }
    }
}
```

**Collect `finals` in exactly one place.** It is single-consumer by construction — a second collector
anywhere in your app would split the user's dictation between two lists with no error to show for it.
Detaching on backgrounding is safe *because* of the unlimited buffer behind it: everything spoken
while the app was away arrives on reattach, in order. See `API.md` for why that trade was made.

Render committed finals as **appended, immutable blocks** and the live partial **in place, visually
distinct** — muted and italic in the demo, versus normal weight at full opacity for a final. That
distinction is the requirement, not styling: a reader has to be able to tell at a glance which words
are safe to rely on, and rendering them alike makes the engine's central guarantee invisible.
Reference: `DictationScreen.Transcript` / `DictationScreen.FinalBlock`.

## 6. Render errors loudly — raw code first, platform detail included

Reference: `DictationScreen.ErrorBanner`:

```kotlin
buildString {
    append("ERROR [")
    append(error.code.value)
    append("] ")
    append(error.message)
    error.platformDetail?.let {
        append(" · platformDetail=")
        append(it)
    }
}
```

The raw kebab-case code and the engine's own message come **first**; your friendly hint copy, if any,
comes after. That order is deliberate — a banner that leads with reassuring copy and buries the code
costs an hour of guessing, and `platformDetail` is frequently the only thing that distinguishes two
failures the taxonomy maps to the same code.

Hint copy is **host-owned by design** and is not part of the frozen contract. Reference:
`demo/src/main/kotlin/io/github/ygaray/sttengine/demo/ErrorHints.kt`. Because `SttErrorCode` is a
value class rather than an enum, a code with no hint simply gets none — an unknown code cannot crash
an exhaustive `when`.

Recognized text and recognizer messages are **untrusted input**. The demo renders them through
Compose `Text`, which does not interpret markup. Do not route them into a markup renderer, a shell
command, or a log line you then parse.

---

## Troubleshooting

The two failures a first-time integrator actually hits.

### `no-offline-model` — the language pack is not on the device

**Symptom:** the preflight fails before any audio is captured, with `no-offline-model` and a message
distinguishing "not installed" from "a download is already pending". No `platformDetail` (this is a
preflight failure, not a recognizer error integer).

**What it means:** the device's recognizer *supports* the language but has not downloaded the offline
model for it. This is the single most common first-run failure and it is **not** a bug in your
integration.

**The correct response is to trigger the download, not to give up.** That is precisely why this code
exists as a separate additive code instead of being folded into `unsupported-language` — the two
demand opposite host actions. The library's floor of `minSdk 33` is what makes
`triggerModelDownload` reachable at all, so a host can offer the recovery inline; failing that, send
the user to the system speech settings.

> **⚠ Ordering trap: the download REQUIRES network.** The engine is offline, the *model acquisition*
> is not. If you are validating offline behaviour, download the pack **while online first**, then go
> offline. Doing it the other way round fails with a language-unavailable error and looks exactly
> like a code bug.

If the language is not supported at all — as opposed to not installed — you get
`unsupported-language` instead, and downloading will not help.

### An empty recognizer-service list — suspect the manifest, not the device

**Symptom:** `PackageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)`
returns an **empty list** on a device that demonstrably does speech recognition, and/or the engine
reports `recognizer-unavailable` on hardware you know is fine.

**What it means:** since Android 11 (API 30), package-visibility filtering hides services your app
has not declared an interest in. Without a `<queries>` declaration the list comes back empty and the
diagnosis reads "no recognizer installed" on a device that has two.

**The library already declares it**, in `stt/src/main/AndroidManifest.xml`, and it merges into every
consumer — which is the point of putting it there rather than making each host remember:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

So if you see an empty list, check the **merged** manifest of your build variant before blaming the
device: a `tools:node="remove"` somewhere in your app or another dependency, or a manifest-merger
conflict, can strip it back out. Reference: the `:demo` module deliberately does **not** repeat this
element — it inherits it — and its diagnostics panel prints both the installed service list and the
`voice_recognition_service` the system will actually bind, which are different questions.

---

## Notes & gotchas

- **An ordinary restart is invisible on purpose.** Continuous dictation is built on restarting the
  recognizer at each utterance boundary, and the state stream deliberately stays `Listening` across
  that gap. Do not render a "reconnecting" affordance off anything but the `Reconnecting` state, and
  do not zero a level meter on a lull — that would paint a visible seam at exactly the boundary the
  design exists to hide. The demo decays its meter instead; see `DictationViewModel.startLevelDecay`.
- **`segmentId` is opaque and per-session.** It starts at 0 and is monotonic within one session. It
  is not a stable identifier and is **not comparable across backends**.
- **`SttWarning` never fires in this version, on either backend.** On-device, that's permanent — the
  recognition service owns the microphone, so there is no outbound send buffer to warn about. On the
  server backend (Phase 9, `prefer`/`serverUrl` on `SttConfig`), the forwarding wire from
  `ServerBackend.warnings` exists, but nothing yet drives the outbound audio pump that would feed the
  backpressure gate in production, so it is temporarily unreachable rather than permanently absent.
  Keep the branch for cross-backend code regardless. See `API.md`.
- **Bumping to a new library version is human-gated.** Change the coordinate to a new immutable tag,
  sync, rebuild, and re-verify on a real device before shipping. See `CLAUDE.md`.
