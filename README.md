# stt-engine-android

A reusable, host-agnostic Kotlin library for live dictation on Android. It exposes the **same
dictation contract** as the project's TypeScript client — the same five engine states, the same
eleven error strings, the same live-partial-then-immutable-final model — implemented **offline and
on-device by default** over the platform `SpeechRecognizer`. A self-hosted server backend also exists,
but it is strictly **opt-in and off by default**: with no `serverUrl` configured (the default
`SttConfig()`), the library behaves exactly as it always has — no server, no socket, no wire protocol
anywhere in the path, and the recognizer service owns the microphone end to end. Supplying a
`serverUrl` and leaving `prefer = BackendPreference.Auto` (the default once a `serverUrl` is given)
tries the self-hosted server first with a bounded timeout and falls back to native seamlessly on
failure or drop. See `API.md`'s `SttConfig` section for the full `prefer`/`serverUrl`/`Backend`
mechanism.

The library owns the recognition plumbing — preflight, the session state machine, the restart loop
that makes continuous dictation possible on an API that only recognizes one utterance at a time,
error classification, and the capability ladder that picks the best segmentation strategy the device
actually supports. A host app owns exactly two things: requesting the microphone permission, and
rendering the flows. It never touches a `SpeechRecognizer`, a `RecognitionListener` or an
`AudioRecord`.

**Docs for agents & integrators:**

- **[`INTEGRATION.md`](INTEGRATION.md)** — step-by-step checklist to adopt the library in a new app.
- **[`API.md`](API.md)** — the full public surface, member by member, plus the contract-parity table
  against the TypeScript client and the three places the two backends deliberately differ.
- **[`CLAUDE.md`](CLAUDE.md)** — the reuse doctrine and the invariants that must not be broken.
- Worked reference: the **`:demo`** module in this repository. Every code snippet in these documents
  is lifted from it, so the docs and a build-verified app cannot drift apart silently.

## Requirements

- Android **`minSdk 33`**, `compileSdk 36`
- Gradle 8.13 / AGP 8.13.0 / Kotlin 2.3.20 / JDK 17
- The consumer gets **`kotlinx-coroutines-android` transitively at `compile` scope** — every member
  of the engine interface is a `Flow` or a `StateFlow`, so coroutines is an `api` dependency by
  necessity, not an oversight. You do not need to declare it yourself.

### Why `minSdk 33` and not lower

Two capabilities land exactly there, and together they are the difference between a diagnosable
failure and a mysterious one:

- `SpeechRecognizer.checkRecognitionSupport` (API 33) — the **pre-flight** support check. It is what
  lets the library discover that the device has no offline language pack **before** dictation
  starts, rather than after a session that simply fails.
- `SpeechRecognizer.triggerModelDownload` (API 33) — the **recovery** the host can offer once that
  is known. A diagnosis with no action attached is only half a diagnosis.

Below API 31 there is no on-device recognizer at all (`createOnDeviceSpeechRecognizer` and
`isOnDeviceRecognitionAvailable` are both API 31), so the offline, free-and-unlimited premise this
library is built on does not exist there. On 31–32 you can go on-device but cannot preflight support
or trigger a pack download, which forfeits the entire diagnosis path. 33 is the floor at which the
library's promises are keepable.

## Install (JitPack)

Add the JitPack repository in your **`settings.gradle.kts`**, inside
`dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

Then depend on it in your app module's **`build.gradle.kts`**. Pin an **immutable tag** (or a
commit SHA) — never a moving branch snapshot:

```kotlin
implementation("com.github.Ygaray:stt-engine-android:<tag>")
```

## Quick start

Five lines, lifted from the `:demo` module. Construct the engine once (it takes its configuration at
construction, deliberately), collect the flows, and start:

```kotlin
val engine = createSttEngine(
    context = getApplication(),
    config = SttConfig(language = "en"),
)

// idle / connecting / listening / reconnecting / error, and the live replace-in-place text
val state by engine.state.collectAsStateWithLifecycle()
val partial by engine.partial.collectAsStateWithLifecycle()
// committed, immutable blocks — collect this in EXACTLY ONE place
launch { engine.finals.collect(viewModel::onSegmentCommitted) }
```

then `engine.start()` — the host must already hold `RECORD_AUDIO`.

`finals` is **lossless and single-consumer by construction**: it is backed by an unlimited channel so
that a lifecycle-aware host detaching on backgrounding — normal behaviour for a dictation app whose
user switches away mid-thought — does not silently discard text the user actually spoke. Collect it
in exactly one place. `API.md` explains the delivery semantics of every property.

**The host requests the microphone permission; the library only checks it.** This is the one
documented asymmetry against the TypeScript client, which triggers the browser's own prompt as part
of capture. A library with no Activity cannot ask, so a missing grant arrives as a typed
`mic-denied` error rather than a thrown exception. See `INTEGRATION.md` step 3 for the worked
snippet.
