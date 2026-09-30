# CLAUDE.md — `voice-engine-android` (the reusable Android dictation library)

You are working in a **reusable, host-agnostic Android library**: the on-device speech-to-text engine
that independent consumer apps import via **JitPack**. This module names **no app-specific
concepts** — it is pure, reusable mechanism.

- **Import root:** `io.github.ygaray.sttengine`
- **JitPack coordinate:** `com.github.Ygaray:voice-engine-android:<tag>`
- **Public mirror:** `github.com/Ygaray/voice-engine-android`
- **Worked reference:** the `:demo` module here — a reference host app, never published

**Read [`API.md`](API.md) for the public surface and [`INTEGRATION.md`](INTEGRATION.md) to wire a new
app.** What follows is the doctrine: the reasons behind the shape, and the invariants that must not
be broken.

## The contract is the durable asset; the backend is swappable

This library and the project's TypeScript client are two **backends behind one contract**. The same
five state strings, the same eleven error strings, the same seven events, the same
live-partial-then-immutable-final model. A host that renders one can render the other.

That is the whole point of the project, and it dictates the priority order for every change here:
**the contract outranks the implementation.** The recognizer strategy, the restart budget, the
capability ladder and the segmentation approach are all free to change — they are `internal`
precisely so they can. The public names, strings and semantics are not.

Corollary: when an implementation detail would be *convenient* on the public surface, the answer is
almost always no. Promoting an internal counter for a debug row buys two numbers and sells a
permanent binary-compatibility commitment on behalf of every future consumer. The `:demo` module
faced exactly that choice and read the engine's opt-in diagnostic trace instead, which is why the
frozen surface is as small as it is.

## The invariants — do not break these

### 1. Error strings are a cross-language contract. A rename is a breaking event in three languages.

The kebab-case error strings are shared across the Python server, the TypeScript client and this
library. Changing one is not a refactor; it is a simultaneous breaking change in three codebases and
in every host that branches on a code.

- The **eleven shared codes are frozen and byte-identical** across all three. `SttErrorCode.SHARED`
  is that set.
- The **three Android-only codes are additive and must NEVER be added to the TypeScript union.** That
  union is closed; widening it breaks exhaustive `switch` in every existing web host. A documented
  Kotlin *superset* costs nobody anything; widening a closed union is the actual breaking event.
- A parity guard enforces all of this mechanically — a Kotlin test that runs on the release path plus
  a three-way script in the development mono-repo. Both print **both** symmetric differences on
  failure so a drift diagnoses itself. **Neither may be allowed to degrade into a check that always
  skips or always passes.** A guard that silently stops guarding is worse than no guard, because it
  converts an unverified surface into a documented-as-verified one. If you touch either, prove it can
  still fail before you trust it green.

### 2. No public model type may be a `data class`.

`FinalSegment`, `SttError`, `SttConfig`, `ReconnectInfo` and `SttWarning` are plain classes with
hand-written `equals` / `hashCode` / `toString`. This is a binary-compatibility rule, not a style
preference.

The contract explicitly plans to add optional fields in v2 (timestamps on `FinalSegment`, most
concretely). Adding a parameter to a `data class` changes its **generated `copy()` signature** and
its **synthetic constructor**, both of which are part of the published ABI — so every consumer that
resolved the previous artifact breaks at link time the moment that field lands. A plain class with a
defaulted parameter adds the field additively. The rule is what makes the v2 promise keepable, so
"tidying" any of these into a `data class` silently cancels it.

### 3. `SttErrorCode` is a value class, not an enum, and this is required.

`RecognitionListener.onError`'s own documentation requires callers to handle integers outside its
documented constant set. The backend must therefore be able to produce a code it did not anticipate.
An enum would make every future code a **source-breaking** `when` for every consumer; the value class
keeps the set open, and an unrecognized recognizer integer lands as `internal-error` carrying the raw
value in `platformDetail`.

`EngineState` *is* an enum, and that is not an inconsistency: that set is closed by design and a host
genuinely wants the compiler to point at a new state.

### 4. The API dump is regenerated **deliberately**, never to make a failing check pass.

`stt/api/stt.api` is the golden dump of the release compilation, diffed by `:stt:apiCheck`, which is
wired into `:stt:check`. When that check fails it is telling you the **public surface changed**.

- If the change was intended, run `./gradlew :stt:apiDump`, **read the diff**, and commit it as part
  of the same change that caused it, with the reason in the commit message.
- If the change was not intended, the check just caught a bug — most often a type missing an
  `internal` modifier. Fix the code, not the dump.

Running `apiDump` reflexively because a build is red is how a frozen surface silently unfreezes. The
dump is generated from the **release** compilation specifically because that is the variant that gets
published; a dump taken from another variant would freeze the wrong thing.

Do **not** migrate to Kotlin's built-in `abiValidation` extension. It does not apply to Android
library projects, and applied here it would configure nothing while the check still reported green.
The standalone `binary-compatibility-validator` is in maintenance mode; that is fine for a tool whose
whole job is a byte-diff of a text file.

### 5. This directory must remain a **self-contained Gradle root**.

The public mirror is produced by promoting this directory to a repository root
(`git subtree split --prefix=android`). Everything the mirror needs — the Gradle wrapper,
`settings.gradle.kts`, the root build script, `gradle/libs.versions.toml`, `jitpack.yml`, and these
four documents — therefore lives **inside** this directory. Nothing the mirror needs may live above
it: a file at the development repository's root does not survive the split, and the mirror is
unbuildable in a way that is only discovered after a tag is immutable.

Related invariants that follow from it:

- `local.properties` is **gitignored from the first commit**. JitPack sets `ANDROID_HOME` itself, and
  committing a local SDK path to a public mirror leaks a filesystem layout for no benefit.
- `jitpack.yml` names the **published library modules only** (now `:stt` AND `:recorder` — plan
  08-05's REC-05 rework extracted the Recorder into its own independently published module). A
  module named there that `settings.gradle.kts` does not include — or the reverse — is an
  unrecoverable failure found after the tag is immutable.
- The reference host app is **outside the published module set**: no `maven-publish`, no
  `explicitApi()`, no binary-compatibility validator. Freezing a surface is the library's job.
- These documents become **world-readable** when the mirror is pushed. They must contain no private
  hostname, no server details, and no path from the development machine.

## Shipping is human-gated

A change here is **not live in any consumer** until: **new tag → JitPack builds it → the consumer
bumps its coordinate** → sync → rebuild → **re-verify on a real device**. That tag/bump step is
human-gated: make the fix and the tests autonomously, then surface the tag and the consumer bump for
confirmation. Do not tag, and do not bump a consumer, without the owner's go-ahead.

Tags are **immutable**. Consumers pin a tag or a commit SHA; never `main-SNAPSHOT`, never a moving
branch ref. JitPack builds from the public mirror, not from any local clone, so local edits reach
nobody until they are pushed, split, tagged and repinned.

## Platform floor, and what is deferred

`minSdk 33` is chosen for a specific capability pair — `checkRecognitionSupport` and
`triggerModelDownload`, both API 33 — which together turn a missing offline language pack from a
mysterious failed session into a diagnosis with a recovery attached. See `README.md`.

**Do not raise the floor for the deferred multilingual path, and do not claim it is reachable at the
current one.** Verified against the local SDK API index: `EXTRA_ENABLE_LANGUAGE_SWITCH` and
`EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES` arrived at **API 34**, and
`EXTRA_LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS` and
`EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES` at **API 35** — not 33, as earlier project notes stated.
In-session language switching is therefore out of reach at this module's floor. That is exactly why
native `"auto"` bilingual detection (implemented for v3.1, NBIL-01..10) is runtime-gated to
`SDK_INT >= 34` rather than assumed available, falling back to the configured default language
below 34 instead of raising the floor; `SttConfig.language` per engine instance works at 33
unconditionally.

## Toolchain

- Gradle **8.13** / AGP **8.13.0** / Kotlin **2.3.20** / JDK **17**, `minSdk 33`, `compileSdk 36`.
  Android **library** module — no `applicationId`, no `versionName`, no Compose in `:stt`.
- Build: `./gradlew :stt:assembleRelease`
- Unit tests: `./gradlew :stt:testDebugUnitTest` (JUnit4 + coroutines-test + Robolectric for the thin
  platform adapter). The majority of the suite runs on the host behind an injected recognizer seam —
  no device, no emulator.
- Freeze check: `./gradlew :stt:apiCheck` (also runs as part of `:stt:check`)
- Full local gate: `./gradlew :stt:check :demo:assembleDebug`
- Publish locally, exactly what JitPack runs (see `jitpack.yml`):
  `./gradlew :stt:publishReleasePublicationToMavenLocal`
- `:stt` ships **no UI**, **no `Application`**, and **no Activity**. It is imported and wired by each
  consumer, never run on its own. `:demo` is the runnable reference and is never published.
