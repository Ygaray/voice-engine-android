# API.md — `stt-engine-android` public surface

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

Six flow properties and two suspend functions. **The delivery semantics differ per property, and
they are part of the contract, not an implementation detail** — choosing the wrong one silently
loses dictated text.

| Member | Type | Delivery | Why |
|---|---|---|---|
| `state` | `StateFlow<EngineState>` | **replays** the current value to every new collector | a recomposing host reads the current state immediately rather than waiting for the next transition |
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
public class SttConfig(public val language: String = "en")
```

One field. `serverUrl` and `sampleRate` from the TypeScript options object are **dropped, not
stubbed**: there is no server and no wire format on this backend, so both would be knobs a host could
set and watch do nothing. Stubbing them would advertise a capability that does not exist. A future
server-backed Android backend gets its own factory and its own config type.

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

### `ReconnectInfo(attempt: Int, max: Int)`

Carried by the `Reconnecting` event. `attempt` is **1-based**, so a host renders "attempt {n} of
{max}" directly.

### `SttError(code: SttErrorCode, message: String, platformDetail: Int? = null)`

A typed terminal failure. `code` drives logic; `message` diagnoses; `platformDetail` carries the raw
recognizer error integer when there is one (`null` for every preflight-originated failure). Friendly
hint copy is deliberately **not** part of this type — see the parity section.

### `SttWarning(code: String, active: Boolean, message: String)`

A non-fatal transient warning. **Structurally present and never emitted in this version.** The
recognition service owns the microphone, so there is no outbound send buffer and therefore no
backpressure analog to warn about. The type is kept anyway so a host can share **one exhaustive
`when`** across both backends without a platform fork in its rendering code — which is the whole
reason the event union survived translation unchanged.

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
| `SERVER_UNREACHABLE` | `server-unreachable` | yes, and it is a **bug signal**: seeing it means the session was not on-device |
| `RECONNECT_EXHAUSTED` | `reconnect-exhausted` | yes (`ERROR_TOO_MANY_REQUESTS` budget exhaustion) |
| `SEND_BACKPRESSURE` | `send-backpressure` | no — no outbound buffer exists |

The unreachable ones stay in `SHARED` because `SHARED` is the **cross-language** contract, not the
set this backend can emit.

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
| `SttEvent.Warning` | `warning: SttWarning` | **never emitted on this backend** — see `SttWarning` |

`events` is an ordered **mirror** of the six properties, not a second consumer of them, so collecting
`events` and `finals` together is safe and neither steals from the other.

`sealed` deliberately makes an eighth event **source-breaking** for an exhaustive consumer `when` —
the same trade-off `EngineState` documents, and the opposite of the one `SttErrorCode` makes, for the
opposite reason: this set is closed by design.

---

## Contract parity with the TypeScript client

The Kotlin surface is a **translation**, not a redesign: every name, every state value and every
error string was copied across.

| TypeScript | Kotlin | Same? |
|---|---|---|
| `EngineState` union (5 strings) | `EngineState` enum + `wireName` (5) | **identical strings** |
| `FinalSegment { text, segmentId }` | `FinalSegment(text, segmentId)` | identical |
| `ReconnectInfo { attempt, max }` | `ReconnectInfo(attempt, max)` | identical, `attempt` 1-based on both |
| `SttWarning { code, active, message }` | `SttWarning(code, active, message)` | identical shape; **never emitted here** |
| `SttError { code, message }` | `SttError(code, message, platformDetail)` | **+1 field** — divergence 2 |
| `SttErrorCode` union (7 server + 4 client = 11) | `SttErrorCode.SHARED` (11) | **byte-identical** |
| — | `SttErrorCode.ANDROID_ONLY` (3) | **additive** — divergence 1 |
| `SttEventMap` (7 events) | `SttEvent` sealed interface (7 members) | same names, same payloads |
| `on(event, cb)` returning an unsubscribe handle | six `Flow` / `StateFlow` properties + `events` | mechanism differs, event set identical |
| `SttEngineOptions { serverUrl?, language? }` | `SttConfig(language)` | `serverUrl` / `sampleRate` **dropped, not stubbed** |
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
