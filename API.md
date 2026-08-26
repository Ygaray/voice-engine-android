# API.md — `voice-engine-android` public surface

Everything a consumer touches. Package root: `io.github.ygaray.sttengine`. To wire it into an app,
see **[`INTEGRATION.md`](INTEGRATION.md)**; for the reuse rules, **[`CLAUDE.md`](CLAUDE.md)**.

This document mirrors **`stt/api/stt.api`**, the committed golden dump of the module's release
compilation. That file is the mechanical source of truth: `./gradlew :stt:apiCheck` fails the build
on any change to a public signature, and it runs as part of `:stt:check`. If this document and that
file ever disagree, the file is right and this document is a bug.

## Surface at a glance

| Symbol | Kind | Purpose |
|---|---|---|
| `createSttEngine(context, config, diagnosticLogging)` | top-level function | the library's **only** entry point |
| `SttEngine` | interface | six flow properties + `start()` / `stop()` |
| `EngineState` | enum (5 entries + `wireName`) | the observable state machine |
| `SttConfig` | class | recognition settings (`language`) |
| `FinalSegment` | class | a committed, immutable transcript segment |
| `ReconnectInfo` | class | which bounded recovery attempt is in flight |
| `SttError` | class | a typed terminal failure (`code`, `message`, `platformDetail`) |
| `SttErrorCode` | value class + companion | the error taxonomy; 14 constants, two named sets |
| `SttWarning` | class | non-fatal transient warning — **never emitted on this backend** |
| `SttEvent` | sealed interface (7 members) | the same seven events as one union, for a shared `when` |

Nothing else is public. Every implementation type — the recognizer seam, the segment accumulator,
the session epoch, the capability ladder, the restart policy, the error mapper, the session log — is
`internal`, and `explicitApi()` plus the frozen dump is what makes that mechanical rather than a
convention.

---

## `createSttEngine` — the entry point

```kotlin
public fun createSttEngine(
    context: Context,
    config: SttConfig = SttConfig(),
    diagnosticLogging: Boolean = false,
): SttEngine
```

| Parameter | Meaning |
|---|---|
| `context` | any context — the **application** context is taken from it and retained, so passing an Activity leaks nothing |
| `config` | recognition settings; see `SttConfig` |
| `diagnosticLogging` | writes a per-session JSONL trace to this app's own external files directory. **Off by default and it must stay off in a shipped build**: the trace contains the user's dictated speech, so a diagnostic that quietly became always-on would turn a private, offline engine into a transcript on disk. |

It is a **top-level** function, so from Kotlin you just import it. From Java it is a static method on
the synthetic file class `SttEngineFactoryKt` — that name appears in the golden dump and is therefore
part of the frozen surface, so the *file* `SttEngineFactory.kt` cannot be renamed without breaking
Java callers.

Configuration is taken at **construction**, not reconfigurable afterwards. That is deliberate: it
means enabling a diagnostic that records speech is a decision made in writing at one call site, and
that a language change is a new engine rather than a mutation of a live session.

No network is used and none can be. The engine constructs only the system-resolved **on-device**
recognizer and never falls back to the network-backed one; an unavailable on-device recognizer is a
typed `recognizer-unavailable` failure, not a reason to switch backends.

---

## `SttEngine` — the contract

Seven flow properties and two suspend functions. **The delivery semantics differ per property, and
they are part of the contract, not an implementation detail** — choosing the wrong one silently
loses dictated text.

| Member | Type | Delivery | Why |
|---|---|---|---|
| `state` | `StateFlow<EngineState>` | **replays** the current value to every new collector | a recomposing host reads the current state immediately rather than waiting for the next transition |
| `backend` | `StateFlow<Backend>` | **replays** the current value | Android-only additive metadata (Phase 9, SEL-03, D-01) — which of `Backend.Server`/`Backend.Native` is actually driving the session. Never widens `state`/`SttEvent`; a host that does not care never has to touch it. `Native` on the v1.0 default path (no `serverUrl`, or `prefer = OnDevice`) for the engine's entire life; flips to `Server` once a try-server window succeeds, and back to `Native` one-way on a mid-session drop (SEL-04) — never back to `Server` within the same session. |
| `partial` | `StateFlow<String>` | **replays**, conflated | "replaces the previous partial" *is* StateFlow conflation; a superseded partial is worthless and dropping it is correct |
| `finals` | `Flow<FinalSegment>` | **lossless, single-consumer** | see below |
| `errors` | `Flow<SttError>` | at most one per session | first-error-wins; `state == Error` is reached exactly once |
| `level` | `Flow<Float>` | conflated, **drops oldest** | a level frame is meaningful only at the instant it is produced; back-pressuring the recognizer callback to deliver a stale one is strictly worse than dropping it. Only meaningful while `state == Listening`. |
| `events` | `Flow<SttEvent>` | ordered mirror of all seven | the convenience union, for one shared `when` |

### `finals` is lossless, and that is why it has exactly one consumer

`finals` is backed by an **unlimited channel**, not a replay-0 shared flow. The difference is
load-bearing rather than stylistic: with a shared flow, every time a lifecycle-aware host detached on
backgrounding — which is *correct*, expected behaviour for a dictation app whose user switches away
mid-thought — any segment committed during that window would be silently discarded. That is data
loss of text the user actually spoke, not a UI glitch. An unlimited channel buffers instead, and the
host receives everything on reattach, in order.

The cost of that guarantee is that the stream has **exactly one consumer**. A second collector would
steal segments from the first, splitting the user's dictation between two lists with no error to show
for it. Collect it once, at the highest point that outlives a configuration change. `events` is a
**mirror**, not a second consumer of this stream, so collecting both is safe.

### `start()` and `stop()`

```kotlin
public suspend fun start()
public suspend fun stop()
```

- **`start()` completes on reaching `Listening` *or* on a terminal error** — and on the error path it
  **returns rather than throwing**, having already emitted the typed error on `errors`. That is what
  stops a mic-denied or recognizer-less device from parking the caller forever on a state it will
  never reach. Inspect `state` after it returns, or collect `errors`; do not rely on an exception.
- **`stop()` flushes the open segment as a final first**, then tears down and returns to `Idle`, so
  text the user had already spoken is committed rather than dropped. Safe to call in any state,
  including from a terminal error.

---

## `EngineState`

`enum class EngineState(val wireName: String)` — five entries, frozen and identical to the
TypeScript union so a host can render one state banner against either backend.

| Entry | `wireName` | Meaning |
|---|---|---|
| `Idle` | `idle` | no session; the initial state and the one `stop()` returns to |
| `Connecting` | `connecting` | preflight, recognizer construction, first `startListening` |
| `Listening` | `listening` | the steady state — audio recognized, partials flowing |
| `Reconnecting` | `reconnecting` | a **bounded recovery** attempt is in flight |
| `Error` | `error` | terminal; a typed `SttError` was emitted exactly once |

`wireName` carries the exact lowercase string the TypeScript union uses, so logs and banners render
the *same* word on both backends. `toString()` on the entry would render `Idle`, not `idle`, and the
cross-language parity a host sees would silently be cosmetic only.

**`Reconnecting` is never emitted for an ordinary segment restart.** The restart-on-result loop that
makes continuous dictation possible on an API that only recognizes one utterance at a time would
otherwise flash this state after every pause. Only genuine recovery reaches it.

**v2 caveat:** adding a sixth value is **source-breaking** for a consumer with an exhaustive
`when (state)` and no `else`. Binary-compatible, not source-compatible. An enum is still right here —
this set is closed by design and a host genuinely wants the compiler to point at the new state.

---

## `SttConfig`

```kotlin
public class SttConfig @JvmOverloads constructor(
    public val language: String = "en",
    public val prefer: BackendPreference = BackendPreference.Auto,
    public val serverUrl: String? = null,
)
```

Three fields (Phase 9, D-01). `prefer` (`BackendPreference`) selects which `Backend` to prefer —
`Auto` (default) tries the self-hosted server first with a bounded timeout and falls back to native
silently; `Server` forces server-only (surfacing exactly one typed error on failure, no native
fallback); `OnDevice` skips the server phase entirely, byte-identical to the v1.0 default path.
`serverUrl` is the self-hosted STT server's WebSocket URL (e.g. `wss://host.ts.net:8444/`), or `null`
to disable the server backend entirely — a session with a `null` `serverUrl` always runs native,
regardless of `prefer`. There is no safe default hostname on Android the way `location.hostname`
exists in a browser, so the host must supply this explicitly to opt in.

`sampleRate` from the TypeScript options object stays **dropped, not stubbed**: the wire protocol's
sample rate is fixed (`16_000`) and is never a host-settable knob on either backend.

`@JvmOverloads` is required on this constructor — without it, adding `prefer`/`serverUrl` as two more
trailing-defaulted parameters would silently drop the single-arg `SttConfig(String)` overload from the
frozen `.api` dump (Kotlin's "every parameter has a default" special case only auto-generates the
zero-arg and full-arg constructors, not every arity in between).

`language` is passed straight through to `RecognizerIntent.EXTRA_LANGUAGE` and is checked against the
device's installed on-device models before any audio is captured. It exists from day one so that v2
multilingual is **additive** rather than a breaking contract change.

> **On the deferred multilingual path and API levels.** Earlier project notes stated that
> `RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH` is available at API 33. It is not. Verified against
> the local SDK API index: `EXTRA_ENABLE_LANGUAGE_SWITCH` and
> `EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES` arrived at **API 34**;
> `EXTRA_LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS` and
> `EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES` at **API 35**. This module's floor is **33**, so the
> in-session language-switch path is **not reachable at this module's minimum API level**. Nothing in
> this version depends on it — multilingual is deferred and out of scope, and the floor is not being
> raised for it — but no document here should be read as promising otherwise. Setting `language` per
> engine instance works at 33 and is unaffected.

---

## Model types

None of these is a `data class`. That is a binary-compatibility decision, not a style one — see
`CLAUDE.md`.

### `FinalSegment(text: String, segmentId: Int)`

A committed, immutable transcript segment. `equals` / `hashCode` / `toString` are hand-written.

**`segmentId` starts at `0`, is library-owned and monotonic within a session, and is OPAQUE and
PER-SESSION.** It is explicitly **not comparable across backends** and not a stable identifier
outside the session that produced it — the server-backed client restarts its numbering on reconnect,
and this backend owns its own counter. Treat it as an ordering key inside one session and nothing
more.

**Known seam (Phase 9): a mid-session server→native handoff resets the counter.** When a session
starts on the server backend and later drops to the on-device fallback (SEL-04), the synthetic final
`BackendSelector` commits for the in-flight partial is numbered `0`, and the native engine's own
`SegmentAccumulator` then starts numbering its own finals from `0` again — on the SAME
`start()`/`stop()` session, from a host's perspective. A host relying on `segmentId` for ordering or
de-duplication within one session can therefore observe `0` (or any other small value) twice across
a handoff. This is a real, documented gap, not an oversight: treat `segmentId` as reset, not
continuous, across a backend handoff.

### `ReconnectInfo(attempt: Int, max: Int)`

Carried by the `Reconnecting` event. `attempt` is **1-based**, so a host renders "attempt {n} of
{max}" directly.

### `SttError(code: SttErrorCode, message: String, platformDetail: Int? = null)`

A typed terminal failure. `code` drives logic; `message` diagnoses; `platformDetail` carries the raw
recognizer error integer when there is one (`null` for every preflight-originated failure). Friendly
hint copy is deliberately **not** part of this type — see the parity section.

### `SttWarning(code: String, active: Boolean, message: String)`

A non-fatal transient warning. **Structurally present and never emitted in this version, on either
backend.** On-device, the recognition service owns the microphone, so there is no outbound send
buffer and therefore no backpressure analog to warn about — that one is permanent by construction.
On the server backend, `BackendSelector` already forwards `ServerBackend.warnings` onto this event
(Phase 9), but nothing yet drives the outbound audio pump that would ever feed the backpressure gate
in production — that gap is temporary, deferred to a later phase. The type is kept anyway so a host
can share **one exhaustive `when`** across both backends without a platform fork in its rendering
code — which is the whole reason the event union survived translation unchanged.

---

## `SttErrorCode`

```kotlin
@JvmInline public value class SttErrorCode(public val value: String)
```

A **value class, not an enum**, and that is required rather than preferred:
`RecognitionListener.onError`'s own documentation requires handling integers outside its documented
constant set, so the backend must be able to produce a code it did not anticipate. An enum would make
every future code a source-breaking `when` for every consumer; the value class keeps the set open and
lets an unrecognized recognizer integer land as `internal-error` carrying the raw value in
`platformDetail`.

The companion exposes 14 constants and two named sets:

**`SttErrorCode.SHARED` — the frozen 11, byte-identical with the TypeScript union.** This is the
**portable subset** a host can rely on across both backends.

| Constant | String | Reachable on Android? |
|---|---|---|
| `VERSION_MISMATCH` | `version-mismatch` | no — no protocol in the path |
| `BUSY` | `busy` | yes (`ERROR_RECOGNIZER_BUSY`) |
| `INVALID_CONFIG` | `invalid-config` | no |
| `UNSUPPORTED_LANGUAGE` | `unsupported-language` | yes (`ERROR_LANGUAGE_NOT_SUPPORTED`, and the preflight) |
| `UNSUPPORTED_SAMPLE_RATE` | `unsupported-sample-rate` | no |
| `PROTOCOL_VIOLATION` | `protocol-violation` | no |
| `INTERNAL_ERROR` | `internal-error` | yes — including every unclassified recognizer integer |
| `MIC_DENIED` | `mic-denied` | yes (`ERROR_INSUFFICIENT_PERMISSIONS`, and the permission preflight) |
| `SERVER_UNREACHABLE` | `server-unreachable` | **depends on the active backend** (Phase 9, D-04): unreachable while on-device (native never touches a socket); reachable once a session has committed to the server backend — a genuine server-side failure there, not a bug signal |
| `RECONNECT_EXHAUSTED` | `reconnect-exhausted` | yes (`ERROR_TOO_MANY_REQUESTS` budget exhaustion) |
| `SEND_BACKPRESSURE` | `send-backpressure` | **depends on the active backend** (Phase 9, D-04): unreachable while on-device (no outbound buffer exists there); reachable once a session has committed to the server backend, where the ported `ServerBackpressureGate`'s sustained-overrun escalation can fire it |

The unreachable-while-on-device ones stay in `SHARED` because `SHARED` is the **cross-language**
contract, not the set every backend can emit in every configuration.

**`SttErrorCode.ANDROID_ONLY` — the three additive codes.**

| Constant | String | Names |
|---|---|---|
| `RECOGNIZER_UNAVAILABLE` | `recognizer-unavailable` | no on-device recognition service resolved on this device |
| `NO_OFFLINE_MODEL` | `no-offline-model` | the language is supported but its pack is not installed (or a download is already pending) |
| `AUDIO_ERROR` | `audio-error` | audio capture failed (`ERROR_AUDIO`), after one restart attempt |

The two sets are **disjoint**, and that is asserted by a test rather than reviewed.

---

## `SttEvent` — the seven events as one union

```kotlin
public sealed interface SttEvent
```

Collected via `SttEngine.events`. Seven members, the same seven the TypeScript event map names,
carrying the same payloads. Members are plain classes (not `data class`es) and carry `toString()`
only — events are transient envelopes and their payloads carry the equality.

| Member | Payload | Notes |
|---|---|---|
| `SttEvent.Partial` | `text: String` | live, in-progress text; latest-wins — each **replaces** the previous |
| `SttEvent.Final` | `segment: FinalSegment` | a committed, immutable segment |
| `SttEvent.State` | `state: EngineState` | a state-machine transition |
| `SttEvent.Error` | `error: SttError` | typed terminal failure; exactly once per session |
| `SttEvent.Level` | `value: Float` | input level `0.0..1.0`; conflated, drops expected |
| `SttEvent.Reconnecting` | `info: ReconnectInfo` | bounded recovery only — **not** ordinary restarts |
| `SttEvent.Warning` | `warning: SttWarning` | **structurally wired end-to-end (Phase 9), never actually emitted in this version**: `BackendSelector` forwards `ServerBackend.warnings` onto this event, but the only producer that would feed it — the outbound backpressure gate under a continuous audio-pump loop — is deferred to a later phase; see `SttWarning` |

`events` is an ordered **mirror** of the six properties, not a second consumer of them, so collecting
`events` and `finals` together is safe and neither steals from the other.

`sealed` deliberately makes an eighth event **source-breaking** for an exhaustive consumer `when` —
the same trade-off `EngineState` documents, and the opposite of the one `SttErrorCode` makes, for the
opposite reason: this set is closed by design.

---

## Recorder

A **separate, independently publishable module** (`:recorder`, JitPack artifact
`voice-engine-android-recorder`) — mic in, compressed `.m4a` file out, usable standalone by a
record-only consumer with zero dependency on recognition (REC-05, mechanically enforced by
`verifyNoRecognitionDependency`, wired into `:recorder:check`). A record-only host imports only this
module and never touches `SttEngine`.

```kotlin
public fun createRecorder(context: Context): Recorder
```

| Parameter | Meaning |
|---|---|
| `context` | any context — the application context is taken from it and retained, so passing an Activity leaks nothing |

**The host must already hold `RECORD_AUDIO`** — this module has no Activity, so it cannot request a
runtime permission, the same asymmetry `SttEngine` documents above. `INTEGRATION.md` §7 has the
worked snippet.

### `Recorder` — the contract

**Call `start()`/`pause()`/`resume()`/`stop()` from a background dispatcher.** Each is a `suspend fun`
that calls straight through to blocking native `android.media.MediaRecorder` operations
(`prepare()`/`start()`/`stop()`/`release()`), which can take tens to low-hundreds of milliseconds on
real devices. None of them internally confines to `Dispatchers.IO` — calling them from
`Dispatchers.Main` (e.g. the default `viewModelScope.launch { ... }`) risks blocking the main thread
long enough to ANR.

| Member | Type | Notes |
|---|---|---|
| `state` | `StateFlow<RecorderState>` | current state, replayed to every new collector |
| `level` | `Flow<Float>` | normalized `[0,1]` amplitude ticks, roughly every ~150ms, while `state` is `Recording`. Conflated — a stale meter frame is worthless, same rationale as `SttEngine.level`. Silent (no ticks), not zero-valued, before `start()` and while `Idle`/`Paused` — it never emits a `0f` placeholder. |
| `errors` | `Flow<RecorderError>` | typed Recorder failures, at most one path to `RecorderState.Error` per session |
| `start()` | `suspend fun` | begin a session: open a new output file and start capturing. A second `start()` while already `Recording`/`Paused` pushes `RecorderErrorCode.ALREADY_RECORDING` on `errors` and does **not** open a second session. |
| `pause()` | `suspend fun` | suspend the active session; writes to the same output file resume on `resume()`. `pause()` while not `Recording` pushes `RecorderErrorCode.INVALID_STATE`. |
| `resume()` | `suspend fun` | resume a suspended session. `resume()` while not `Paused` pushes `RecorderErrorCode.INVALID_STATE`. |
| `stop(): RecordedFile` | `suspend fun` | end the session and return the finished `RecordedFile`; `durationMs` excludes any time spent `Paused`; returns to `Idle`. `stop()` while not `Recording`/`Paused` — never started, or a previous `stop()` already completed — pushes `RecorderErrorCode.INVALID_STATE` on `errors` **and throws `IllegalStateException`** rather than returning stale or fabricated data. |
| `delete(uri: String)` | `suspend fun` | delete the file at `uri`. A no-op (not an error) if the file does not exist — idempotent by design (REC-03). Rejected with `RecorderErrorCode.INVALID_STATE` if `uri` is the currently open (not-yet-stopped) recording. |

**Platform amplitude asymmetry, documented not hidden (REC-06):** `level` is
`MediaRecorder.getMaxAmplitude()` **PEAK**, not RMS — there is no RMS variant exposed by the platform
API. The web Recorder's equivalent signal is **RMS** (`~10 Hz normalized RMS (0–1)`, `web/docs/API.md`).
A host that renders "the same meter feel" on both platforms without accounting for this will see
different behavior for identical audio.

### `RecorderState`

None of these is a `data class` — same binary-compatibility rule as the engine's own model types (see
`CLAUDE.md` invariant #2). A DISTINCT enum from `EngineState`: the Recorder and the recognition
engine are separate capabilities, and a record-only consumer never has to reason about recognition's
five-state machine to render a four-state recording UI.

`enum class RecorderState(val wireName: String)` — exactly four values, by locked design, with no
fifth transient/starting state. `state` stays `Idle` until `start()` either reaches `Recording` or
surfaces a typed error via `errors` (pushing `Error`).

| Entry | `wireName` | Meaning |
|---|---|---|
| `Idle` | `idle` | no active recording session — the initial state and the state `stop()` returns to |
| `Recording` | `recording` | a session is actively capturing audio to the output file |
| `Paused` | `paused` | the session is suspended: the underlying file is untouched until `resume()` |
| `Error` | `error` | terminal for the current session — a typed `RecorderError` was pushed on `errors` |

### `RecordedFile`

None of these is a `data class` (`CLAUDE.md` invariant #2) — a future optional field must be addable
without breaking every already-published consumer's resolved `copy()`/constructor ABI.

`RecordedFile(uri: String, durationMs: Long, format: String, createdAt: String)` — the result of a
completed recording session (REC-01):

| Field | Meaning |
|---|---|
| `uri` | An opaque, Recorder-owned locator — an internal, bare absolute filesystem path (e.g. `/data/user/0/<pkg>/files/<uuid>.m4a`) into the host app's private storage, with NO `file://` scheme prefix (`File(uri)`, not `Uri.parse(uri)`, is the correct way to resolve it). Never derived from consumer input. |
| `durationMs` | Wall-clock recording time, excluding any time spent `Paused`. |
| `format` | The concrete container/codec actually produced — always `"audio/mp4"` for this Recorder (`MediaRecorder.OutputFormat.MPEG_4` + `MediaRecorder.AudioEncoder.AAC`, REC-04). Reported from what was configured, not probed from the file. |
| `createdAt` | An ISO-8601 UTC timestamp captured at `start()` time. |

### `RecorderErrorCode` / `RecorderError`

```kotlin
@JvmInline public value class RecorderErrorCode(public val value: String)
```

A **value class, not an enum** — a FRESH, separate set from `SttErrorCode`, mirroring its open-set
idiom: `android.media.MediaRecorder` can raise failure modes this Recorder did not anticipate, so the
set stays open rather than a closed `enum` that would source-break every consumer's exhaustive `when`
the day a new code is added. Never add a Recorder-only value to `SttErrorCode.SHARED`/`.ANDROID_ONLY`,
and never touch `ErrorCodeParityTest`/`scripts/check-error-code-parity.sh` for this type — those gates
exist exclusively for the frozen 11-code recognition contract, unrelated to recording.

| Constant | String | When |
|---|---|---|
| `MIC_DENIED` | `mic-denied` | `MediaRecorder.start()` raised a `RuntimeException` — the mic is unavailable or busy |
| `NO_STORAGE` | `no-storage` | `prepare()`/`start()` raised an `IOException` — disk-full or a permission failure |
| `ALREADY_RECORDING` | `already-recording` | `start()` was called while a session was already `Recording`/`Paused` |
| `INVALID_STATE` | `invalid-state` | a call arrived out of order for the current `RecorderState` — `pause()` while not `Recording`, `resume()` while not `Paused`, or `delete(uri)` on the live recording's own `uri` |

`RecorderError(code: RecorderErrorCode, message: String)` — **deliberately NOT a `data class`**
(`CLAUDE.md` invariant #2), for the same binary-compatibility reason as every other model type here.

---

## Voice Notes

A third, independently publishable Gradle module (`:voicenotes`, JitPack artifact
`voice-engine-android-voicenotes`) — record-now / transcribe-when-reachable (NOTE-01..06): a thin
composition over `Recorder` above (capture) and the Phase 7 batch endpoint (transcription),
persisting every note transition to a Room-backed durable store as the source of truth.

```kotlin
public fun createVoiceNotes(context: Context, serverUrl: String): VoiceNotes
```

| Parameter | Meaning |
|---|---|
| `context` | any context — the application context is taken from it and retained, so passing an Activity leaks nothing |
| `serverUrl` | the Phase 7 batch endpoint's base URL (e.g. `https://host.ts.net:8446`) — `POST {serverUrl}/jobs` / `GET {serverUrl}/jobs/{id}` are built from it directly |

**There is no tailnet-default baked into `serverUrl`** here, unlike `createSttEngine`'s own
`wss://<hostname>:8444/` default — the host must supply it explicitly.

### `VoiceNotes` — the contract

Delivery semantics differ per property, deliberately, mirroring `SttEngine`'s own doc-comment table
convention:

| Member | Type | Notes |
|---|---|---|
| `notes` | `StateFlow<List<NoteRecord>>` | every known note, replayed to every new collector — a recomposing Compose host wants the current list immediately, same rationale as `SttEngine.state` |
| `noteTranscribed` | `Flow<NoteRecord>` | delivered when a consumer is alive AND persisted onto the corresponding `NoteRecord` (NOTE-04) — never event-only. **Lossless**, not a `StateFlow`: a transcript that has already been delivered must never be silently replaced/dropped for a late collector, mirroring `SttEngine.finals`'s rationale. |
| `pendingCount` | `StateFlow<Int>` | count of notes whose `status` is `Queued`, `Transcribing`, or the retryable `Failed` — i.e. not yet `Done` |
| `lastSyncedAt` | `StateFlow<String?>` | ISO-8601 UTC timestamp of the most recent successful drain (a note reaching `Done`), or `null` if none has ever succeeded |
| `record()` | `suspend fun` | begin recording a new note — forwards to `Recorder.start()`. Record-time failures (`NoteErrorCode.MIC_DENIED`/`.NO_STORAGE`/`.ALREADY_RECORDING`) surface through the Recorder's own error path — the note-contract decision reuses Phase 8's Recorder error vocabulary rather than inventing a parallel taxonomy. |
| `stopAndEnqueue(): NoteRecord` | `suspend fun` | stop the in-progress recording, persist a new `NoteRecord` with `NoteStatus.Queued` (durable BEFORE returning — a recorded note must never be lost), and schedule its drain. Returns the freshly persisted `NoteRecord`. |
| `drainNow()` | `suspend fun` | force a drain attempt now, bypassing any backoff wait currently in effect — without cancelling an attempt already in flight (never blind-replaces a running drain) |
| `delete(id: String)` | `suspend fun` | remove a note and its underlying recorded file. Delegates file removal to `Recorder.delete()` using the note's own stored `NoteRecord.uri` unchanged — never reconstructing a path from `id` or any other field. |

**NOTE-04's persist-before-notify guarantee, stated explicitly:** every implementation persists a
note's new state to the durable store BEFORE emitting `noteTranscribed` for it — a consumer that
calls into `notes` from inside its own `noteTranscribed` collector must see the already-persisted
transcript, not a stale `Transcribing` row.

**Web vs. Android: the drain asymmetry (read this before shipping).** This Android implementation
drains via `WorkManager`, in the background, surviving app close — the opposite of the web
`createVoiceNote()` backend, which drains only while its page is open (an in-page timer loop; no
background-execution capability exists on the web platform). A host targeting both platforms must
not assume identical drain timing.

### `NoteRecord`

None of these is a `data class` (`CLAUDE.md` invariant #2) — a future optional field must be addable
without breaking every already-published consumer's resolved `copy()`/constructor ABI. Since this is
a plain class it has no `copy()`: constructing a new `NoteRecord(...)` listing every field explicitly
is required whenever a note's state needs to change.

`NoteRecord(id: String, uri: String, format: String, createdAt: String, durationMs: Long, status:
NoteStatus, jobId: String? = null, transcript: String? = null, errorCode: NoteErrorCode? = null,
errorMessage: String? = null)` — 10 fields:

| Field | Meaning |
|---|---|
| `id` | a client-generated, self-issued identifier (never derived from any external input, mirroring `RecordedFile.uri`'s rule) |
| `uri` | `RecordedFile.uri` embedded VERBATIM — the Recorder's own opaque, Recorder-owned locator. Never reconstructed from `id` or any other field for a filesystem path. |
| `format` | `RecordedFile.format` embedded verbatim — always `"audio/mp4"` for this Recorder |
| `createdAt` | `RecordedFile.createdAt` embedded verbatim — an ISO-8601 UTC timestamp captured at record time |
| `durationMs` | `RecordedFile.durationMs` embedded verbatim — wall-clock recording time |
| `status` | the note's current lifecycle stage — see `NoteStatus` |
| `jobId` | the batch endpoint's job id once `POST /jobs` has succeeded, persisted BEFORE any polling begins — `null` until then, and cleared again if a `GET /jobs/{id}` 404s after eviction/server-restart (the job reference was lost, not the transcription) |
| `transcript` | non-null exactly when `status` is `Done` — persisted BEFORE `noteTranscribed` is emitted |
| `errorCode` | non-null exactly when `status` is `Failed` — see `NoteErrorCode` |
| `errorMessage` | a human-readable detail accompanying `errorCode`, non-null exactly when `status` is `Failed` |

### `NoteStatus`

A closed set by design (mirrors `EngineState`, not `RecorderErrorCode`/`NoteErrorCode`): a host
genuinely wants the compiler to point at a new lifecycle stage. `recorded -> queued -> transcribing
-> done | failed`.

| Entry | `wireName` | Meaning |
|---|---|---|
| `Recorded` | `recorded` | `Recorder.stop()` returned, not yet queued for drain. **NOT currently persisted or observed by this implementation** — `stopAndEnqueue()` constructs the first-ever `NoteRecord` directly with `Queued`; there is no intermediate durable write at this stage. This value is reserved for a future/alternate implementation; a consumer's exhaustive `when` over `NoteStatus` must still handle it, but it will never actually be observed in the current codebase. |
| `Queued` | `queued` | persisted to the durable store and enqueued for drain, no job id obtained yet |
| `Transcribing` | `transcribing` | a job id was obtained from `POST /jobs` and is being polled via `GET /jobs/{id}` |
| `Done` | `done` | terminal success: `transcript` is non-null |
| `Failed` | `failed` | terminal-for-now failure: `errorCode`/`errorMessage` are set. Retryable. |

### `NoteErrorCode` / `NoteError`

```kotlin
@JvmInline public value class NoteErrorCode(public val value: String)
```

A **value class, not an enum** — a FRESH, separate set from `SttErrorCode` and from
`RecorderErrorCode`, mirroring `RecorderErrorCode`'s open-set idiom: the drain worker can raise
failure modes this module did not anticipate, so the set stays open rather than a closed `enum` that
would source-break every consumer's exhaustive `when` the day a new code is added. Never add a
note-only value to `SttErrorCode.SHARED`/`.ANDROID_ONLY`, and never touch `ErrorCodeParityTest`/
`scripts/check-error-code-parity.sh` for this type.

7 companion constants — the first four are **reused verbatim** from `RecorderErrorCode`, per the
note-contract decision ("reuse Phase 8's Recorder error vocabulary for record-time failures — do not
invent a parallel taxonomy"); the remaining three are new upload/drain-specific codes this module
owns:

| Constant | String | When |
|---|---|---|
| `MIC_DENIED` | `mic-denied` | `MediaRecorder.start()` raised a `RuntimeException` — the mic is unavailable or busy |
| `NO_STORAGE` | `no-storage` | `prepare()`/`start()` raised an `IOException` — disk-full or a permission failure |
| `ALREADY_RECORDING` | `already-recording` | `record()` was called while a session was already in progress |
| `INVALID_STATE` | `invalid-state` | a call arrived out of order for the note's current lifecycle stage |
| `UPLOAD_TOO_LARGE` | `upload-too-large` | `POST /jobs` rejected the upload as exceeding the server's enforced size cap |
| `UPLOAD_REJECTED` | `upload-rejected` | `POST /jobs` rejected the upload for a reason other than size (e.g. malformed multipart) |
| `TRANSCRIPTION_FAILED` | `transcription-failed` | `GET /jobs/{id}` reached a terminal `status=failed` — a typed, permanent transcription failure |

`NoteError(code: NoteErrorCode, message: String)` — **deliberately NOT a `data class`** (`CLAUDE.md`
invariant #2), for the same binary-compatibility reason as every other model type here.

---

## Contract parity with the TypeScript client

The Kotlin surface is a **translation**, not a redesign: every name, every state value and every
error string was copied across.

| TypeScript | Kotlin | Same? |
|---|---|---|
| `EngineState` union (5 strings) | `EngineState` enum + `wireName` (5) | **identical strings** |
| `FinalSegment { text, segmentId }` | `FinalSegment(text, segmentId)` | identical |
| `ReconnectInfo { attempt, max }` | `ReconnectInfo(attempt, max)` | identical, `attempt` 1-based on both |
| `SttWarning { code, active, message }` | `SttWarning(code, active, message)` | identical shape; never emitted while on-device, and **still never emitted on the server backend either** in this version — the forwarding wire exists (Phase 9), but its only producer (the outbound audio pump) is deferred |
| `SttError { code, message }` | `SttError(code, message, platformDetail)` | **+1 field** — divergence 2 |
| `SttErrorCode` union (7 server + 4 client = 11) | `SttErrorCode.SHARED` (11) | **byte-identical** |
| — | `SttErrorCode.ANDROID_ONLY` (3) | **additive** — divergence 1 |
| `SttEventMap` (7 events) | `SttEvent` sealed interface (7 members) | same names, same payloads |
| `on(event, cb)` returning an unsubscribe handle | seven `Flow` / `StateFlow` properties + `events` | mechanism differs, event set identical |
| `SttEngineOptions { serverUrl?, language? }` | `SttConfig(language, prefer, serverUrl)` | `serverUrl` re-added (Phase 9, D-01) — `prefer`/`backend` are Android-only additive; `sampleRate` stays **dropped, not stubbed** |
| engine requests microphone access itself | **host** holds `RECORD_AUDIO` | divergence 3 |
| `start(): Promise<void>` / `stop()` | `suspend fun start()` / `suspend fun stop()` | same settle semantics |

A three-way guard (`scripts/check-error-code-parity.sh` in the development mono-repo, plus a Kotlin
test that runs in the release path) fails the build if the Python, TypeScript and Kotlin error sets
ever drift apart. The gate has been observed to fail on a deliberate one-character change; a guard
that has never been seen to fail is not a guard.

### The three places the contract deliberately bends

**1. Three additive error codes exist here and not in the TypeScript union.**

They name failures the socket-backed client cannot physically have: no recognition service on the
device, no offline language pack installed, and a local audio-capture failure. The alternative —
coercing them into the frozen eleven — forces real lies. The dominant first-run failure on Android is
"this language is supported but not downloaded yet", whose correct host action is *trigger the
download*; squeezing it into `unsupported-language` tells the host to give up instead. That is a
silently-wrong code, which is worse than an additive one.

**These three codes are NOT added to the TypeScript union, and that is the point.** `SttErrorCode` is
a *closed* union on the TypeScript side; widening it breaks every existing web host's exhaustive
`switch`. Adding to a closed union is the actual breaking cross-language event — a documented Kotlin
**superset** is not. A host that wants portable handling branches on `SttErrorCode.SHARED` and treats
anything else as "unknown, show the raw code".

**2. `SttError` carries a platform-local `platformDetail: Int?`.**

It holds the raw Android recognizer error constant, so a loud error banner or a bug report always has
full fidelity — `platformDetail` is frequently the only thing that distinguishes two failures the
taxonomy maps to the same code. **The web `SttError` shape is untouched**; this field is deliberately
platform-local and a cross-backend host simply ignores it on the web side.

**3. The host holds the microphone permission.**

The web engine requests microphone access itself, through the browser, as part of the capture call. A
library with no Activity cannot do that. So the **host must hold `RECORD_AUDIO`**, and the library's
half of the deal is that it **checks** and reports a missing grant as a typed `mic-denied` error on
`errors` — never as a thrown exception, so a host that forgets does not crash, it fails the same
loud, diagnosable way every time. This is a real difference in the adoption contract, documented
rather than smoothed over. `INTEGRATION.md` step 3 has the worked snippet.

---

## What is deliberately *not* here

- **Friendly error copy.** Hints are advice to a particular user of a particular app: "allow the mic
  in your browser settings" is wrong on Android, "open Settings → Voice input" is wrong on the web.
  Baking copy into the shared taxonomy would either lie to one platform or force every consumer to
  ship the same wrong sentence. The `:demo` module owns its hint map; yours owns yours.
- **A recognizer seam, a rung selector, a restart-policy knob.** The capability ladder, the restart
  budget and the segmentation strategy are the library's own business and are free to change. A
  public accessor would invite hosts to build behaviour on an implementation detail.
- **`SttEngineImpl` and every lifecycle core.** `internal`, and the frozen dump proves it.
