#!/usr/bin/env bash
# check-no-recognition-dependency.sh — the fast, Gradle-invocation-free pre-flight half of REC-05's
# module-boundary guarantee (T-08-10).
#
# THE AUTHORITATIVE, RESOLVED-GRAPH PROOF is android/recorder/build.gradle.kts's
# verifyNoRecognitionDependency Gradle task (already wired into :recorder:check via
# `tasks.named("check") { dependsOn("verifyNoRecognitionDependency") }`). That task inspects the
# REAL resolved releaseRuntimeClasspath/releaseCompileClasspath configurations — the actual
# dependency graph the build will use — and is what makes the guarantee durable against a future
# regression (it fails automatically on every standard `:recorder:check`, not just at this plan's
# execution time).
#
# THIS SCRIPT is the immediate, zero-build-time tripwire: a plain grep of this module's own
# build.gradle.kts source text for any ":stt" reference, so a human or CI can catch an obvious
# regression before even invoking Gradle. It is the same belt-and-suspenders relationship
# check-r8-mapping.sh has to the module-boundary guarantee itself — fast and static, not a
# substitute for the resolved-graph check above.
#
# Usage:
#   cd android && bash recorder/check-no-recognition-dependency.sh
#
# Exit 0 if android/recorder/build.gradle.kts names no ":stt" reference. Exit 1, printing the
# offending line(s), otherwise.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_FILE="${SCRIPT_DIR}/build.gradle.kts"

fail() { echo "check-no-recognition-dependency: FAIL — $*" >&2; exit 1; }

[ -f "${BUILD_FILE}" ] || fail "build.gradle.kts not found at ${BUILD_FILE}."

# Strip `//` comments before scanning — both full-line (allowing leading whitespace) and trailing
# same-line comments (IN-01: a legitimate trailing comment mentioning ":stt" for documentation
# purposes must not false-positive-fail this check). This file's own doc comments legitimately
# DISCUSS ":stt" (documenting what must never be added) and verifyNoRecognitionDependency's own
# enforcement code legitimately holds the bare string ":stt" as its COMPARISON TARGET
# (`val forbiddenProjectPath = ":stt"`), not as a dependency declaration. Only a real Gradle
# dependency notation — `project(":stt")`, or the published `com.github.Ygaray:stt-engine-android`
# coordinate string used inside a dependency call — is a genuine regression this script must catch.
# (No line in this file legitimately needs a literal `//` inside a string, so the trailing-comment
# strip is safe here without special-casing string literals.)
cleaned="$(sed -E 's#^[[:space:]]*//.*$##; s#[[:space:]]*//.*$##' "${BUILD_FILE}")"

# Line-scoped view: catches the common single-line regression shape and reports exact line numbers.
hits="$(printf '%s\n' "${cleaned}" | grep -nE 'project\(":stt"\)|com\.github\.Ygaray:stt-engine-android' || true)"

# WR-01: a dependency declaration wrapped across multiple lines —
#   implementation(
#       project(":stt")
#   )
# — contains the literal substring `project(":stt")` on NO single line, so the line-scoped grep
# above silently misses it (this exact wrapping style is already used elsewhere in this codebase,
# e.g. multi-line `proguardFiles(...)` calls). Collapse the whole comment-stripped file into one
# whitespace-normalized string and re-check there too, with a whitespace-tolerant pattern (Kotlin
# allows a newline/indentation between `project(` and its closing `)`), so a multi-line reference
# cannot evade detection just by being reformatted.
joined="$(printf '%s' "${cleaned}" | tr '\n' ' ' | tr -s '[:space:]' ' ')"
joined_hit="$(printf '%s' "${joined}" | grep -oE 'project\([[:space:]]*":stt"[[:space:]]*\)|com\.github\.Ygaray:stt-engine-android' || true)"

if [ -n "${hits}" ]; then
  echo "check-no-recognition-dependency: FAIL — :recorder/build.gradle.kts declares a real dependency on :stt:" >&2
  echo "${hits}" | sed 's/^/    /' >&2
  fail ":recorder must NEVER depend on :stt or its published coordinate (T-08-10, REC-05). Remove the reference above."
elif [ -n "${joined_hit}" ]; then
  echo "check-no-recognition-dependency: FAIL — :recorder/build.gradle.kts declares a real dependency on :stt (wrapped across multiple lines, not caught by the single-line scan):" >&2
  echo "    ${joined_hit}" >&2
  fail ":recorder must NEVER depend on :stt or its published coordinate (T-08-10, REC-05). Remove the reference above."
fi

echo "check-no-recognition-dependency: OK — build.gradle.kts names no :stt reference."
echo "  (static pre-flight only: the authoritative, resolved-graph proof is"
echo "   :recorder:verifyNoRecognitionDependency, wired into :recorder:check.)"
