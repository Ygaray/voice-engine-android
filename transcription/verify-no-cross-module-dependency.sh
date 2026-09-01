#!/usr/bin/env bash
# verify-no-cross-module-dependency.sh — the fast, Gradle-invocation-free pre-flight half of
# HUB-01's module-boundary guarantee.
#
# THE AUTHORITATIVE, RESOLVED-GRAPH PROOF is android/transcription/build.gradle.kts's
# verifyIndependentModule Gradle task (already wired into :transcription:check via
# `tasks.named("check") { dependsOn("verifyIndependentModule") }`). That task inspects the REAL
# resolved releaseRuntimeClasspath/releaseCompileClasspath configurations — the actual dependency
# graph the build will use — and is what makes the guarantee durable against a future regression
# (it fails automatically on every standard `:transcription:check`, not just at this plan's
# execution time).
#
# THIS SCRIPT is the immediate, zero-build-time tripwire: a plain grep of this module's own
# build.gradle.kts source text for any ":stt"/":recorder"/":voicenotes"/Room/WorkManager reference,
# so a human or CI can catch an obvious regression before even invoking Gradle. It is the same
# belt-and-suspenders relationship check-no-recognition-dependency.sh has to :recorder's own
# module-boundary guarantee — fast and static, not a substitute for the resolved-graph check above.
#
# Usage:
#   cd android && bash transcription/verify-no-cross-module-dependency.sh
#
# Exit 0 if android/transcription/build.gradle.kts names no forbidden reference. Exit 1, printing
# the offending line(s), otherwise.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_FILE="${SCRIPT_DIR}/build.gradle.kts"

fail() { echo "verify-no-cross-module-dependency: FAIL — $*" >&2; exit 1; }

[ -f "${BUILD_FILE}" ] || fail "build.gradle.kts not found at ${BUILD_FILE}."

# Strip `//` comments before scanning — both full-line (allowing leading whitespace) and trailing
# same-line comments (this file's own doc comments legitimately DISCUSS ":stt"/":recorder"/
# ":voicenotes" for documentation purposes, and verifyIndependentModule's own enforcement code
# legitimately holds the bare strings as its COMPARISON TARGETS, not as dependency declarations.
# Only a real Gradle dependency notation is a genuine regression this script must catch).
cleaned="$(sed -E 's#^[[:space:]]*//.*$##; s#[[:space:]]*//.*$##' "${BUILD_FILE}")"

# Line-scoped view: catches the common single-line regression shape and reports exact line numbers.
# NOTE on the Room/WorkManager patterns: they require a trailing colon (`androidx.room:`,
# `androidx.work:`) — the real shape a dependency coordinate takes (e.g.
# `androidx.room:room-runtime:2.8.4`). This is deliberate: verifyIndependentModule's own
# enforcement code below stores the bare comparison-target strings `"androidx.room"`/
# `"androidx.work"` (no trailing colon, used with `startsWith()`), and a colon-less pattern here
# would self-match those bare strings as a false positive.
hits="$(printf '%s\n' "${cleaned}" | grep -nE \
  'project\(":stt"\)|project\(":recorder"\)|project\(":voicenotes"\)|com\.github\.Ygaray:voice-engine-android(-recorder|-voicenotes)?|androidx\.room:|androidx\.work:' \
  || true)"

# A dependency declaration wrapped across multiple lines —
#   implementation(
#       project(":stt")
#   )
# — contains the literal substring `project(":stt")` on NO single line, so the line-scoped grep
# above silently misses it (this exact wrapping style is already used elsewhere in this codebase,
# e.g. multi-line `proguardFiles(...)` calls). Collapse the whole comment-stripped file into one
# whitespace-normalized string and re-check there too, with a whitespace-tolerant pattern, so a
# multi-line reference cannot evade detection just by being reformatted.
joined="$(printf '%s' "${cleaned}" | tr '\n' ' ' | tr -s '[:space:]' ' ')"
joined_hit="$(printf '%s' "${joined}" | grep -oE \
  'project\([[:space:]]*":stt"[[:space:]]*\)|project\([[:space:]]*":recorder"[[:space:]]*\)|project\([[:space:]]*":voicenotes"[[:space:]]*\)|com\.github\.Ygaray:voice-engine-android(-recorder|-voicenotes)?|androidx\.room:|androidx\.work:' \
  || true)"

if [ -n "${hits}" ]; then
  echo "verify-no-cross-module-dependency: FAIL — :transcription/build.gradle.kts declares a real dependency on a forbidden module:" >&2
  echo "${hits}" | sed 's/^/    /' >&2
  fail ":transcription must NEVER depend on :stt/:recorder/:voicenotes/Room/WorkManager (HUB-01). Remove the reference above."
elif [ -n "${joined_hit}" ]; then
  echo "verify-no-cross-module-dependency: FAIL — :transcription/build.gradle.kts declares a real dependency on a forbidden module (wrapped across multiple lines, not caught by the single-line scan):" >&2
  echo "    ${joined_hit}" >&2
  fail ":transcription must NEVER depend on :stt/:recorder/:voicenotes/Room/WorkManager (HUB-01). Remove the reference above."
fi

echo "verify-no-cross-module-dependency: OK — build.gradle.kts names no forbidden reference."
echo "  (static pre-flight only: the authoritative, resolved-graph proof is"
echo "   :transcription:verifyIndependentModule, wired into :transcription:check.)"
