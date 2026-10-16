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
| `checkLanguageAvailability(context, langs)` | top-level `suspend` function | ask whether the `en` / `es` on-device packs are usable, with no session — see [Language packs](#language-packs--checklanguageavailability--requestlanguagepackdownload) |
| `requestLanguagePackDownload(context, lang)` | top-level `suspend` function | ask the platform to download a missing `en` / `es` pack; reports acceptance only |
| `LanguageAvailability` | enum (5 entries) | one language's on-device pack state, as the preflight and a native `"auto"` start read it |
| `submitCorrection(context, httpBaseUrl, sessionId, corrected, language)` | top-level `suspend` function | send the user's corrected text for a retained session or clip; returns a typed result and never throws on network errors — see [Corpus corrections](#corpus-corrections--submitcorrection) |
| `CorrectionSubmitResult` | enum (4 entries) | what happened to one correction: `ACCEPTED`, `UNKNOWN_SESSION`, `UNREACHABLE`, `REJECTED` |
| `SttEngine` | interface | seven flow properties, the `lastFinal` and `lastSessionId` snapshots, `start()` / `stop()` |
| `EngineState` | enum (5 entries + `wireName`) | the observable state machine |
| `SttConfig` | class | recognition settings (`language`, backend preference, server URL, try window, corrections, vocabulary, the voice-corpus opt-in `retainForCorpus`) |
| `FinalSegment` | class | a committed, immutable transcript segment |
| `LanguageSource` | enum (2 entries) | where a `FinalSegment.language` label came from: a detection or a default stamp |
| `ReconnectInfo` | class | which bounded recovery attempt is in flight |
| `SttError` | class | a typed terminal failure (`code`, `message`, `platformDetail`) |
| `SttErrorCode` | value class + companion | the error taxonomy; 14 constants, two named sets |
| `SttWarning` | class | non-fatal transient warning — emitted only on the server backend (`backpressure`); never on-device |
| `SttEvent` | sealed interface (7 members) | the same seven events as one union, for a shared `when` |
| `RecorderConfig` | class (`:recorder`) | host-configurable level cadence + MediaRecorder encoding params — see [Recorder](#recorder) |
| `RecorderForegroundService` | `Service` + binder (`:recorder`) | binder-only bound/started foreground-service seam, host-supplied notification via `RecorderNotificationConfig` — see [Recorder](#recorder) |
| `Corrections` | object (`:corrections`) | `deriveCorrections` / `apply` / `buildRuleset` — mine, apply, and build a bounded correction ruleset — see [Corrections](#corrections) |

Nothing else is public. Every implementation type — the recognizer seam, the segment accumulator,
the session epoch, the capability ladder, the restart policy, the error mapper, the session log — is
`internal`, and `explicitApi()` plus the frozen dump is what makes that mechanical rather than a
convention.

_(This index covers `:stt`'s own surface plus the two symbols this release adds to `:recorder`. The
rest of `:recorder`/`:voicenotes`/`:transcription`'s public surface is documented in their own
sections below without a top-level index entry — a pre-existing gap in this table predating this
release, not something this phase's growth introduced or is scoped to close.)_

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

With no `serverUrl` (the default), no network is used and none can be. The native path constructs
only the system-resolved **on-device** recognizer and never falls back to the platform's
network-backed one; an unavailable on-device recognizer is a typed `recognizer-unavailable` failure,
not a reason to switch backends. The only network the library ever touches is the self-hosted server
a host opts into through `SttConfig.serverUrl`.

---

## Language packs — `checkLanguageAvailability` / `requestLanguagePackDownload`

```kotlin
public suspend fun checkLanguageAvailability(
    context: Context,
    langs: Set<String> = setOf("en", "es"),
): Map<String, LanguageAvailability>

public suspend fun requestLanguagePackDownload(context: Context, lang: String): Boolean
```

Two top-level `suspend` functions in `SttEngineFactory.kt`, next to `createSttEngine`. They are
**Kotlin-coroutines-only**: from Java a `suspend` function needs a hand-built `Continuation`, which is
not a supported way to call them. Together they let a host find out **before** starting a session
whether the English and Spanish on-device packs are usable, and fix a missing one — without the host
ever holding a `SpeechRecognizer` of its own.

```kotlin
lifecycleScope.launch {
    val packs = checkLanguageAvailability(context)        // {en=INSTALLED, es=DOWNLOADABLE}
    if (packs["es"] == LanguageAvailability.DOWNLOADABLE) {
        val accepted = requestLanguagePackDownload(context, "es")
        // later: call checkLanguageAvailability(context) again to see whether it has arrived
    }
}
```

### Keys are strict

Only the exact, case-sensitive keys **`"en"`** and **`"es"`** are recognized. Anything else —
including a region-qualified tag such as `"en-US"` and a case variant such as `"EN"` — reads
`UNSUPPORTED` from the preflight and makes the download return `false`, **without contacting the
recognition service**. The result map is keyed by the caller's own strings, in the iteration order of
`langs`, with one value per requested key.

### The preflight agrees with session start

The preflight and a native `"auto"` session start ask the platform the same question and classify the
answer the same way, so on **API 34+** a value read here predicts what `start()` does:

| `LanguageAvailability` | What a native `"auto"` session start does (API 34+) |
|---|---|
| `INSTALLED` | starts (`listening`) |
| `DOWNLOADABLE` | fails `no-offline-model` |
| `PENDING` | fails `no-offline-model` |
| `UNSUPPORTED` | fails `unsupported-language` |
| `UNKNOWN` | starts — a query that could not answer never blocks a session |
| recognizer unavailable (the preflight reads every key `UNSUPPORTED`) | fails `recognizer-unavailable` |

Below API 34 an `"auto"` session uses a single language (see `SttConfig.language`), so only that
language's value predicts it.

### What the download result means

`requestLanguagePackDownload` reports whether the platform **accepted** the request, not whether the
download finished.

- **Idempotent.** If the pack is already `INSTALLED` or a download is already `PENDING`, it returns
  `true` without asking the platform again, so no system prompt appears for a pack the user already
  has.
- **`true` on Android 14 and later** means the platform acknowledged, scheduled or started the
  download. A recognition service that cannot report download progress is asked again without
  progress reporting, and `true` then carries the Android 13 meaning below. **On Android 13** it only
  means the request was passed on to the recognition service without an error — that version reports
  nothing back. If the service could not be reached to check the pack first, Android 13 sends no
  request and the result is `false`.
- **`false`** means the request was refused, the language is unsupported on this device, or
  acceptance was not confirmed within about five seconds (a user-approval prompt can outlast that).
  **Re-query with `checkLanguageAvailability` rather than calling the download again in a loop.**
- The download itself **needs a network connection** and may wait for an unmetered one, and the pack
  can keep reading `DOWNLOADABLE` while it downloads. No progress is reported — any download UI is
  the host's job.

### Threading, timing and caching

- **Both functions hop to the main thread themselves.** Call them from a coroutine — `lifecycleScope`,
  `viewModelScope`, a background scope — and the calling coroutine resumes in its own context.
  **Never call either from `runBlocking` on the main thread**: that blocks the very thread the answer
  needs.
- **Call them when no dictation session is active.** Each briefly creates and destroys its own
  on-device recognizer to ask.
- **Nothing is cached.** Every call asks the recognition service again, so query again after a
  download instead of reusing an earlier answer.
- Neither throws, except for cancellation of the calling coroutine. Cancelling the download call does
  not recall a request already handed to the platform. No `RECORD_AUDIO` grant is needed for either.

---

## Corpus corrections — `submitCorrection`

```kotlin
public suspend fun submitCorrection(
    context: Context,
    httpBaseUrl: String,
    sessionId: String,
    corrected: String,
    language: String? = null,
): CorrectionSubmitResult

public enum class CorrectionSubmitResult { ACCEPTED, UNKNOWN_SESSION, UNREACHABLE, REJECTED }
```

A top-level `suspend` function in `SubmitCorrection.kt` (JVM facade `SubmitCorrectionKt`) and a four-
entry enum, both in `:stt`. Together with `SttConfig.retainForCorpus`, `SttEngine.lastSessionId` and
the batch `submitJob(retainForCorpus = true)` overload they let a host keep a dictation session or a
batch clip in the server host's voice corpus and later send the text the user corrected it to. Like
the language-pack calls it is **Kotlin-coroutines-only**.

```kotlin
lifecycleScope.launch {
    engine.stop()
    val id = engine.lastSessionId ?: return@launch        // null: nothing was retained
    when (submitCorrection(context, "https://host.ts.net/SttBatch", id, editedText, language = "en")) {
        CorrectionSubmitResult.ACCEPTED -> markSynced()
        CorrectionSubmitResult.UNREACHABLE -> retryLater() // may already be stored
        else -> dropIt()
    }
}
```

| Parameter | Meaning |
|---|---|
| `context` | accepted for symmetry with the other top-level calls; currently unused and never retained |
| `httpBaseUrl` | the batch base URL the host already gives `createTranscriptionClient`; the request is `POST {httpBaseUrl}/corpus/{sessionId}/revisions`, with or without a trailing slash on the base |
| `sessionId` | the record id: `SttEngine.lastSessionId` for a live session, or the job id from `submitJob` for a batch clip (only once `getJobStatus` reports `Done`). Blank, `"."` and `".."` return `REJECTED` without a request |
| `corrected` | the user's corrected text; blank returns `REJECTED` without a request |
| `language` | the bare `"en"` or `"es"` the server accepts, or `null` to omit it. Unchecked on the client: a value the server refuses is `REJECTED` by the server |

### What the result means

| `CorrectionSubmitResult` | Meaning | Maps from |
|---|---|---|
| `ACCEPTED` | the server appended a revision; nothing was overwritten | any 2xx |
| `UNKNOWN_SESSION` | there is no such record; retrying the same id will not help. A host may treat it as a silent drop | a 404 (`not-found`) |
| `UNREACHABLE` | the outcome is unknown, the correction MAY or MAY NOT have been stored; a retry may help | a timeout, an IO or TLS failure, a 408, a 5xx, and any unexpected 1xx or 3xx answer |
| `REJECTED` | the input was refused and sending it again will not succeed | any other 4xx, a blank text or id, a dot-segment id, a base URL that is not a URL |

**Always include an `else` branch in a `when` over this type**, so an entry added later does not
break the host's build.

### Behavior

- **Main-safe and bounded.** The call hops to a background dispatcher itself and is bounded by a
  whole-call timeout of about 15 s. It never throws on a network, TLS, timeout or malformed-URL
  failure, but it does propagate cancellation of the calling coroutine (a cancelled caller is never
  turned into `UNREACHABLE`).
- **One request per call, no automatic retry, no idempotency key.** The HTTP client does not replay
  the POST after a dropped connection and does not follow redirects. `UNREACHABLE` therefore does not
  prove nothing was stored: a host retry after `UNREACHABLE` (a 503 included) can append a
  duplicate revision, which the corpus tolerates because it is append-only.
- **Append-only.** Each accepted call appends one revision to the record and nothing is overwritten.
  There is one corrected text per call; call again to add another.
- **The request body is `{corrected, language?}`.** The server stamps the revision time, so the
  client sends no timestamp. A body that carried `createdAt` (the server stamps it) is refused with
  a 400 (`invalid-revision`, so `REJECTED`). The line stored in the record is
  `{corrected, createdAt, language?}` with `createdAt` stamped by the server: that is the stored
  shape, not the request.
- **`context` is accepted and unused.**
- **Privacy.** The corrected text is never logged, echoed or placed in an exception message, and only
  the status code is read back.

### Server errors a host can hit

Read from the shipped server's correction route (`POST /corpus/{id}/revisions`), each with the
result this client returns:

| Server error | Status | Result | When |
|---|---|---|---|
| `not-found` | 404 | `UNKNOWN_SESSION` | no such record: never retained, retention failed, or an older server without the route |
| `invalid-revision` | 400 | `REJECTED` | bad JSON, an unknown key (a client-sent timestamp is one, the server stamps it), blank text, or a bad language |
| `payload-too-large` | 413 | `REJECTED` | the body is over 64 KiB |
| `revision-limit` | 409 | `REJECTED` | the record already holds 1000 revisions |
| `revision-busy` | 503 | `UNREACHABLE` | another append holds the record; retry shortly |
| `corpus-unavailable` | 503 | `UNREACHABLE` | retention is not armed on the server |
| `internal-error` | 500 | `UNREACHABLE` | the server could not store the correction |

### Retention limits, and how each transport signals them

Retention itself stops at 4 h of audio per record and when the corpus disk is under its 2 GiB
free-space floor. How a host learns that depends on the transport:

- **Live sessions.** A record longer than the 4 h cap, a corpus disk under the 2 GiB floor and a
  failed commit each end or discard the retention with no wire signal on a live session: dictation
  continues, the session still ends with close 1000, no record is committed, and a later correction
  for that id returns `UNKNOWN_SESSION`. `lastSessionId` can therefore be non-null for a record that
  does not exist.
- **Batch jobs.** For a job that was armed (`retained: true` on the `202`), the terminal
  `GET /jobs/{id}` carries an optional `retentionStatus` of `committed` or `failed`; see
  [Transcription](#transcription). This client tolerates the key and does not surface it.

### Privacy and exposure

The correction route has **no authentication beyond the tailnet**, the same posture as `POST /jobs`.
Record ids are 128-bit random and there is **no read route**, so an id is a capability to append a
revision to that record and nothing more: it cannot be used to read, edit or delete anything. Do
not publish ids. The corpus itself lives on the server host and is never readable over the network.

---

## `SttEngine` — the contract

Seven flow properties, two read-only snapshots (`lastFinal` and `lastSessionId`) and two suspend functions. **The
delivery semantics differ per property, and they are part of the contract, not an implementation
detail** — choosing the wrong one silently loses dictated text.

| Member | Type | Delivery | Why |
|---|---|---|---|
| `state` | `StateFlow<EngineState>` | **replays** the current value to every new collector | a recomposing host reads the current state immediately rather than waiting for the next transition |
| `backend` | `StateFlow<Backend>` | **replays** the current value | Android-only additive metadata (Phase 9, SEL-03, D-01) — which of `Backend.Server`/`Backend.Native` is actually driving the session. Never widens `state`/`SttEvent`; a host that does not care never has to touch it. `Native` on the v1.0 default path (no `serverUrl`, or `prefer = OnDevice`) for the engine's entire life; flips to `Server` once a try-server window succeeds, and back to `Native` one-way on a mid-session drop (SEL-04) — never back to `Server` within the same session. |
| `partial` | `StateFlow<String>` | **replays**, conflated | "replaces the previous partial" *is* StateFlow conflation; a superseded partial is worthless and dropping it is correct |
| `finals` | `Flow<FinalSegment>` | **lossless, single-consumer** | see below |
| `errors` | `Flow<SttError>` | at most one per session | first-error-wins; `state == Error` is reached exactly once |
| `level` | `Flow<Float>` | conflated, **drops oldest** | a level frame is meaningful only at the instant it is produced; back-pressuring the recognizer callback to deliver a stale one is strictly worse than dropping it. Only meaningful while `state == Listening`. Emitted on **both** backends: on the server path the engine computes it from the same audio it streams, so a meter does not go dead when `backend == Server`. |
| `events` | `Flow<SttEvent>` | ordered mirror of all seven | the convenience union, for one shared `when` |
| `lastFinal` | `FinalSegment?` | a plain read, **no flow** | the final the last `stop()` flushed, readable synchronously the moment `stop()` returns; see [`lastFinal`](#lastfinal--the-tail-after-stop) below |
| `lastSessionId` | `String?` | a plain read, **no flow** | the corpus record id of the session that just ended, when it was retained; see [`lastSessionId`](#lastsessionid--the-corpus-id-after-stop) below |

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
- **A second `start()` while a session is starting or live is ignored** — it returns at once and
  opens nothing (no second socket, no second recognizer). This is the same rule on both backends.
  After `stop()` or a terminal error, `start()` begins a fresh session.
- **`stop()` flushes the open segment as a final first**, then tears down and returns to `Idle`, so
  text the user had already spoken is committed rather than dropped. Safe to call in any state,
  including from a terminal error. Its signature is unchanged (`suspend fun stop()`, returning
  `Unit`).

**`stop()` latency on the server backend.** On the server path, `stop()` stops capture, sends the
remaining audio, sends the protocol's `stop` token, and then **waits up to about 2.5 s** for the
server to flush the tail of the utterance and close the connection normally. The flushed tail is
delivered on `finals` as usual, and it is readable from `lastFinal` as soon as `stop()` returns. If
the server does not finish within that bound, the engine commits the open partial locally as the
final instead, so the tail is never dropped. A requested stop never emits on `errors`, even if the
connection fails during the flush. On the native on-device backend `stop()` is unchanged and
returns promptly. A host that calls `stop()` from a UI handler should launch it in a coroutine
rather than block on it. `stop()` cannot be cut short by cancelling its caller: a caller whose scope
is torn down mid-stop still waits out the flush bound and only then sees its cancellation, so the
socket and microphone are always released. The flushed tail is on `finals` before `stop()` returns.
A `stop()` issued while the server try window is still connecting ends at `Idle`, like any other
stop.

### `lastFinal` — the tail after `stop()`

```kotlin
public val lastFinal: FinalSegment? get() = null
```

A read-only snapshot of the final the most recent `stop()` flushed — or, if nothing was open at
stop, the session's latest final.

- **Readable synchronously as soon as `stop()` returns**, with no `finals` collector kept alive
  across the stop. A host that wants the tail of a dictation (to insert into a note, say) reads it
  here instead of racing the lossless flow.
- It is the **same `FinalSegment` instance** that `finals` delivers (same `text`, `segmentId` and
  `language`). `finals` is unchanged and still delivers it, so a host that already collects `finals`
  keeps working.
- **`null` before the first final** of the current session. **`start()` resets it**, so a reused
  engine never reports a previous session's text. A terminal error does **not** clear it; only
  `start()` does.
- Works on both backends, including across a mid-session server-to-native handoff.
- `language` can be `null` for a stop-flushed native tail under `"auto"` (API 34+) when the session
  closed before a confident detection — "unknown", not "English".

**Migration:** `lastFinal` is a new **non-abstract** interface member with a default of `null`.
Existing implementers and test fakes need no change; they stay source- and binary-compatible
through the `SttEngine$DefaultImpls` bridge. A fake may override `lastFinal` to drive host logic.

### `lastSessionId` — the corpus id after `stop()`

```kotlin
public val lastSessionId: String? get() = null
```

A read-only snapshot of the corpus record id of the session that just ended. It is the handle you
pass to [`submitCorrection`](#corpus-corrections--submitcorrection).

- **Non-null only after `stop()`** on a session that opted in with `SttConfig.retainForCorpus`, ran
  on the server backend, never failed, was never handed off to the on-device recognizer, and whose
  server finished its stop path by closing the connection cleanly. The server attempts the corpus
  commit before that close, so the observed clean close is the earliest moment the record can exist.
- **Reset by `start()`**, so a reused engine never reports a previous session's id.
- **`null` on every other path:** while a session is live, after a flush that timed out, an error, an
  abnormal close, a mid-session fallback to the on-device recognizer, a stop during the try-server
  window, any native session, a server that sent no id, and a session that did not ask for retention.
  A `null` after a retained request therefore means "no record claimed" for one of several reasons the
  host cannot tell apart.
- **A record can exist without a handle.** `stop()` waits about 2.5 s for the server's tail flush, and
  on a retained session the server commits the record inside that window. When a long recording or a
  slow corpus disk pushes the commit past the bound, `stop()` gives up and `lastSessionId` is `null`
  although the server may still finish storing the record. That record is never correctable from the
  client.
- **A non-null id does not guarantee the record was stored.** A server-side retention failure on a
  live session sends no signal to the client, so a later correction for that id can come back
  `UNKNOWN_SESSION`.
- **Ordering.** The id is published before the engine reports `Idle`. A host whose `stop()` is
  asynchronous should wait for `EngineState.Idle` and only then read `lastSessionId`; a host that
  awaits `stop()` can read it as soon as `stop()` returns.
- **The id is a capability to append** corrections to that record. Do not publish it.

**Migration:** `lastSessionId` is a new **non-abstract** interface member with a default of `null`,
the same shape as `lastFinal`. Existing implementers and test fakes need no change and stay source-
and binary-compatible; a fake may override it to drive host logic.

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
    corrections: CorrectionRuleset? = null,
    vocabulary: List<String>? = null,
    serverTryTimeoutMs: Long = 10_000L,
    public val retainForCorpus: Boolean = false,
)
```

Seven fields (Phase 9 D-01 for `prefer`/`serverUrl`; Phase 13 CORR-01/D-05 for `corrections`; Phase 17
REL-03/BIAS-01 for `vocabulary`; Phase 21 TRY-01 for `serverTryTimeoutMs`; Phase 25 CORR-06 for
`retainForCorpus`). `prefer`
(`BackendPreference`) selects which `Backend` to prefer — `Auto` (default) tries the self-hosted
server first within the `serverTryTimeoutMs` window and falls back to native silently; `Server`
forces server-only (surfacing exactly one typed error on failure, no native fallback); `OnDevice`
skips the server phase entirely, byte-identical to the v1.0 default path.
`serverUrl` is the self-hosted STT server's WebSocket URL (e.g. `wss://host.ts.net:8444/`), or `null`
to disable the server backend entirely — a session with a `null` `serverUrl` always runs native,
regardless of `prefer`. There is no safe default hostname on Android the way `location.hostname`
exists in a browser, so the host must supply this explicitly to opt in.

`corrections` is the active correction ruleset (see [Corrections](#corrections)) —
`Corrections.apply` replays it against every committed final at each real emit-origin point;
partials are never corrected (CORR-03). `null` (the default) is a zero-cost no-op — a host that
never builds a ruleset pays nothing. The constructor takes an immutable snapshot
(`corrections?.toList()`) of the host-supplied list rather than aliasing it directly, so a host that
mutates its own live list afterward cannot tear a concurrent `apply()` call.

`vocabulary` is the host's personal vocabulary/term list (BIAS-01) — biases the live server-backed
session toward these names/jargon terms via the server's Whisper `hotwords`, the same live-path
parity `TranscriptionClient.submitJob`'s batch `vocabulary` parameter already has (see
[Transcription](#transcription)). `null` (the default) is a zero-cost no-op — the WS config frame's
`vocabulary` key is omitted entirely, not sent empty. Takes the same immutable-snapshot treatment
`corrections` gets, for the same concurrent-mutation reason, and is silently inert on a session that
never reaches the server backend (native on-device recognition has no concept of biasing hotwords).

`serverTryTimeoutMs` (TRY-01) is how long, in milliseconds, a server-eligible engine (`serverUrl`
set and `prefer` not `OnDevice`) keeps trying the server at session start before giving up. The
window bounds the whole connect → config → `ready` sequence, retries included, as **one deadline**.
**Defaults to 10 000 ms.** Any value is **clamped into 1 000..10 000 ms rather than rejected**, and
the property reports the clamped value. A session started while the server is cold-starting can wait
up to 10 s before the on-device fallback under `Auto`. How a failed try is treated depends on how far the
connection got:

- **Fails fast — before the host answers.** A DNS failure, no network, a refused connection, a
  server that reports itself busy, a rejected config or a TLS certificate failure gives up
  immediately rather than waiting out the window: `Auto` falls back to on-device at once, `Server`
  reports its error at once. This also holds when such a failure arrives wrapped inside another
  exception.
- **Retried within the window — the host answered but is not ready yet.** For example a server that
  is still starting up behind its TLS proxy, which accepts the connection and then drops it. The
  engine retries with a short backoff (250 ms, 500 ms, then 1 s) until the server is ready or the
  window ends.
- **Waits the full window — a black-holed host.** A host that never answers at all (packets
  silently dropped) cannot be told apart from a slow one, so it waits out the whole window before
  `Auto` falls back or `Server` reports an error.

No audio is captured while the window runs, so a longer window means more deaf time at session
start when the server is off or black-holed: up to 10 s with the default. Ignored when no server is
configured.

**Migration for `serverTryTimeoutMs`:** it is a trailing defaulted parameter, so existing Kotlin and
Java call sites compile unchanged (all earlier `@JvmOverloads` constructors remain; Java gains one
6-argument constructor). The synthetic `$default` constructor did change, so **Kotlin consumers must
recompile against the new artifact when they repin**. Behavior change: a server-eligible engine that
does not set the knob now gets a **10 000 ms** window, where the previous release (v0.7.0) used
2 500 ms. (A 5 000 ms value was set during development and never released.) A session started while
the server is cold-starting can therefore wait up to 10 s before the on-device fallback under
`Auto`. Refused and DNS-class failures still fail fast; only a black-holed host or a slow cold start
uses the longer window. A host that wants the old behavior passes `serverTryTimeoutMs = 2_500L`.

`retainForCorpus` (CORR-06) opts this **server** session into the host's voice corpus. When `true`,
the server keeps the session's audio and its committed finals as a record on the server host, and
the config frame carries one extra key, `retainForCorpus: true`. The default `false` retains nothing
and puts no new key on the wire, so a default config frame is byte-identical to the previous
release. Only a server session retains anything: a native session (no `serverUrl`, `prefer =
OnDevice`, or a mid-session fallback to the on-device recognizer), a server that predates the
corpus and a server with retention switched off all produce **no record and no error**. Read the
outcome from [`SttEngine.lastSessionId`](#lastsessionid--the-corpus-id-after-stop) after `stop()`.
The corpus lives on the server host and is never readable over the network; the host app owns the
user's consent for keeping their voice.

**Migration for `retainForCorpus`:** it is a trailing defaulted parameter, so existing Kotlin and
Java call sites compile unchanged (all earlier `@JvmOverloads` constructors remain; Java gains one
7-argument constructor). The synthetic `$default` constructor was replaced again, as it was for
`serverTryTimeoutMs`, so **Kotlin consumers must recompile against the new artifact when they
repin**. This is accepted under the relaxed additive rule (A20, teamlead ruling of 2026-10-16).

`sampleRate` from the TypeScript options object stays **dropped, not stubbed**: the wire protocol's
sample rate is fixed (`16_000`) and is never a host-settable knob on either backend.

`@JvmOverloads` is required on this constructor — without it, adding a new trailing-defaulted
parameter would silently drop a shorter-arity overload (e.g. the single-arg `SttConfig(String)`) from
the frozen `.api` dump (Kotlin's "every parameter has a default" special case only auto-generates the
zero-arg and full-arg constructors, not every arity in between). This is why `corrections` and
`vocabulary` were added as trailing, not inserted between existing parameters.

`language` is passed straight through to `RecognizerIntent.EXTRA_LANGUAGE` on the native backend, and
into the server's config frame on the server backend, and is checked against the device's installed
on-device models before any audio is captured (native path only). It exists from day one so that v2
multilingual is **additive** rather than a breaking contract change.

**Bilingual EN/ES `"auto"` works on BOTH backends.** On the **server** backend (LANG-01), `language`
accepts `"en"` or `"auto"` — `"auto"` opts into a server-detected-per-segment bilingual EN<->ES
session; the server does not currently accept a bare `"es"` here. **The server path accepts only
`"en"` or `"auto"`.** Any other tag — including a region-qualified one such as `"en-US"` — is
rejected by the server during the try window: under `Auto` the session fails fast and runs on
native instead, and under `Server` it surfaces `unsupported-language`. On the **native on-device** backend
(v3.1 / `v0.7.0`, NBIL-01..11), `SttConfig(language = "auto")` is now a first-class **offline** EN<->ES
mode, **runtime-gated to API 34+** (`SDK_INT >= 34`): it enables the platform's on-device
language-switch over the fixed `[en-US, es-US]` allowlist and labels each committed segment. **Below
API 34**, `"auto"` falls back to the configured default language (not an error). The engine enforces
the `{en, es}` allowlist **itself** — a non-en/es platform proposal is ignored (never committed as
foreign text), while a genuinely missing `en-US`/`es-US` pack fails loud with `no-offline-model`. The
detected language for each committed segment shows up on `FinalSegment.language` as bare `"en"`/`"es"`
(or `null` when no confidence-3 label) — see [Model types](#model-types) below. A `prefer = Auto`
session therefore bilingual-switches on either backend at API 34+; it no longer fails native
recognition. (A pure-native host that must guarantee bilingual capture below API 34 should use
`prefer = BackendPreference.Server`.)

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

### `FinalSegment(text: String, segmentId: Int, language: String? = null, languageSource: LanguageSource? = null)`

A committed, immutable transcript segment. `equals` / `hashCode` / `toString` are hand-written.
`@JvmOverloads` preserves the existing 2-arg `<init>(String, Int)` and 3-arg
`<init>(String, Int, String)` in the frozen `.api` dump alongside the 4-arg form, for the same reason
`SttConfig` documents above.

**`segmentId` starts at `0`, is library-owned and monotonic within a session, and is OPAQUE and
PER-SESSION.** It is explicitly **not comparable across backends** and not a stable identifier
outside the session that produced it — the server-backed client restarts its numbering on reconnect,
and this backend owns its own counter. Treat it as an ordering key inside one session and nothing
more.

**`language`** is the bare `"en"` / `"es"` label of a bilingual `"auto"` session, on either backend.
On the server backend it is the server's per-segment detection. On the native on-device backend it is
detected on-device from a highly confident event on API 34 and above, and it is the configured default
`"en"` on native below API 34. It is `null` for every non-`"auto"` session, and `null` when no label
was produced. A host that never runs a bilingual `"auto"` session sees `null` unconditionally and can
ignore the field entirely.

**`languageSource`** says whether that label is a detection or a default stamp. It is a closed
two-value enum, `LanguageSource.DETECTED` or `LanguageSource.FALLBACK`, or `null`.
A `DETECTED` label is the recognizer's own decision for that segment.
A `FALLBACK` label is a default stamp, not a detection result, and it may be a label the session already held. The
enum is closed, so an exhaustive `when` needs no `else` branch, and a non-`"auto"` session reads
`null`, never a third value. An absent or unrecognized value from a server is `null` and is never
mapped to `FALLBACK`.

The relationship between the two fields is one-way: a source is only ever present together with a
language, and the engine sets the two together, so a segment from the native engine or from a current
server has either both or neither. The single case of a language with no source is a server that
predates the field (row 9 below), and it means the engine makes no claim about where the label came
from. `null` must never be read as `FALLBACK`.

| # | Path | `language` | `languageSource` |
|---|---|---|---|
| 1 | native, API 34+, a confident `en`/`es` detection | the label | `DETECTED` |
| 2 | native, API 34+, no confident event for the segment | `null` | `null` |
| 3 | native, below API 34, `"auto"` session | `"en"` | `FALLBACK` |
| 4 | native, non-`"auto"` session | `null` | `null` |
| 5 | server `"auto"`, detection evidence for the segment (including when the previous label was kept because the challenger did not clear the switch margin) | the label | `DETECTED` |
| 6 | server `"auto"`, no usable `en`/`es` detection evidence for the segment | the label the session already held, or `"en"` when it held none | `FALLBACK` |
| 7 | server `"auto"`, language detection failed | `"en"` | `FALLBACK` |
| 8 | server, non-`"auto"` session | `null` | `null` |
| 9 | a server that predates the field, or a frame without the key | the label, if the frame carried one | `null` |
| 10 | a segment committed by a mid-session server-to-native handoff, or by a stop-flush timeout | `null` | `null` |

**Migration for `languageSource`:** the parameter is trailing and defaulted, so Kotlin and Java call
sites compile unchanged and every earlier constructor overload and getter stays. Two things are not
purely additive. First, the synthetic default-arguments constructor that Kotlin callers use is
replaced by one that includes the new parameter, so a module compiled against an earlier artifact and
linked against this one must be recompiled; apps do this on the dependency bump, and a pre-compiled
library that constructs `FinalSegment` with omitted arguments is the only case. Second, `equals`,
`hashCode` and `toString` now include the field, so a test that asserts equality between engine
output and a `FinalSegment` literal built with a language but without a source no longer matches:
pass the source in the literal, or compare the fields.

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

A non-fatal transient warning. **Emitted only on the server backend**, by the outbound backpressure
gate: the server path streams microphone audio over its socket, and when that send buffer runs
over its soft ceiling the engine sheds the oldest audio to keep up and emits
`SttWarning(code = "backpressure", active = true, …)` on `events`; once the buffer drains it emits
the matching `active = false`. A sustained overrun is not a warning but the terminal
`send-backpressure` error. **On-device it is never emitted, and that is permanent by construction**:
the recognition service owns the microphone, so there is no outbound send buffer to warn about. The
type is shared by both backends so a host can keep **one exhaustive `when`** across them without a
platform fork in its rendering code — which is the whole reason the event union survived
translation unchanged.

### `LanguageAvailability`

```kotlin
public enum class LanguageAvailability { INSTALLED, DOWNLOADABLE, PENDING, UNSUPPORTED, UNKNOWN }
```

One language's on-device pack state, returned per key by `checkLanguageAvailability` — the same
classification a native `"auto"` session start uses, so the two agree (see
[Language packs](#language-packs--checklanguageavailability--requestlanguagepackdownload)).

| Entry | Meaning |
|---|---|
| `INSTALLED` | the on-device model is installed; dictation in this language can start, offline |
| `DOWNLOADABLE` | supported here, but the model is not installed yet; `requestLanguagePackDownload` can fix it, and until then a session fails `no-offline-model` |
| `PENDING` | a download is already scheduled and may take hours (for example while waiting for an unmetered network); a session fails `no-offline-model` until it completes — query again later instead of requesting another download |
| `UNSUPPORTED` | no on-device model for this language on this device, and no download will help; a session fails `unsupported-language` |
| `UNKNOWN` | the recognition service could not tell (the query failed, timed out, or came back empty); a session still tries to start |

The entry order is frozen. **Always include an `else` branch in every `when` over it; `UNKNOWN` may
share it.** An explicit `UNKNOWN` branch is not a substitute for `else`: listing every entry makes the
`when` exhaustive without one, so an entry added later would break the host's build. And render
`UNKNOWN` as "unknown", never as "missing": it is a real answer, not an error, and a host that folds it
into "missing" will tell a user their language is unavailable on a device that can dictate in it.

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
| `SttEvent.Warning` | `warning: SttWarning` | non-fatal, transient; emitted on the server backend by the outbound backpressure gate (`backpressure`, active then cleared), never on-device; see `SttWarning` |

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
@JvmOverloads
public fun createRecorder(context: Context, config: RecorderConfig = RecorderConfig()): Recorder
```

| Parameter | Meaning |
|---|---|
| `context` | any context — the application context is taken from it and retained, so passing an Activity leaks nothing |
| `config` | host-configurable level cadence + MediaRecorder encoding parameters; defaults reproduce the library's pre-`RecorderConfig` behavior exactly (see `RecorderConfig` below) |

`@JvmOverloads` keeps the pre-existing one-argument Java entry point on the ABI alongside the new
two-argument one — a Java caller that only ever wrote `createRecorder(context)` keeps compiling
unchanged.

**The host must already hold `RECORD_AUDIO`** — this module has no Activity, so it cannot request a
runtime permission, the same asymmetry `SttEngine` documents above. `INTEGRATION.md` §7 has the
worked snippet.

### `RecorderConfig`

A plain class, **not** a `data class` (`CLAUDE.md` invariant #2) — a future optional field must be
addable without breaking every already-published consumer's resolved `copy()`/constructor ABI.

`RecorderConfig(levelIntervalMs: Long = 150L, sampleRateHz: Int? = null, encodingBitRate: Int? = null, channelCount: Int? = null)`:

| Field | Default | Meaning |
|---|---|---|
| `levelIntervalMs` | `150L` | the `level` amplitude-poll cadence in milliseconds. Must be `>= MIN_LEVEL_INTERVAL_MS` (a constructor `require`), enforced at construction so an invalid config fails immediately rather than producing a silently-too-fast poll loop. |
| `sampleRateHz` | `null` | forwarded to `MediaRecorder.setAudioSamplingRate` when non-null; `null` leaves the platform default untouched. |
| `encodingBitRate` | `null` | forwarded to `MediaRecorder.setAudioEncodingBitRate` when non-null; `null` leaves the platform default untouched. |
| `channelCount` | `null` | forwarded to `MediaRecorder.setAudioChannels` when non-null; `null` leaves the platform default untouched. |

`RecorderConfig.MIN_LEVEL_INTERVAL_MS` (`10L`, on the companion) is the floor for `levelIntervalMs` —
comfortably below any perceptible meter refresh rate.

**The default `RecorderConfig()` reproduces this library's behavior exactly as of the previous
release**: a 150ms level cadence and no encoding setters applied at all (the three encoding fields
default `null`, so `AndroidMediaRecorderSession` calls none of the three `MediaRecorder` setters and
the platform's own defaults for `MediaRecorder.OutputFormat.MPEG_4` + `MediaRecorder.AudioEncoder.AAC`
apply exactly as they did before `RecorderConfig` existed). Passing a default-constructed
`RecorderConfig` is therefore always behavior-neutral — no caller is forced to reason about the new
fields just to keep existing behavior.

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

### The foreground-service seam — `RecorderForegroundService` / `RecorderNotificationConfig`

An optional, **binder-only** public `android.app.Service` subclass that hosts a `Recorder` behind a
foreground service, so a recording keeps running while the host app is backgrounded. Using it is
opt-in: a consumer that never binds this service still gets the plain `Recorder` contract above with
no foreground-service behavior at all.

```kotlin
public final class RecorderForegroundService : Service() {
    public inner class RecorderBinder : Binder() {
        public fun attach(recorder: Recorder, notification: RecorderNotificationConfig)
        public fun detach()
    }
}
```

The binder exposes exactly two calls. `onBind` returns a `RecorderBinder`; there is no other public
entry point on the service.

| Call | Meaning |
|---|---|
| `attach(recorder, notification)` | begin managing `recorder` under this service: `startForeground()` is called with the host-supplied notification, then the service self-starts (so it is both *started* and *bound* — see the lifecycle contract below). |
| `detach()` | end the managed session: stop foreground, cancel the notification, and stop the service. |

**The service is bound rather than started from the host's own call site** — the host never calls
`Context.startService`/`startForegroundService` itself; `attach()` does that internally. The host
supplies **all** notification content through `RecorderNotificationConfig`; the service has no
built-in notification copy or icon of its own.

**Lifecycle contract (read this before adopting):** on `attach()`, the service enters the *started*
state in addition to being bound, so a recording **survives every client unbinding** — including an
Activity destroyed and recreated by a configuration change. Only `detach()` (or the recorder reaching
a terminal state on its own — see the host obligation below) ends the managed session. Unbinding a UI
client alone does **not** end a recording; teardown is explicit by design.

**The host detach obligation.** A host that calls `attach` and then abandons the recorder without ever
calling `start()` keeps the notification and the started state indefinitely — no library-side timeout
can safely resolve this (it would freeze a policy constant into an immutable tag and kill the
legitimate slow-start case). **A host that attaches MUST call `detach` if recording never starts**,
wired to its own lifecycle teardown. This is the only leak path this service does not close on its
own — it closes every other case by itself: a failed `start()`, any other transition into `Error`, a
recording that ends normally, and an external `stop()` on the `Recorder` all tear this service down
without host involvement.

**The threading contract.** `attach` and `detach` are **main-thread-only**, and the service is **not**
thread-safe against concurrent binder calls from arbitrary threads. A local binder does not marshal
calls across threads, so this is a stated contract the host must honor, not a framework guarantee the
service enforces on your behalf.

**The inherited-permission consequence.** Any consumer that depends on `:recorder` — even one that
never binds `RecorderForegroundService` — inherits the `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MICROPHONE` manifest permissions by manifest merge, because the service
declaration lives in this module's own manifest. This was accepted rather than split into a separate
artifact: a second `:recorder-service` module would double the published-module count and the
release-gate surface for two permission lines that Android already treats as ordinary
(non-dangerous, no runtime prompt) declarations.

`RecorderNotificationConfig(smallIconResId: Int, contentTitle: String, contentText: String? = null, channelId: String = DEFAULT_CHANNEL_ID, channelName: String = DEFAULT_CHANNEL_NAME, channelImportance: Int = NotificationManager.IMPORTANCE_LOW, notificationId: Int = DEFAULT_NOTIFICATION_ID, contentIntent: PendingIntent? = null)`
— a plain class, **not** a `data class` (`CLAUDE.md` invariant #2):

| Field | Default | Meaning |
|---|---|---|
| `smallIconResId` | *(required)* | must be non-zero — validated at construction. |
| `contentTitle` | *(required)* | must be non-blank — validated at construction. |
| `contentText` | `null` | optional notification body text. |
| `channelId` | `DEFAULT_CHANNEL_ID` (`"sttengine_recorder_recording"`) | must be non-blank — validated at construction. |
| `channelName` | `DEFAULT_CHANNEL_NAME` (`"Recording"`) | must be non-blank — validated at construction. |
| `channelImportance` | `NotificationManager.IMPORTANCE_LOW` | passed straight through to the notification channel. |
| `notificationId` | `DEFAULT_NOTIFICATION_ID` (`481210`) | must be non-zero — validated at construction. |
| `contentIntent` | `null` | if supplied, **must** be an immutable `PendingIntent` (`PendingIntent.FLAG_IMMUTABLE`) — validated at construction and rejected otherwise. |

Every validation failure above throws `IllegalArgumentException` at construction time, before
`attach()` is ever called — a host cannot bind a broken notification config and discover it only when
the platform later rejects it.

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

## Transcription

A fourth, independently publishable Gradle module (`:transcription`, JitPack artifact
`voice-engine-android-transcription`) — the dependency-light batch-protocol client only: `POST /jobs`
(submit) plus `GET /jobs/{id}` (poll). Extracted and hardened from the internal `:voicenotes`
implementation of the same Phase-7 server contract, with zero `:stt`/`:recorder`/`:voicenotes`/Room/
`WorkManager` coupling — a consumer of this module pulls in only the batch-protocol wire client, no
recording, no persistence, no background scheduling.

```kotlin
public fun createTranscriptionClient(serverUrl: String): TranscriptionClient
```

| Parameter | Meaning |
|---|---|
| `serverUrl` | the batch endpoint's base URL (e.g. `https://host.ts.net:8446`) — `POST {serverUrl}/jobs` / `GET {serverUrl}/jobs/{id}` are built from it directly. No tailnet default is baked in, mirroring `createVoiceNotes`'s "the host must supply it explicitly" contract. |

### `TranscriptionClient` — the contract

| Member | Type | Notes |
|---|---|---|
| `submitJob(file: File, mimeType: String, language: String = "en", vocabulary: List<String> = emptyList())` | `suspend fun` returning `SubmitOutcome` | `POST /jobs` — submit a recorded file for transcription. The abstract member; **unchanged** |
| `submitJob(file: File, mimeType: String, language: String = "en", vocabulary: List<String> = emptyList(), retainForCorpus: Boolean = false)` | `suspend fun` returning `SubmitOutcome` | the same upload, optionally opting the clip into the voice corpus. A new overload with an interface **default body**; see below |
| `getJobStatus(jobId: String)` | `suspend fun` returning `JobStatusOutcome` | `GET /jobs/{id}` — poll a previously submitted job |

`language` defaults to `"en"`, reproducing the previously published tag's behavior exactly when a
caller omits it. The server validates the value against its own allow-list and rejects anything else
with a typed `SubmitOutcome.Rejected` — the two codes supported at this tag are English (`"en"`) and
Spanish (`"es"`). An unrecognized code does **not** mint a new `TranscriptionErrorCode` constant; it
surfaces through the existing `Rejected` outcome, carrying the server's own taxonomy string in its
`message`. A consumer that wants to detect this case matches on the message rather than a code.

#### `retainForCorpus` — opting a clip into the voice corpus

`retainForCorpus` (CORR-09) asks the server to keep the clip as a corpus record on the server host.

- **The part.** It travels as one multipart text part named `retainForCorpus` with the value `true`,
  placed after `language` and `vocabulary` and **before the file part**. It is omitted entirely when
  the flag is `false`, so a default request carries the same parts, values and order as the previous
  release. A server without the corpus feature, or with retention not armed, ignores it.
- **The id.** On a `202` the returned `Accepted.jobId` is the corpus record id and is the `sessionId`
  that [`submitCorrection`](#corpus-corrections--submitcorrection) takes. There is no separate id.
- **No flag on `Accepted`.** `Accepted` carries no "retained" field: the server's `retained: true` on
  the `202` means retention is **armed, not committed**, and the job id is all a host needs.
- **Corrections after `Done`.** Submit a correction only after `getJobStatus` reports `Done`: the
  record is committed before the status flips.
- **A re-transcribe is a new job and a new record.** There is no `kind` parameter; the server infers
  the record kind.
- **An older or unarmed server** ignores the part. The job runs as usual and no record exists, so a
  later correction returns `UNKNOWN_SESSION`.
- **The host app owns consent** for keeping the clip; this library never decides to retain on its own.

`GET /jobs/{id}` may carry an optional terminal `retentionStatus` of `committed` or `failed` for jobs
that were armed. This client **tolerates** the key and does not surface it as a field. A retention
failure still reports `done` with the transcript, and a later correction for that id then returns
`UNKNOWN_SESSION`. Jobs that did not opt in return bodies byte-identical to before.

**Migration for `submitJob`:** there is **no break**. The existing four-parameter `submitJob` is
unchanged and still abstract, and `retainForCorpus` arrives as an additive overload with an interface
default body, so callers, implementers and host test fakes compile and link unchanged with no
source or binary break; the API dump shows only added lines. A call that omits the flag binds to the four-parameter member, and a call that names or passes
`retainForCorpus` binds to the new overload. For a custom `TranscriptionClient` that does not override
the new overload the default body behaves as follows: a flagless call and `retainForCorpus = false`
work unchanged and delegate to the four-parameter member; `retainForCorpus = true` returns
`SubmitOutcome.Rejected` with `UPLOAD_REJECTED` and uploads nothing (never a silent drop). To honour
the flag, override the five-parameter overload. Never implement the four-parameter member by calling
the five-parameter one without overriding it: the two would call each other forever.

### `SubmitOutcome`

The outcome of a `POST /jobs` attempt — a sealed interface with three cases:

| Case | Meaning |
|---|---|
| `Accepted(jobId: String)` | `202` — the server accepted the upload and assigned a job id |
| `Rejected(code: TranscriptionErrorCode, message: String)` | `413`/`400` — the server rejected the upload outright. A permanent, typed failure. |
| `Transient` | Any network failure or unexpected status (including `408`) — retry, do not mark permanent |

### `JobStatusOutcome`

The outcome of a `GET /jobs/{id}` poll — a sealed interface with five cases:

| Case | Meaning |
|---|---|
| `Done(transcript: String)` | `{status:"done", transcript}` — terminal success |
| `Failed(message: String)` | `{status:"failed", error}` — terminal, permanent failure |
| `NotFound` | `404` — the job reference was lost (TTL eviction or server restart), not "failed" |
| `Pending` | `{status:"processing"}` — still running; retry later |
| `Transient` | Network failure or unexpected status — retry, do not touch persisted state |

### `TranscriptionErrorCode`

```kotlin
@JvmInline public value class TranscriptionErrorCode(public val value: String)
```

A fresh, `:transcription`-owned error taxonomy — 2 companion constants:

| Constant | String |
|---|---|
| `UPLOAD_TOO_LARGE` | `upload-too-large` |
| `UPLOAD_REJECTED` | `upload-rejected` |

**Note (mirrors `NoteErrorCode`'s own doc note):** this carries the same wire strings as
`voicenotes.NoteErrorCode.UPLOAD_TOO_LARGE`/`.UPLOAD_REJECTED` but is **not the same type** — it is a
genuinely separate, freshly-declared type, and depending on `:transcription` creates no dependency
edge onto `:voicenotes` or vice versa.

---

## Corrections

A fifth, independently publishable Gradle module (`:corrections`, JitPack artifact
`voice-engine-android-corrections`) — the portable, zero-I/O correction/vocabulary-learning core:
mine candidate correction pairs from a recognized→corrected diff, rewrite committed final text
against an active ruleset, and build a bounded, frequency-gated active ruleset from a stored
correction corpus. Hand-ported line-for-line from `web/src/corrections.ts` (Phase 13, CORR-01/
CORR-02/CORR-04/CORR-05) for byte parity — no two independently-designed implementations in
different languages are guaranteed to agree on tie-breaking behavior, so this module follows the
SAME steps as the TypeScript twin rather than a fresh design; any change on one side must be
mirrored on the other.

```kotlin
public class CorrectionRevision(
    public val recognized: String,
    public val corrected: String,
    public val createdAt: String,
    public val audioUri: String? = null,
)

public class CorrectionPair(
    public val wrong: String,
    public val right: String,
)

public typealias CorrectionRuleset = List<CorrectionPair>

public object Corrections {
    public const val DEFAULT_PROMOTION_THRESHOLD: Int = 2
    public const val DEFAULT_RULESET_CAP: Int = 200

    public fun deriveCorrections(recognized: String, corrected: String): List<CorrectionPair>
    public fun apply(text: String, ruleset: CorrectionRuleset): String
    public fun buildRuleset(
        revisions: List<CorrectionRevision>,
        thresholdN: Int = DEFAULT_PROMOTION_THRESHOLD,
        maxRulesetSize: Int = DEFAULT_RULESET_CAP,
    ): CorrectionRuleset
}
```

None of `CorrectionRevision`/`CorrectionPair` is a `data class` (`CLAUDE.md` invariant #2) — the
same binary-compatibility discipline as every other model type in this document: a future optional
field must be addable without breaking every already-published consumer's resolved `copy()`/
constructor ABI. Since these are plain classes they have no `copy()`; both hand-write
`equals`/`hashCode`/`toString`.

### `CorrectionRevision`

An append-only correction/corpus revision — one immutable revision keyed to the same audio. A
correction is always a NEW revision, never a mutation of a prior one; this type exports no
`replace`/`update`/`copy`-like helper, and the absence of a mutation path is the append-only
guarantee's actual enforcement mechanism (mechanically backed by `:corrections:apiCheck`).

| Field | Meaning |
|---|---|
| `recognized` | What the engine originally recognized. |
| `corrected` | What the host/user corrected it to. |
| `createdAt` | ISO-8601 UTC timestamp of when this revision was recorded. |
| `audioUri` | An opaque, host-issued locator for the audio this revision was corrected against — never validated, parsed, or dereferenced by the engine (mirrors `RecordedFile.uri`'s opaque-locator framing). `null` when not supplied. |

The persistence contract a host's own datastore must follow to avoid silently destroying its own
correction history is in `../docs/CORPUS-INTEGRITY.md` — read it before wiring a `CorrectionRevision`
store, not after.

### `CorrectionPair` / `CorrectionRuleset`

`CorrectionPair(wrong, right)` is a single word/phrase substitution — a different granularity than
`CorrectionRevision`'s whole-utterance `recognized`/`corrected`. `CorrectionRuleset` is a plain
`List<CorrectionPair>` type alias, not its own class — an active ruleset is just an ordered list a
host builds once (via `buildRuleset`, below) and hands to `apply()` or `SttConfig.corrections`.

### `deriveCorrections(recognized, corrected)`

Mines candidate `CorrectionPair`s from a single recognized→corrected diff via a word-level LCS
alignment. Pure, synchronous, zero-I/O, and standalone — importable and usable without constructing
an `SttEngine`, e.g. over a batch of corrected voice-note transcripts.

### `Corrections.apply(text, ruleset)`

Rewrites `text` by replacing every whole-token/whole-phrase match of a ruleset pair's `wrong` text
with its `right` text. Matching is purely a literal, tokenized-array elementwise comparison — it
never constructs a `Regex` from host-supplied text. An empty ruleset is a fast-path no-op: `text` is
returned byte-for-byte unchanged. A non-empty ruleset NFC-normalizes the entire output, including
unmatched spans — a documented, deliberate side effect distinct from "correction," relevant only to a
host whose recognizer might ever emit non-NFC text.

### `buildRuleset(revisions, thresholdN?, maxRulesetSize?)`

Builds a bounded, frequency-gated, anti-list-filtered `CorrectionRuleset` from a host-supplied,
append-only `CorrectionRevision` corpus. Pure and synchronous — re-running this over the identical
`revisions` list always returns byte-identical output; there is no persisted counter or other
module-level mutable state.

**Note on shape:** unlike the TypeScript twin's `buildRuleset(revisions, options?)` (an optional
`BuildRulesetOptions { thresholdN?, maxRulesetSize? }` bag), the Kotlin signature exposes
`thresholdN`/`maxRulesetSize` as plain trailing-defaulted parameters directly on `buildRuleset`
itself — the actual committed Kotlin idiom, not a ported options object. Both platforms accept and
default the same two knobs; only the calling shape differs.

| Parameter | Default | Meaning |
|---|---|---|
| `revisions` | *(required)* | The host's own stored corpus, in list (chronological, append-only) order — never re-sorted, since list order is the one deterministic input order both platforms share. |
| `thresholdN` | `Corrections.DEFAULT_PROMOTION_THRESHOLD` (`2`) | Minimum observation count before a mined pair is promoted into the returned ruleset. |
| `maxRulesetSize` | `Corrections.DEFAULT_RULESET_CAP` (`200`) | Maximum returned ruleset size, enforced inside this function — never left to the host/config layer. Truncated in order of highest observed count first. |

A hand-enumerated, non-configurable list of common EN/ES function words (`a`, `the`, `el`, `la`, …)
is excluded from promotion regardless of how many times it was observed — a guard against a single
ambiguous, high-frequency mis-hearing silently becoming a standing rule.

See [`INTEGRATION.md`](INTEGRATION.md)'s "Adopt Corrections" step for the worked adoption snippet,
and [`SttConfig`](#sttconfig) above for how a built ruleset reaches a live session via
`SttConfig.corrections`.

---

## Contract parity with the TypeScript client

The Kotlin surface is a **translation**, not a redesign: every name, every state value and every
error string was copied across.

| TypeScript | Kotlin | Same? |
|---|---|---|
| `EngineState` union (5 strings) | `EngineState` enum + `wireName` (5) | **identical strings** |
| `FinalSegment { text, segmentId }` | `FinalSegment(text, segmentId)` | identical |
| `ReconnectInfo { attempt, max }` | `ReconnectInfo(attempt, max)` | identical, `attempt` 1-based on both |
| `SttWarning { code, active, message }` | `SttWarning(code, active, message)` | identical shape; emitted on the server backend (`backpressure`, as on the web client), never while on-device |
| `SttError { code, message }` | `SttError(code, message, platformDetail)` | **+1 field** — divergence 2 |
| `SttErrorCode` union (7 server + 4 client = 11) | `SttErrorCode.SHARED` (11) | **byte-identical** |
| — | `SttErrorCode.ANDROID_ONLY` (3) | **additive** — divergence 1 |
| `SttEventMap` (7 events) | `SttEvent` sealed interface (7 members) | same names, same payloads |
| `on(event, cb)` returning an unsubscribe handle | seven `Flow` / `StateFlow` properties + `events` | mechanism differs, event set identical |
| `SttEngineOptions { serverUrl?, language? }` | `SttConfig(language, prefer, serverUrl, corrections, vocabulary, serverTryTimeoutMs, retainForCorpus)` | `serverUrl` re-added (Phase 9, D-01) — `prefer`/`backend`/`serverTryTimeoutMs` are Android-only additive; `retainForCorpus` and `SttEngine.lastSessionId` exist on the Kotlin client only (the TypeScript client carries wire types for them, no corpus API); `sampleRate` stays **dropped, not stubbed** |
| engine requests microphone access itself | **host** holds `RECORD_AUDIO` | divergence 3 |
| `start(): Promise<void>` / `stop()` | `suspend fun start()` / `suspend fun stop()` | same settle semantics; on the server path Android's `stop()` waits up to ~2.5 s for the server's flush (see [`start()` and `stop()`](#start-and-stop)) |

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
- **A language-pack download progress stream.** `requestLanguagePackDownload` reports acceptance
  only. Progress, percentages and "still downloading" UI are the host's job; re-query
  `checkLanguageAvailability` to learn when a pack has arrived.
