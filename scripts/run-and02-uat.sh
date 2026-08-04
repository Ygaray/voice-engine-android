#!/usr/bin/env bash
# run-and02-uat.sh — produce a SCORED AND-02 verdict from a real run on a real device.
#
# ─── WHAT THIS PROVES ────────────────────────────────────────────────────────────────────────────
#
# AND-02: "dictation continues seamlessly across forced restarts with no duplicated or dropped text,
# verified by >=2 min continuous dictation." That is not a thing a person can reliably eyeball across
# a two-minute transcript, so this script does not ask anyone to look at a screen. It drives the demo
# over adb with the engine's opt-in JSONL session trace enabled, pulls the trace, and feeds it to
# SessionLogAnalyzer, whose nine assertions are the verdict. The script's exit status IS the
# analyzer's exit status: 0 means AND-02 passed, non-zero means it did not, and the printed report
# names which of the nine failed and why.
#
# The analyzer is deliberately STIMULUS-AGNOSTIC — six of its nine assertions are structural
# invariants that hold whether a file, a speaker or a human produced the speech. So the stimulus
# chain below chooses what to TARGET FIRST, never what the verdict depends on.
#
# ─── THE TWO PRECONDITIONS THAT MAKE A CORRECT BUILD LOOK BROKEN ─────────────────────────────────
#
# Both are encoded as steps, both before the run, and neither has a bypass flag.
#
#   * LANGUAGE PACK, WHILE THE NETWORK IS UP (D-26). Downloading an on-device recognition pack needs
#     a network. Running an offline-shaped session before the pack has landed produces a
#     `no-offline-model` error that reads exactly like a code bug and sends a debugging session in
#     entirely the wrong direction. So the pack is confirmed FIRST, online, and the run does not
#     start until it is there.
#
#   * POWER MANAGER (test-android.md). Samsung's Freecess can freeze the app's coroutines during a
#     multi-minute foreground run. The symptom — text simply stops arriving — is indistinguishable
#     from the dropped-text failure AND-02 exists to detect. The demo package is therefore added to
#     the device-idle whitelist before any run.
#
# ─── STATED CONSTRAINT: NO RADIO TOGGLING, EVER ──────────────────────────────────────────────────
#
# This script contains no command that puts the device into airplane mode or otherwise disables its
# radios. Doing that over wireless adb severs the transport IRREVERSIBLY and needs physical access to
# the phone to recover (devices/common.md, D-25). The offline proof is a HUMAN Gate-2 step performed
# by Yahir at milestone close; it is not, and must never become, an agentic one.
#
# ─── STIMULUS, AND ITS FALLBACK CHAIN (05-RUNG-VERDICT.md) ───────────────────────────────────────
#
#   1. FILE-FED via the demo's AUDIO_SOURCE extra — the probe's chosen path, measured at precision
#      0.994 / recall 0.994 over a 130 s scripted passage, and the only DETERMINISTIC one: the same
#      bytes, in the same order, at the same wall-clock pace, every time. The stimulus is pushed into
#      the demo's OWN external files directory (no storage permission, unlike the shared-storage
#      dance the acoustic path needs) and named on the START broadcast. `auto` still DETECTS the
#      extra on disk rather than assuming it — the disk wins.
#
#      A file-fed run is refused rather than downgraded if anything about it cannot be verified. The
#      hazard is NOT the microphone; it is rung (c) never being selected: on rungs (b)/(a) the
#      app-owned pipe is never opened and the session records the ROOM while every label says
#      file-fed. So step 7 requires the trace to name `RawAudioSegmented` AND the demo's sidecar to
#      report a non-zero open count. A run that cannot prove it was file-fed is not scored, because
#      an acoustic run mislabelled file-fed is worse than no run at all — it is evidence-shaped.
#   2. ACOUSTIC speaker-to-microphone loopback — measured usable at precision 0.968 / recall 0.559.
#      The recall shortfall is the restart deaf window clipping utterance onsets, not an acoustic
#      problem. Fragile to set up (plan 07 needed four attempts and a file-manager grant), so an
#      unconfirmed playback degrades to the floor below with a loud message rather than aborting.
#   3. HUMAN-SPOKEN — always available, and the guaranteed floor. The script prints what to say and
#      for how long, then holds.
#
# ─── ORDERING THAT MATTERS ───────────────────────────────────────────────────────────────────────
#
# The trace is pulled at step 7, BEFORE anything that could run an instrumented test. A
# `connectedAndroidTest` run wipes application data and would take the evidence with it. This script
# runs no instrumented test at all; step 8 is a JVM unit-test invocation.
#
# ─── USAGE (all inputs are environment variables; nothing is hardcoded) ──────────────────────────
#
#   AND02_SERIAL=100.113.42.22:1496 AND02_DURATION_S=130 ./scripts/run-and02-uat.sh
#
# Optional:
#   AND02_LANGUAGE=en-US     BCP-47 tag the session requests (default en-US)
#   AND02_STIMULUS=auto      auto | file-fed | acoustic | human
#   AND02_LANGUAGE_TIMEOUT_S=300   how long to wait for a language pack to land
#   AND02_EVIDENCE_DIR=<dir> where the pulled trace lands (default: the phase directory)
#   AND02_RUNG_CEILING=<rung>  cap the capability ladder: RawAudioSegmented | SilenceSegmented |
#                              RestartingSingleSession. Unset = the device's own answer.
#
# ─── WHY A RUNG CEILING EXISTS, AND WHY IT DOES NOT WEAKEN THE VERDICT ───────────────────────────
#
# Assertion 2 wants at least three restarts, and on this device a normal run produces NONE. That is
# not a harness failure — it is a measured property of the shipped ladder. Rung (c) sets
# EXTRA_SEGMENTED_SESSION to EXTRA_AUDIO_SOURCE, so the session ends when the APP-OWNED SOURCE is
# exhausted, and an app-owned source (a microphone, or the paced file feed) never exhausts. Nothing
# degrades off it either: both of rung (c)'s triggers are exempt for a session the host stopped.
# Measured here: a 140 s file-fed run and a 75 s quiet-microphone run, zero restarts each, no errors,
# no degradation. Rung (c) is ABSORBING on a device that supports it.
#
# So AND-02's two halves cannot be measured by one run, and the ceiling is what makes the second one
# possible rather than what makes it pass:
#
#   * FILE-FED on rung (c) — deterministic, perfect recall, ZERO restarts. Carries the text-integrity
#     claim. Assertion 2 fails on it, and that failure is a statement about the STIMULUS.
#   * CEILING=RestartingSingleSession on rung (a) — the classic restart loop, one restart per final,
#     microphone-only (rung (a) does not accept an app-owned source, so it cannot be file-fed).
#     Carries the restart-boundary claim.
#
# The ceiling changes WHICH RUNG runs. It does not touch the analyzer, the assertions or the exit
# status, and step 7c refuses to score a capped run whose trace does not name the requested rung on
# EVERY session — a cap is a request, the trace is the fact.
#
# The serial is an environment variable because the tester's wireless-adb port ROTATES; a hardcoded
# one goes stale and the next person edits the script instead of the invocation. The script refuses
# to touch any device other than the one supplied and prints the resolved model before doing
# anything, so a wrong-phone run — the personal daily driver is the same handset model — is visible
# in the first three lines of output.
#
# There is no bypass flag on any gate, on purpose.

set -euo pipefail

# ─── inputs ──────────────────────────────────────────────────────────────────────────────────────

# NOTE: no apostrophes inside these two messages. Bash tracks quotes INSIDE ${...} even within a
# double-quoted word, so a lone ' would swallow the closing brace and the whole file after it.
: "${AND02_SERIAL:?run-and02: ABORT [env] set AND02_SERIAL to the target device (e.g. 100.113.42.22:1496) — the wireless-adb port on the tester rotates, so it is never hardcoded}"
: "${AND02_DURATION_S:?run-and02: ABORT [env] set AND02_DURATION_S to the hold duration in seconds (use 130 — it clears the 120 s assertion with margin)}"

SERIAL="${AND02_SERIAL}"
DURATION_S="${AND02_DURATION_S}"
LANGUAGE="${AND02_LANGUAGE:-en-US}"
STIMULUS="${AND02_STIMULUS:-auto}"
LANGUAGE_TIMEOUT_S="${AND02_LANGUAGE_TIMEOUT_S:-300}"
RUNG_CEILING="${AND02_RUNG_CEILING:-}"

MIN_DURATION_S=130
PROBE_HOLD_S=8
LANGUAGE_POLL_S=20

# ─── paths, resolved from this script rather than from the caller's working directory ────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${ANDROID_DIR}/.." && pwd)"
STIMULUS_WAV="${ANDROID_DIR}/stt/src/androidTest/assets/probe-stimulus-16k-mono.wav"
DEMO_DEBUG_DIR="${ANDROID_DIR}/demo/src/debug"

# `android/` is subtree-split into a mirror whose root is this directory, where `.planning/` does not
# exist. A pulled trace carries recognized speech, so it belongs OUTSIDE the split prefix (T-05-04)
# whenever there is an outside to put it in.
#
# AND02_EVIDENCE_DIR overrides the destination. It exists because a trace is the one output of this
# script that a caller may need to place deliberately — an agent running in a git worktree must not
# drop untracked files into the planning tree, and a smoke run should not leave artifacts among real
# evidence. It changes only WHERE the trace lands: every gate, the analyzer and the exit status are
# untouched, so it cannot be used to dodge a verdict.
EVIDENCE_DIR="${AND02_EVIDENCE_DIR:-}"
if [ -z "${EVIDENCE_DIR}" ]; then
  EVIDENCE_DIR="${REPO_ROOT}/.planning/phases/05-android-library"
  if [ ! -d "${EVIDENCE_DIR}" ]; then
    EVIDENCE_DIR="${ANDROID_DIR}/build/and02"
  fi
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

# ─── THE CROSS-PLAN BROADCAST INTERFACE ──────────────────────────────────────────────────────────
#
# Copied verbatim out of DemoControlReceiver.kt's companion object and the debug manifest, which are
# the source of truth. Step 0 re-checks every one of them against that source at run time, and this
# plan's own automated verify cross-greps them at authoring time, so a rename in the demo fails here
# rather than surfacing later as an unexplained AND-02 failure.

PKG="io.github.ygaray.sttengine.demo"
RECEIVER_CLASS="io.github.ygaray.sttengine.demo.debug.DemoControlReceiver"
ACTION_START="io.github.ygaray.sttengine.demo.debug.START"
ACTION_STOP="io.github.ygaray.sttengine.demo.debug.STOP"
ACTION_DUMP="io.github.ygaray.sttengine.demo.debug.DUMP_DIAGNOSTICS"
EXTRA_SESSION_LOG="io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG"
EXTRA_LANGUAGE="io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE"
EXTRA_AUDIO_SOURCE="io.github.ygaray.sttengine.demo.debug.extra.AUDIO_SOURCE"
EXTRA_RUNG_CEILING="io.github.ygaray.sttengine.demo.debug.extra.RUNG_CEILING"

RECEIVER="${PKG}/${RECEIVER_CLASS}"
REMOTE_FILES_DIR="/sdcard/Android/data/${PKG}/files"
DEMO_APK="${ANDROID_DIR}/demo/build/outputs/apk/debug/demo-debug.apk"

# The stimulus goes in the demo's OWN external files directory: adb can write there, the app can
# read it with no permission at all, and it is not in MediaStore — so unlike the acoustic path there
# is no file-manager onboarding and no all-files grant to negotiate.
REMOTE_STIMULUS="${REMOTE_FILES_DIR}/and02-stimulus.wav"

# 16 kHz mono 16-bit PCM is 32 000 bytes per second. Used only to size the hold; the DEVICE does the
# real format validation and refuses anything that is not exactly this shape.
WAV_BYTES_PER_SECOND=32000
WAV_HEADER_BYTES=44

# Slack past the end of the passage. The armed feed serves paced silence once the passage runs out
# (ending the stream instead would restart the session, whose fresh source is also exhausted — a
# restart storm in the final seconds of every run), so overshooting is cheap and undershooting
# silently truncates the stimulus.
FILE_FED_TAIL_S=8

# ─── helpers ─────────────────────────────────────────────────────────────────────────────────────

# Every device-directed command goes through here, so "-s <the supplied serial>" is structural rather
# than a discipline repeated at thirty call sites. There is no bare `adb shell` anywhere below.
adb_dev() { adb -s "${SERIAL}" "$@"; }

say() { echo "run-and02: $*"; }
abort() { echo "run-and02: ABORT [$1] ${2}" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || abort "env" "\`$1\` is not on PATH"
}

# ─── STEP 0 — the broadcast names still match the demo on disk ───────────────────────────────────

step_0_verify_interface() {
  say "step 0: cross-checking the broadcast interface against ${DEMO_DEBUG_DIR}"
  [ -d "${DEMO_DEBUG_DIR}" ] ||
    abort "step-0/actions" "${DEMO_DEBUG_DIR} does not exist — the debug control surface is plan 07's and this script cannot drive the demo without it"

  local name
  for name in "${RECEIVER_CLASS}" "${ACTION_START}" "${ACTION_STOP}" "${ACTION_DUMP}" \
    "${EXTRA_SESSION_LOG}" "${EXTRA_LANGUAGE}" "${EXTRA_AUDIO_SOURCE}" "${EXTRA_RUNG_CEILING}"; do
    grep -qrF -- "${name}" "${DEMO_DEBUG_DIR}" ||
      abort "step-0/actions" "\`${name}\` is not present in ${DEMO_DEBUG_DIR} — the demo renamed it; the DISK wins, so update this script from the receiver source, never the other way round"
  done
  say "step 0: OK — eight interface strings all present in the debug source set"

  case "${RUNG_CEILING}" in
    "" | RawAudioSegmented | SilenceSegmented | RestartingSingleSession) ;;
    *) abort "step-0/actions" "AND02_RUNG_CEILING=${RUNG_CEILING} is not a rung; expected RawAudioSegmented, SilenceSegmented or RestartingSingleSession" ;;
  esac
}

# ─── STEP 1 — reconnect and confirm ──────────────────────────────────────────────────────────────

step_1_device() {
  require_cmd adb
  say "step 1: resolving ${SERIAL}"

  local state
  state="$(adb devices | awk -v s="${SERIAL}" '$1 == s { print $2 }' | head -n 1)"

  if [ "${state}" != "device" ]; then
    # The tester's wireless-adb port rotates and a stale entry is the usual cause, so re-arm the ONE
    # supplied serial. Never `kill-server`, never `tcpip`: the host watchdog owns that transport and
    # bouncing it would take every other agent's device session down with it.
    say "step 1: ${SERIAL} is not in state device (saw \"${state:-absent}\") — reconnecting"
    adb disconnect "${SERIAL}" >/dev/null 2>&1 || true
    adb connect "${SERIAL}" >/dev/null 2>&1 || true
    sleep 2
    state="$(adb devices | awk -v s="${SERIAL}" '$1 == s { print $2 }' | head -n 1)"
  fi

  [ "${state}" = "device" ] ||
    abort "step-1/device" "${SERIAL} is not reachable (state \"${state:-absent}\"). Check the tailnet and the current wireless-debugging port; do NOT substitute another device"

  local model product
  model="$(adb_dev shell getprop ro.product.model | tr -d '\r')"
  product="$(adb_dev shell getprop ro.serialno | tr -d '\r')"
  say "step 1: OK — ${SERIAL} is ${model} (${product})"
  say "step 1: READ THIS LINE. Both phones on this tailnet are S22 Ultras; only the TESTER may be driven."
}

# ─── STEP 2 — see the header. There is nothing to execute here. ──────────────────────────────────
#
# The no-radio-toggling constraint is a property of what this file does NOT contain, and it is
# checked by a grep over the script's executable lines rather than by a runtime step.

# ─── STEP 3 — the language pack, while the network is up ─────────────────────────────────────────

step_3_language() {
  say "step 3: confirming the ${LANGUAGE} recognition pack is installed"

  # A download needs a network, and adb-over-tailnet does not imply one.
  if ! adb_dev shell dumpsys connectivity 2>/dev/null | grep -q "VALIDATED"; then
    say "step 3: WARNING — could not confirm a validated network. A pack download will not succeed offline."
  fi

  # There is no adb command that reports installed recognition packs, and the library deliberately
  # does not call triggerModelDownload. So the check asks the ONE authority that matters: the
  # engine's own preflight, through a short real session whose trace we read back. Same question the
  # real run will ask, same code path, same answer.
  local verdict
  verdict="$(language_probe)"

  if [ "${verdict}" = "ok" ]; then
    say "step 3: OK — the preflight reached listening, so ${LANGUAGE} is installed"
    return
  fi

  if [ "${verdict}" = "missing:unsupported-language" ]; then
    abort "step-3/language" "the recognizer reports ${LANGUAGE} as unsupported on this device — no download will help. Pick a language the device supports (AND02_LANGUAGE)"
  fi

  if [ "${verdict}" = "unknown" ]; then
    abort "step-3/language" "could not read a preflight verdict for ${LANGUAGE} — no trace came back from the probe session. Check that the demo is installed and the screen is unlocked"
  fi

  say "step 3: ${LANGUAGE} is supported but not installed. Opening the on-device voice-input settings."
  say "step 3: DOWNLOAD THE ${LANGUAGE} OFFLINE PACK NOW — this script will wait up to ${LANGUAGE_TIMEOUT_S}s and re-probe."
  adb_dev shell am start -a android.settings.VOICE_INPUT_SETTINGS >/dev/null 2>&1 || true

  local waited=0
  while [ "${waited}" -lt "${LANGUAGE_TIMEOUT_S}" ]; do
    sleep "${LANGUAGE_POLL_S}"
    waited=$((waited + LANGUAGE_POLL_S))
    verdict="$(language_probe)"
    say "step 3: re-probed after ${waited}s — ${verdict}"
    if [ "${verdict}" = "ok" ]; then
      say "step 3: OK — the pack landed"
      return
    fi
  done

  abort "step-3/language" "${LANGUAGE} did not become available within ${LANGUAGE_TIMEOUT_S}s. Running now would produce a no-offline-model error that reads exactly like a code bug (D-26)"
}

# Drives one short real session and reports what the engine's preflight decided.
language_probe() {
  clear_remote_logs
  start_session >/dev/null 2>&1 || true
  sleep "${PROBE_HOLD_S}"
  stop_session >/dev/null 2>&1 || true
  sleep 1

  local remote local_copy
  remote="$(newest_remote_log)"
  if [ -z "${remote}" ]; then echo "unknown"; return; fi
  local_copy="${WORK_DIR}/language-probe.jsonl"
  if ! adb_dev pull "${remote}" "${local_copy}" >/dev/null 2>&1; then echo "unknown"; return; fi

  if grep -q '"code":"no-offline-model"' "${local_copy}"; then
    echo "missing:no-offline-model"
  elif grep -q '"code":"unsupported-language"' "${local_copy}"; then
    echo "missing:unsupported-language"
  elif grep -q '"value":"listening"' "${local_copy}"; then
    echo "ok"
  else
    echo "unknown"
  fi
}

# ─── STEP 4 — the power manager ──────────────────────────────────────────────────────────────────

step_4_power() {
  say "step 4: whitelisting ${PKG} against battery optimisation"
  adb_dev shell dumpsys deviceidle whitelist "+${PKG}" >/dev/null 2>&1 ||
    abort "step-4/power" "could not add ${PKG} to the device-idle whitelist"

  adb_dev shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "${PKG}" ||
    abort "step-4/power" "${PKG} is not in the device-idle whitelist after adding it. A frozen coroutine mid-run is indistinguishable from dropped text"
  say "step 4: OK"
}

# ─── STEP 5 — a fresh install, so a stale APK cannot hide a fix ──────────────────────────────────

step_5_install() {
  say "step 5: reinstalling the debug demo"
  [ -f "${DEMO_APK}" ] ||
    abort "step-5/install" "no debug APK at ${DEMO_APK} — build it first: (cd ${ANDROID_DIR} && ./gradlew :demo:assembleDebug)"

  adb_dev install -r -g "${DEMO_APK}" >/dev/null ||
    abort "step-5/install" "installing ${DEMO_APK} failed"
  adb_dev shell pm grant "${PKG}" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true

  adb_dev shell dumpsys package "${PKG}" 2>/dev/null | grep -q "android.permission.RECORD_AUDIO: granted=true" ||
    abort "step-5/install" "RECORD_AUDIO is not granted to ${PKG}; the run would fail preflight with mic-denied"
  say "step 5: OK — installed and RECORD_AUDIO granted"
}

# ─── STEP 6 — the run ────────────────────────────────────────────────────────────────────────────

step_6_run() {
  [ "${DURATION_S}" -ge "${MIN_DURATION_S}" ] 2>/dev/null ||
    abort "step-6/stimulus" "AND02_DURATION_S=${DURATION_S} is below ${MIN_DURATION_S}s. Assertion 1 needs 120000 ms measured from the first listening state, and the deaf windows eat into that; a shorter run cannot pass. There is no bypass flag"

  CHOSEN_STIMULUS="$(resolve_stimulus)"

  # THE SCORED RUN STARTS FROM A FRESH PROCESS. Measured, not precautionary: step 3's language probe
  # is a real session on a real engine, and the demo only rebuilds its engine when the language or
  # the diagnostic flag CHANGES — which they do not between the probe and the run. So without this
  # the scored run inherits the probe's engine, and with it the probe's segment counter and its
  # timestamp origin. A file-fed run measured exactly that: 14 finals carrying segment ids 1..14
  # instead of 0..13, failing assertion 3 fourteen times over for a reason that had nothing to do
  # with the engine's behaviour under restarts.
  #
  # A fresh process is also what a real user session is, so this makes the run more representative
  # rather than less.
  say "step 6: force-stopping ${PKG} so the scored run starts from a fresh engine"
  adb_dev shell am force-stop "${PKG}" >/dev/null 2>&1 || true
  sleep 2

  # File-fed arms the audio source ON the START broadcast, so its setup has to happen BEFORE the
  # session exists — unlike acoustic playback, which is delivered into an already-running session.
  if [ "${CHOSEN_STIMULUS}" = "file-fed" ]; then
    prepare_file_fed
  fi

  say "step 6: stimulus = ${CHOSEN_STIMULUS}, holding for ${DURATION_S}s"

  [ -n "${RUNG_CEILING}" ] && say "step 6: requesting rung ceiling ${RUNG_CEILING}"

  clear_remote_logs
  if [ "${CHOSEN_STIMULUS}" = "file-fed" ]; then
    start_session "${REMOTE_STIMULUS}" "${RUNG_CEILING}" ||
      abort "step-6/stimulus" "could not start the file-fed session; is the screen unlocked?"
  else
    start_session "" "${RUNG_CEILING}" ||
      abort "step-6/stimulus" "could not start the session; is the screen unlocked?"
  fi

  case "${CHOSEN_STIMULUS}" in
    file-fed)
      # Nothing to deliver: the engine is reading the pushed WAV through rung (c)'s own pipe, paced
      # to wall clock by the library's debug source set. Step 7 PROVES that actually happened rather
      # than assuming it, because a run that silently fell to rung (b) would look identical here.
      say "step 6: file-fed session armed with ${REMOTE_STIMULUS}"
      ;;
    acoustic) deliver_acoustic ;;
    human)
      say "step 6: ─────────────────────────────────────────────────────────────────────"
      say "step 6: SPEAK NOW, CONTINUOUSLY, FOR ${DURATION_S} SECONDS."
      say "step 6: Pause briefly between sentences — the pauses are what force the segment"
      say "step 6: boundaries AND-02 is about. Do not stop early; the run needs 120 s of speech."
      say "step 6: ─────────────────────────────────────────────────────────────────────"
      ;;
  esac

  sleep "${DURATION_S}"

  # A diagnostic snapshot while the session is still live — counters and identifiers only, no
  # transcript text. Useful when the verdict is a failure and the trace alone is ambiguous.
  adb_dev shell am broadcast -n "${RECEIVER}" -a "${ACTION_DUMP}" >/dev/null 2>&1 || true
  say "step 6: OK — held ${DURATION_S}s"
}

resolve_stimulus() {
  case "${STIMULUS}" in
    file-fed | acoustic | human)
      echo "${STIMULUS}"
      return
      ;;
    auto) ;;
    *) abort "step-6/stimulus" "AND02_STIMULUS=${STIMULUS} is not one of auto|file-fed|acoustic|human" ;;
  esac

  # The rung verdict's first choice needs a demo-side switch selecting a file as the audio source.
  # Detect it on disk rather than assuming either answer; the disk wins. Both halves are required —
  # the switch is useless without a passage to feed through it.
  if grep -qrE 'extra\.(AUDIO_SOURCE|STIMULUS_FILE|FEED_FILE)' "${DEMO_DEBUG_DIR}" 2>/dev/null &&
    [ -f "${STIMULUS_WAV}" ]; then
    echo "file-fed"
  elif [ -f "${STIMULUS_WAV}" ]; then
    echo "acoustic"
  else
    echo "human"
  fi
}

# ─── file-fed setup ──────────────────────────────────────────────────────────────────────────────

CHOSEN_STIMULUS=""
PUSHED_STIMULUS=0

prepare_file_fed() {
  [ -f "${STIMULUS_WAV}" ] ||
    abort "step-6/stimulus" "file-fed needs a passage but ${STIMULUS_WAV} does not exist. Use AND02_STIMULUS=acoustic or =human"

  say "step 6: pushing ${STIMULUS_WAV} to ${REMOTE_STIMULUS}"
  # The app's own external files directory exists once the app has run at least once; step 5's
  # install plus step 3's probe session guarantee that by now.
  adb_dev shell mkdir -p "${REMOTE_FILES_DIR}" >/dev/null 2>&1 || true
  adb_dev push "${STIMULUS_WAV}" "${REMOTE_STIMULUS}" >/dev/null 2>&1 ||
    abort "step-6/stimulus" "could not push ${STIMULUS_WAV} to ${REMOTE_STIMULUS}"
  PUSHED_STIMULUS=1

  # A push that silently landed short would feed a truncated passage and score the result.
  local local_bytes remote_bytes
  local_bytes="$(stat -c %s "${STIMULUS_WAV}")"
  remote_bytes="$(adb_dev shell stat -c %s "${REMOTE_STIMULUS}" 2>/dev/null | tr -d '\r')"
  [ "${local_bytes}" = "${remote_bytes}" ] ||
    abort "step-6/stimulus" "the pushed stimulus is ${remote_bytes:-absent} bytes on the device but ${local_bytes} locally; a truncated passage would be scored as dropped text"

  # The hold has to outlast the passage, because the passage IS the stimulus: stopping early does not
  # produce a shorter run, it produces a DIFFERENT one. Derived from the file rather than trusted to
  # a hardcoded default that drifts the moment somebody swaps the WAV.
  local passage_s needed_s
  passage_s=$(((local_bytes - WAV_HEADER_BYTES) / WAV_BYTES_PER_SECOND))
  needed_s=$((passage_s + FILE_FED_TAIL_S))
  say "step 6: the passage is ~${passage_s}s"
  if [ "${DURATION_S}" -lt "${needed_s}" ]; then
    say "step 6: extending the hold ${DURATION_S}s -> ${needed_s}s so the whole passage plays out"
    DURATION_S="${needed_s}"
  fi
}

REMOTE_WAV="/sdcard/Download/and02-stimulus.wav"
PUSHED_WAV=0

deliver_acoustic() {
  say "step 6: pushing the scripted stimulus and playing it through the speaker"
  adb_dev push "${STIMULUS_WAV}" "${REMOTE_WAV}" >/dev/null 2>&1 ||
    abort "step-6/stimulus" "could not push ${STIMULUS_WAV} to the device"
  PUSHED_WAV=1

  adb_dev shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://${REMOTE_WAV}" >/dev/null 2>&1 || true
  # `media volume` is not present on every OEM build (it is absent on the Samsung tester); the
  # media_session shell command is, and does the same job. Try both, insist on neither.
  adb_dev shell cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1 ||
    adb_dev shell media volume --stream 3 --set 15 >/dev/null 2>&1 || true
  sleep 2

  local id uri component
  id="$(adb_dev shell content query --uri content://media/external/audio/media \
    --projection _id:_data 2>/dev/null |
    tr -d '\r' | grep -F "and02-stimulus.wav" |
    sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | head -n 1)"

  if [ -n "${id}" ]; then
    uri="content://media/external/audio/media/${id}"
    # An IMPLICIT VIEW lands on the system resolver dialog whenever more than one handler exists or
    # none is the user default — measured on the tester, where it put `ResolverActivity` on top and
    # played nothing. So resolve the handler and address it with `-n`. A chooser waiting for a tap is
    # indistinguishable, from here, from a run held in silence.
    component="$(adb_dev shell cmd package query-activities --brief \
      -a android.intent.action.VIEW -t audio/wav 2>/dev/null |
      tr -d '\r' | sed -n 's|^ *\([A-Za-z0-9_.]*/[A-Za-z0-9_.]*\)$|\1|p' | head -n 1)"

    if [ -n "${component}" ]; then
      adb_dev shell am start -n "${component}" -a android.intent.action.VIEW \
        -d "${uri}" -t audio/wav >/dev/null 2>&1 || true
    else
      adb_dev shell am start -a android.intent.action.VIEW -d "${uri}" -t audio/wav >/dev/null 2>&1 || true
    fi
    sleep 5
  fi

  if adb_dev shell dumpsys audio 2>/dev/null | grep -qi "state:started"; then
    say "step 6: acoustic playback confirmed"
    return
  fi

  # Plan 07 needed a file-manager onboarding plus an all-files grant to get this far, and this script
  # will not escalate a third-party app's permissions on the operator's behalf. Degrade LOUDLY to the
  # guaranteed floor rather than holding two minutes of silence and scoring it.
  say "step 6: ─────────────────────────────────────────────────────────────────────"
  say "step 6: WARNING — acoustic playback could not be confirmed. Falling back to the human floor."
  say "step 6: SPEAK NOW, CONTINUOUSLY, FOR ${DURATION_S} SECONDS (or start the WAV yourself)."
  say "step 6: ─────────────────────────────────────────────────────────────────────"
}

# ─── STEP 7 — stop and collect, BEFORE anything that could wipe app data ─────────────────────────

PULLED_LOG=""
PULLED_DIAGNOSTICS=""

step_7_collect() {
  say "step 7: stopping the session and pulling the trace"
  stop_session || true
  sleep 2

  local remote
  remote="$(newest_remote_log)"
  [ -n "${remote}" ] ||
    abort "step-7/pull" "no stt-session-*.jsonl in ${REMOTE_FILES_DIR}. The session log is opt-in; check that the start broadcast carried the session-log extra"

  mkdir -p "${EVIDENCE_DIR}"
  PULLED_LOG="${EVIDENCE_DIR}/and02-session-$(date -u +%Y%m%dT%H%M%SZ).jsonl"
  adb_dev pull "${remote}" "${PULLED_LOG}" >/dev/null ||
    abort "step-7/pull" "pulling ${remote} failed"

  local lines
  lines="$(wc -l <"${PULLED_LOG}")"
  say "step 7: OK — ${lines} lines at ${PULLED_LOG}"

  # The demo's own sidecar, which is NOT in the engine's frozen schema and is not scored. Pulled
  # beside the trace because it names the resolved recognizer service, which is the first thing worth
  # knowing when a verdict comes back red — and because it carries the file-feed counters the gate
  # below reads.
  PULLED_DIAGNOSTICS="${EVIDENCE_DIR}/and02-demo-diagnostics-$(date -u +%Y%m%dT%H%M%SZ).jsonl"
  adb_dev pull "${REMOTE_FILES_DIR}/demo-diagnostics.jsonl" "${PULLED_DIAGNOSTICS}" >/dev/null 2>&1 ||
    PULLED_DIAGNOSTICS=""

  # Leave the device as it was found. Anything this script put on shared storage comes back off,
  # including its MediaStore row — a borrowed device is returned, not kept.
  if [ "${PUSHED_WAV}" -eq 1 ]; then
    local id
    id="$(adb_dev shell content query --uri content://media/external/audio/media \
      --projection _id:_data 2>/dev/null |
      tr -d '\r' | grep -F "and02-stimulus.wav" |
      sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    [ -n "${id}" ] && adb_dev shell content delete \
      --uri "content://media/external/audio/media/${id}" >/dev/null 2>&1 || true
    adb_dev shell rm -f "${REMOTE_WAV}" >/dev/null 2>&1 || true
    say "step 7: removed the pushed stimulus from the device"
  fi

  if [ "${PUSHED_STIMULUS}" -eq 1 ]; then
    adb_dev shell rm -f "${REMOTE_STIMULUS}" >/dev/null 2>&1 || true
    say "step 7: removed ${REMOTE_STIMULUS} from the device"
  fi
}

# ─── STEP 7b — a file-fed run must PROVE it was file-fed, or it is not scored ─────────────────────
#
# The whole reason to feed a file is determinism. If the ladder did not land on rung (c), the
# app-owned pipe was never opened, the armed WAV was never read, and the session recorded whatever
# the room was doing — while the filename, the console output and the eventual report all say
# "file-fed". Scoring that is worse than not running at all: a wrong answer that looks like a
# measurement gets believed and acted on.
#
# Two independent sources have to agree, which is why neither is trusted alone: the ENGINE's own
# trace (what rung it actually ran) and the DEMO's sidecar (how many times the armed feed was really
# opened, and how many bytes of it were served). There is no bypass flag.

step_7b_prove_file_fed() {
  [ "${CHOSEN_STIMULUS}" = "file-fed" ] || return 0

  say "step 7b: proving the run was actually file-fed"

  local rung_lines raw_lines
  rung_lines="$(grep -c '"kind":"rung"' "${PULLED_LOG}" || true)"
  raw_lines="$(grep '"kind":"rung"' "${PULLED_LOG}" | grep -c '"value":"RawAudioSegmented"' || true)"

  [ "${rung_lines}" -gt 0 ] ||
    abort "step-7b/file-fed" "the trace names no rung at all, so there is no evidence this run reached a session. ${PULLED_LOG}"

  # EVERY session, not just the last: a mid-run degradation to rung (b) hands the microphone back to
  # the recognition service, so the remainder of the passage was never heard and the transcript is a
  # blend of two different stimuli.
  [ "${raw_lines}" = "${rung_lines}" ] ||
    abort "step-7b/file-fed" "only ${raw_lines} of ${rung_lines} sessions ran on RawAudioSegmented. The rest ran on a rung where the recognition service owns the microphone, so part of this run recorded the ROOM rather than the file. Re-run, or use AND02_STIMULUS=acoustic and accept its recall 0.559. Trace: ${PULLED_LOG}"

  [ -n "${PULLED_DIAGNOSTICS}" ] && [ -f "${PULLED_DIAGNOSTICS}" ] ||
    abort "step-7b/file-fed" "could not pull the demo diagnostics sidecar, so the file-feed open count is unknown and the file-fed claim is unverifiable"

  local last confirmed opens reason
  last="$(grep '"fileFedOpens"' "${PULLED_DIAGNOSTICS}" | tail -n 1)"
  [ -n "${last}" ] ||
    abort "step-7b/file-fed" "the sidecar carries no file-feed counters. Either the DUMP_DIAGNOSTICS broadcast never landed or the demo predates the audio-source extra: ${PULLED_DIAGNOSTICS}"

  confirmed="$(echo "${last}" | sed -n 's/.*"fileFedConfirmed":\([a-z][a-z]*\).*/\1/p')"
  opens="$(echo "${last}" | sed -n 's/.*"fileFedOpens":\([0-9][0-9]*\).*/\1/p')"
  reason="$(echo "${last}" | sed -n 's/.*"fileFedAbortReason":"\([^"]*\)".*/\1/p')"

  [ "${opens:-0}" -gt 0 ] 2>/dev/null ||
    abort "step-7b/file-fed" "the armed WAV was opened ${opens:-0} times, so none of it reached the recognizer. ${reason:+Demo said: ${reason}}"

  [ "${confirmed}" = "true" ] ||
    abort "step-7b/file-fed" "the demo did not confirm the file-fed run. ${reason:+Reason: ${reason}} Sidecar: ${PULLED_DIAGNOSTICS}"

  say "step 7b: OK — ${rung_lines}/${rung_lines} sessions on RawAudioSegmented, feed opened ${opens}x, demo confirmed"
}

# ─── STEP 7c — a capped run must PROVE it ran on the rung it asked for ────────────────────────────
#
# Exactly step 7b's argument, applied to the other seam. A ceiling is a REQUEST sent over a broadcast
# to a receiver that may have refused it, may predate the extra, or may have been handed a name it
# did not recognise. If the cap did not land, the run happened on the device's usual rung — and a run
# labelled "rung (a), 13 restarts" that actually ran on rung (c) with zero restarts is not merely
# wrong, it is evidence-shaped.
#
# The ENGINE's own trace is the authority: it writes a `rung` line per session. Every one of them has
# to name the requested rung, not just the last — a mid-run change means the transcript is a blend of
# two different behaviours. There is no bypass flag.

step_7c_prove_rung_ceiling() {
  [ -n "${RUNG_CEILING}" ] || return 0

  say "step 7c: proving the run honoured the ${RUNG_CEILING} ceiling"

  local rung_lines matching observed
  rung_lines="$(grep -c '"kind":"rung"' "${PULLED_LOG}" || true)"
  matching="$(grep '"kind":"rung"' "${PULLED_LOG}" | grep -c "\"value\":\"${RUNG_CEILING}\"" || true)"

  [ "${rung_lines}" -gt 0 ] ||
    abort "step-7c/ceiling" "the trace names no rung at all, so there is no evidence this run reached a session. ${PULLED_LOG}"

  if [ "${matching}" != "${rung_lines}" ]; then
    observed="$(grep '"kind":"rung"' "${PULLED_LOG}" | sed -n 's/.*"value":"\([^"]*\)".*/\1/p' | sort -u | tr '\n' ' ')"
    abort "step-7c/ceiling" "only ${matching} of ${rung_lines} sessions ran on ${RUNG_CEILING} (observed: ${observed}). The cap did not land, so this run measured a different rung than the one it is labelled with. Trace: ${PULLED_LOG}"
  fi

  # The demo's sidecar echoes what it was ASKED for, which separates "the cap was ignored" from
  # "the cap was never delivered" when the two numbers above disagree.
  if [ -n "${PULLED_DIAGNOSTICS}" ] && [ -f "${PULLED_DIAGNOSTICS}" ]; then
    local asked
    asked="$(grep '"rungCeiling"' "${PULLED_DIAGNOSTICS}" | tail -n 1 | sed -n 's/.*"rungCeiling":"\([^"]*\)".*/\1/p')"
    [ "${asked}" = "${RUNG_CEILING}" ] ||
      abort "step-7c/ceiling" "the demo reports the ceiling as \"${asked:-none}\" but this run asked for ${RUNG_CEILING}; the extra did not reach the receiver. ${PULLED_DIAGNOSTICS}"
    say "step 7c: the demo confirms it was asked for ${asked}"
  fi

  say "step 7c: OK — ${matching}/${rung_lines} sessions on ${RUNG_CEILING}"
}

# ─── STEP 8 — score ──────────────────────────────────────────────────────────────────────────────

step_8_score() {
  say "step 8: scoring ${PULLED_LOG} with SessionLogAnalyzer"

  # A Gradle test JVM does not inherit -D from the command line, and this script must not edit
  # stt/build.gradle.kts to add the wiring — that file is a frozen-API surface another plan owns. An
  # init script sets the property on the Test task itself, which is both the supported mechanism and
  # one that leaves no trace in the repository.
  local init_script="${WORK_DIR}/and02-init.gradle"
  cat >"${init_script}" <<GRADLE_INIT
gradle.allprojects { project ->
    project.tasks.withType(Test).configureEach { testTask ->
        testTask.systemProperty('stt.sessionLog', '${PULLED_LOG}')
        testTask.outputs.upToDateWhen { false }
        testTask.testLogging { showStandardStreams = true }
    }
}
GRADLE_INIT

  # The whole analyzer class runs, not just the entry point: the same invocation that scores the
  # device log re-proves the analyzer against its seven fixtures, so a green verdict is never a
  # verdict from a broken instrument.
  set +e
  (cd "${ANDROID_DIR}" && ./gradlew :stt:testDebugUnitTest \
    --tests '*SessionLogAnalyzerTest*' -I "${init_script}")
  local status=$?
  set -e

  if [ "${status}" -eq 0 ]; then
    say "step 8: AND-02 PASSED — ${PULLED_LOG}"
  else
    echo "run-and02: ABORT [step-8/score] AND-02 FAILED — see the assertion report above; the trace is at ${PULLED_LOG}" >&2
  fi
  return "${status}"
}

# ─── session control ─────────────────────────────────────────────────────────────────────────────

# $1 (optional): an on-device WAV path to feed rung (c) instead of the microphone. Omitted by the
# language probe on purpose — that is a short preflight session, and arming the passage for it would
# burn the opening seconds of the stimulus before the scored run ever starts.
start_session() {
  local audio_source="${1:-}"

  # The receiver drives the live view model and cannot conjure one, so the screen has to be up first.
  adb_dev shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1
  sleep 3

  # The ceiling rides on the SCORED start only. The language probe passes "" for it deliberately:
  # capping that preflight would tell us whether the language works on rung (a) rather than on the
  # rung the run is about.
  local ceiling="${2:-}"
  local -a extras=(--ez "${EXTRA_SESSION_LOG}" true --es "${EXTRA_LANGUAGE}" "${LANGUAGE}")
  [ -n "${audio_source}" ] && extras+=(--es "${EXTRA_AUDIO_SOURCE}" "${audio_source}")
  [ -n "${ceiling}" ] && extras+=(--es "${EXTRA_RUNG_CEILING}" "${ceiling}")

  adb_dev shell am broadcast -n "${RECEIVER}" -a "${ACTION_START}" "${extras[@]}" >/dev/null
  sleep 2
}

stop_session() {
  adb_dev shell am broadcast -n "${RECEIVER}" -a "${ACTION_STOP}" >/dev/null
}

newest_remote_log() {
  adb_dev shell "ls -1t ${REMOTE_FILES_DIR}/stt-session-*.jsonl 2>/dev/null | head -n 1" |
    tr -d '\r' | grep '\.jsonl$' || true
}

# Only the engine's own traces, and only so that "the newest one" is unambiguous. The demo's sidecar
# is left alone.
clear_remote_logs() {
  adb_dev shell "rm -f ${REMOTE_FILES_DIR}/stt-session-*.jsonl" >/dev/null 2>&1 || true
}

# ─── main ────────────────────────────────────────────────────────────────────────────────────────

# The step NUMBERS are the plan's; the ORDER below is the dependency order, and it differs in one
# place. The language check has no adb surface to query — the only authority on "can this library
# use this language on this device" is the engine's own preflight — so it is answered by driving a
# short real session, which needs the app installed. Step 5 therefore runs before step 3. Both
# still run before the run itself, which is what the requirement is actually about, and the install
# is idempotent.
say "targeting ${SERIAL} · language ${LANGUAGE} · ${DURATION_S}s · stimulus ${STIMULUS}"
step_0_verify_interface
step_1_device
step_5_install
step_3_language
step_4_power
step_6_run
step_7_collect
step_7b_prove_file_fed
step_7c_prove_rung_ceiling
step_8_score
