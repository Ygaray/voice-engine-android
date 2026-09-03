# INTEGRATION.md — adopt `voice-engine-android` in a new Android app

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
- Nothing else required either way. No auth or account is needed on either backend. A network/server
  is optionally involved only when you supply `serverUrl` in `SttConfig` (opt-in, governed by
  `prefer` — see `API.md`); with no `serverUrl`, the library runs exactly as it always has, entirely
  on-device. The library's only transitive dependency is `kotlinx-coroutines-android`, and it arrives
  at `compile` scope automatically because every member of the engine interface is a `Flow`.

---

## 1. Add the JitPack repository

In **`settings.gradle.kts`** → `dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

## 2. Depend on the library — pin an immutable tag

In your app module's **`build.gradle.kts`**:

```kotlin
implementation("com.github.Ygaray:voice-engine-android:v0.3.0")   // immutable tag — NEVER a branch snapshot
```

Pin a tag or a commit SHA. **Never `main-SNAPSHOT` or any moving branch ref** — a moving coordinate
means the bytes you tested and the bytes your users run are not the same bytes, and there is no way
to tell after the fact which is which. The artifact id is `voice-engine-android`: it equals the mirror
repository name, not the Gradle module name (`:stt`).

> **What this coordinate actually installs.** Live-probed (`137-01-SUMMARY.md`): this exact coordinate's
> POM resolves at HTTP 200, but it is JitPack's synthesized **root/aggregator** POM for the whole
> multi-module repo — its own dependency list pulls in `voice-engine-android` (the real `:stt` AAR, under
> a *different* groupId), `-recorder`, `-transcription` and `-voicenotes` as `<type>aar</type>`
> dependencies. Declaring `implementation("com.github.Ygaray:voice-engine-android:v0.3.0")` therefore
> transitively links **all four modules**, not `:stt` alone. If you want `:stt`'s recognition code with
> zero Recorder/VoiceNotes/Transcription bytes pulled in, depend on the real standalone artifact instead:
> `implementation("com.github.Ygaray.voice-engine-android:voice-engine-android:v0.3.0")` — note the
> `.voice-engine-android` suffix on the **groupId**, matching the pattern the three secondary modules use
> (§7–9 below). Both coordinates are live and immutable at `v0.3.0`; pick the aggregator only if you
> genuinely want every module.

### Migrating from `stt-engine-android`

This library was renamed from `stt-engine-android` to `voice-engine-android` to reflect its
audio-layer identity (Recorder + Voice Notes, not just recognition). If you already depend on the old
coordinate, here is exactly what changed and what still holds.

**Old coordinate (still resolvable):** `com.github.Ygaray:stt-engine-android:v0.1.0` — mirror
`github.com/Ygaray/stt-engine-android`. Per this project's migration policy (D-03), the old mirror and
its `v0.1.0` tag are left completely untouched and remain resolvable for **at least one more
release**. Verified: `git ls-remote --tags https://github.com/Ygaray/stt-engine-android.git` still
returns exactly `8220bb545ac07aa4c441a65ed03282670a9f6b9c refs/tags/v0.1.0`, and its JitPack build log
(`https://jitpack.io/com/github/Ygaray/stt-engine-android/v0.1.0/build.log`) still resolves with a
clean, complete build.

**New coordinate:** `com.github.Ygaray:voice-engine-android:v0.2.0` — mirror
`github.com/Ygaray/voice-engine-android`, verified live: tag `v0.2.0` resolves at commit
`ff6477c3a9428884b6b44f5d926ca004b8de667d`. Two more artifacts ship for the **first time** under this
coordinate — there is no old coordinate to preserve for either, since neither was ever tag-published
before this rename: `com.github.Ygaray:voice-engine-android-recorder:v0.2.0` and
`com.github.Ygaray:voice-engine-android-voicenotes:v0.2.0`.

**To migrate:** update the `implementation(...)` line(s) in your consumer app's `build.gradle.kts`
from the old coordinate to the new one, pin the new tag (`v0.2.0`), sync, and rebuild. Same rule as
always: pin a tag or a commit SHA, **never `main-SNAPSHOT` or any moving branch ref**.

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

## 7. Adopt the Recorder (optional, separate module)

`:recorder` is a second, independently publishable Gradle module (JitPack artifact
`voice-engine-android-recorder`) — mic in, compressed `.m4a` file out, usable standalone by a
record-only consumer. A record-only host depends on **only** this coordinate and pulls in zero
recognition code (REC-05):

```kotlin
implementation("com.github.Ygaray.voice-engine-android:voice-engine-android-recorder:v0.4.0")   // immutable tag — NEVER a branch snapshot
```

> **Groupid note.** Secondary modules carry a **different groupId prefix** than the plain
> `com.github.Ygaray` shown for `:stt` in step 2 — JitPack derives each secondary artifact's groupId
> from the mirror repository name (`com.github.Ygaray.voice-engine-android`), not from the custom
> `groupId` override declared in this module's own publish block; the override does not take effect for
> non-root artifacts on JitPack's platform (live-probed and recorded in `137-01-SUMMARY.md`; the
> previously-documented `com.github.Ygaray:voice-engine-android-recorder:v0.3.0` coordinate 401s).

Same rule as step 2: pin a tag or a commit SHA, never `main-SNAPSHOT` or any moving branch ref. This
coordinate does not transitively pull in `:stt` — a consumer that only needs recording never links
recognition code, and that separation is mechanically enforced by
`android/recorder/check-no-recognition-dependency.sh`, wired into `:recorder:check`.

Construct + collect, mirroring steps 4/5's shape:

```kotlin
val recorder = createRecorder(context = getApplication())

val state by recorder.state.collectAsStateWithLifecycle()

LaunchedEffect(recorder, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { recorder.level.collect(::onLevel) }
        launch { recorder.errors.collect(::onRecorderError) }
    }
}

// start a session
scope.launch { recorder.start() }

// end it and use the finished file
scope.launch {
    val recordedFile = recorder.stop()
    // recordedFile.uri / .durationMs / .format / .createdAt
}
```

The host must already hold `RECORD_AUDIO` (see step 3) — `createRecorder()` has no Activity and
cannot request it. See `API.md`'s Recorder section for the full `RecorderState`/`RecorderError`
taxonomy and the documented PEAK-vs-RMS amplitude asymmetry against the web Recorder.

### 7a. Override the level cadence or encoding parameters (optional)

Pass a `RecorderConfig` to override the amplitude-poll cadence or any of the three MediaRecorder
encoding parameters. The default `RecorderConfig()` is behavior-neutral — passing it explicitly
changes nothing:

```kotlin
val recorder = createRecorder(
    context = getApplication(),
    config = RecorderConfig(levelIntervalMs = 100L, sampleRateHz = 44100),
)
```

See `API.md`'s `RecorderConfig` subsection for every field's default and validation rule.

### 7b. Adopt the foreground-service seam (optional, separate from Recorder itself)

`RecorderForegroundService` keeps a recording running while the host app is backgrounded. Adopting it
is opt-in — skip this subsection entirely for a host that only ever records in the foreground.

1. **Declare and request the runtime microphone permission the same as step 3/7 above.** The service
   declares the foreground-service permissions itself by manifest merge (see the inherited-permission
   note below), but a host still requests `RECORD_AUDIO` at runtime exactly as for the plain
   `Recorder` — the service adds no shortcut around that.
2. **Bind the service and call `attach` to begin a managed session; call `detach` explicitly to end
   it:**

   ```kotlin
   val connection = object : ServiceConnection {
       override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
           val recorderBinder = binder as RecorderForegroundService.RecorderBinder
           recorderBinder.attach(
               recorder = recorder,
               notification = RecorderNotificationConfig(
                   smallIconResId = R.drawable.ic_recording,
                   contentTitle = "Recording",
               ),
           )
       }
       override fun onServiceDisconnected(name: ComponentName?) = Unit
   }
   applicationContext.bindService(
       Intent(applicationContext, RecorderForegroundService::class.java),
       connection,
       Context.BIND_AUTO_CREATE,
   )
   ```

   Binding from the **application context** is the recommendation here — it keeps the connection
   alive across an Activity recreation without extra wiring — but it is a recommendation, not the
   survival mechanism: the lifecycle contract below is what actually keeps a recording alive across
   every client unbinding, independent of which context bound the service.

3. **The lifecycle contract, exactly as implemented:** on `attach()`, the service enters the
   *started* state in addition to being bound, so a recording **survives every client
   unbinding** — including an Activity destroyed and recreated by a configuration change. Only
   `detach()` (or the recorder reaching a terminal state on its own — see the next point) ends the
   managed session. Unbinding a UI client alone does **not** end a recording.
4. **The host detach obligation.** A host that calls `attach` and then abandons the recorder without
   ever calling `start()` keeps the notification and the started state indefinitely — no
   library-side timeout can safely resolve this (it would freeze a policy constant into an immutable
   tag and kill the legitimate slow-start case). **A host that attaches MUST call `detach` if
   recording never starts**, wired to its own lifecycle teardown. This is the only leak path this
   service does not close on its own — it closes every other case by itself: a failed `start()`, any
   other transition into `Error`, a recording that ends normally, and an external `stop()` on the
   `Recorder` all tear this service down without host involvement.
5. **Recovering from a failed `start()`.** Because armed-rule teardown fires on *any* `Error`
   (armed or not), and `Recorder.start()` can be re-called from `Error`, call `start()` on the
   recorder BEFORE re-attaching it to the service — re-attaching to a recorder still sitting at
   `Error` tears the service back down immediately, and a `start()` that later succeeds would then
   have no foreground service watching it.
6. **The threading contract.** `attach` and `detach` are **main-thread-only**, and the service is
   **not** thread-safe against concurrent binder calls from arbitrary threads. A local binder does
   not marshal calls across threads, so this is a stated contract the host must honor, not a
   framework guarantee the service enforces on the host's behalf.

**The inherited-permission consequence.** Any consumer that depends on `:recorder` — even one that
never binds `RecorderForegroundService` — inherits the `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MICROPHONE` manifest permissions by manifest merge, because the service
declaration lives inside `:recorder`'s own manifest. This was accepted rather than split into a
separate artifact: a second `:recorder-service` module would double the published-module count and
the release-gate surface for two permission lines Android already treats as ordinary
(non-dangerous, no runtime prompt) declarations.

See `API.md`'s foreground-service-seam subsection for `RecorderNotificationConfig`'s full field list
and validation rules.

---

## 8. Adopt Voice Notes (optional, composes Recorder + the batch endpoint)

`:voicenotes` is a third, independently publishable Gradle module (JitPack artifact
`voice-engine-android-voicenotes`) — record-now / transcribe-when-reachable, composing the Recorder
above with a durable Room-backed queue and the Phase 7 batch endpoint:

```kotlin
implementation("com.github.Ygaray.voice-engine-android:voice-engine-android-voicenotes:v0.3.0")   // immutable tag — NEVER a branch snapshot
```

> **Groupid note.** Same wrinkle as `:recorder` (step 7): the groupId prefix is
> `com.github.Ygaray.voice-engine-android`, not the plain `com.github.Ygaray` shown for `:stt` — JitPack
> derives it from the mirror repository name for every secondary module (live-probed and recorded in
> `137-01-SUMMARY.md`; the previously-documented `com.github.Ygaray:voice-engine-android-voicenotes:v0.3.0`
> coordinate 401s).

Same pinning rule as steps 2 and 7. This module depends on `:recorder` internally (a voice note is a
recording plus persistence and drain), so a consumer of `:voicenotes` gets Recorder capability
transitively — it does not need its own `:recorder` dependency line as well.

It needs a `serverUrl` pointing at the Phase 7 batch endpoint (e.g. `https://host.ts.net:8446` — see
`server/README.md`). Unlike `createSttEngine`, there is **no tailnet-default baked in** — the host
must supply this explicitly:

```kotlin
val voiceNotes = createVoiceNotes(
    context = getApplication(),
    serverUrl = "https://host.ts.net:8446",
)

val notes by voiceNotes.notes.collectAsStateWithLifecycle()
val pendingCount by voiceNotes.pendingCount.collectAsStateWithLifecycle()

LaunchedEffect(voiceNotes, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { voiceNotes.noteTranscribed.collect(::onNoteTranscribed) }
    }
}

// record a new note
scope.launch { voiceNotes.record() }

// stop and enqueue it for drain
scope.launch { voiceNotes.stopAndEnqueue() }

// force an immediate drain attempt
scope.launch { voiceNotes.drainNow() }
```

**Read this before shipping a multi-platform host:** this Android implementation drains via
`WorkManager`, in the background, surviving app close — the opposite of the web `createVoiceNote()`
backend, which drains only while its page is open (an in-page timer loop; no background-execution
capability exists on the web platform). A host targeting both platforms must not assume identical
drain timing. See `API.md`'s Voice Notes section for the full `NoteRecord`/`NoteStatus`/
`NoteErrorCode` taxonomy.

---

## 9. Adopt Transcription (optional, the batch protocol client only)

`:transcription` is a fourth, independently publishable Gradle module (JitPack artifact
`voice-engine-android-transcription`) — the dependency-light batch-protocol client only (`POST /jobs`
plus `GET /jobs/{id}`), with zero `:stt`/`:recorder`/`:voicenotes`/Room/`WorkManager` coupling:

```kotlin
implementation("com.github.Ygaray.voice-engine-android:voice-engine-android-transcription:v0.3.0")   // immutable tag — NEVER a branch snapshot
```

> **Groupid note.** Same wrinkle as `:recorder`/`:voicenotes` (steps 7–8): the groupId prefix is
> `com.github.Ygaray.voice-engine-android`, not the plain `com.github.Ygaray` shown for `:stt` — JitPack
> derives it from the mirror repository name for every secondary module (live-probed and recorded in
> `137-01-SUMMARY.md`; the previously-documented
> `com.github.Ygaray:voice-engine-android-transcription:v0.3.0` coordinate 401s).

Same pinning rule as steps 2, 7, and 8: pin a tag or a commit SHA, never `main-SNAPSHOT` or any moving
branch ref.

```kotlin
val client = createTranscriptionClient(serverUrl = "https://host.ts.net:8446")

// submit a recorded file
val outcome = client.submitJob(file, mimeType)

// poll a previously submitted job
val status = client.getJobStatus(jobId)
```

See `API.md`'s Transcription section for the full `SubmitOutcome`/`JobStatusOutcome`/
`TranscriptionErrorCode` taxonomy.

**Unlike `:voicenotes`, this module has no Room-backed persistence of its own** — the caller owns
retry/polling/persistence entirely. This is the module-boundary contract, not an oversight: a host
that wants durable, drain-on-a-schedule note tracking should adopt `:voicenotes` instead (step 8); a
host that just needs the raw wire protocol against its own persistence layer adopts `:transcription`
directly.

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
