#!/usr/bin/env bash
# check-r8-mapping.sh — REC-05's Android SECONDARY proof (plan 08-05: the module-boundary guarantee
# below is now primary), made mechanical and non-vacuous.
#
# AS OF PLAN 08-05: :verify-recorder-only depends on project(":recorder") alone — a module whose own
# dependencies {} block never names :stt. That compile-time dependency already proves this
# consumer's classpath contains ZERO :stt bytecode under ANY build configuration. THE AUTHORITATIVE
# REC-05 PROOF is now :recorder/build.gradle.kts's verifyNoRecognitionDependency Gradle task (wired
# into :recorder:check) plus its static-grep companion, :recorder/check-no-recognition-dependency.sh.
# This script is a secondary, belt-and-suspenders check layered on top of that guarantee, not the
# primary one — do not mistake it for the authoritative proof.
#
# WHAT THIS SCRIPT (still) PROVES: :verify-recorder-only imports ONLY io.github.ygaray.sttengine.recorder.*
# (never io.github.ygaray.sttengine.SttEngine/createSttEngine or any internal recognition symbol) and
# builds with isMinifyEnabled=true + an EMPTY proguard-rules.pro. If R8's own reachability analysis
# still retains none of the five recognition-only internal classes in that build's real mapping.txt,
# that is additional, mechanical evidence on top of the module-boundary guarantee above.
#
# WHAT THIS DOES NOT PROVE — two caveats this script documents rather than silently resolving
# (08-RESEARCH.md, Pitfall 4 / Assumption A3 and Pitfall 5) — both remain true even now that the
# module boundary is primary, since they describe THIS script's own residual scope, not REC-05 as a
# whole:
#
#   1. BEST CASE ONLY, build-config-dependent. A debug build, an unminified release, or any real
#      consumer that happens to reference an SttEngine symbol for unrelated reasons ships 100% of the
#      recognition code regardless of what this script finds. This harness says nothing about those
#      configurations — it is not a general REC-05 guarantee, only a proof for the specific
#      minified-release configuration it builds.
#   2. MANIFEST MERGE IS UNCONDITIONAL AND R8-INDEPENDENT. The RECORD_AUDIO permission and the
#      RecognitionService <queries> intent-filter merge into every :stt consumer's manifest
#      regardless of R8/minification. R8 has no effect on manifest XML, so this script cannot and
#      does not check for their absence. Whether that counts as "recognition code" for REC-05's
#      literal text is an open judgment call, not decided here.
#
# Usage:
#   cd android && ./gradlew :verify-recorder-only:assembleRelease && bash verify-recorder-only/check-r8-mapping.sh
#
# Exit 0 when the mapping.txt this script reads is real (non-vacuous) AND names none of the five
# recognition-only classes. Exit 1, printing the specific failing assertion, otherwise.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPING_FILE="${SCRIPT_DIR}/build/outputs/mapping/release/mapping.txt"

fail() { echo "check-r8-mapping: FAIL — $*" >&2; exit 1; }

# --- (a) the mapping file must exist and be non-empty --------------------------------------------

[ -f "${MAPPING_FILE}" ] || fail "mapping.txt not found at ${MAPPING_FILE}. Run
  ./gradlew :verify-recorder-only:assembleRelease
first — this script only inspects a real R8 output, it never generates one."

[ -s "${MAPPING_FILE}" ] || fail "mapping.txt at ${MAPPING_FILE} exists but is empty."

# --- (b) vacuous-pass-prevention: the dump must name something real from the recorder package -----
# Mirrors ReleaseAudioSourceSeamTest.theFrozenApiDumpNamesNoSeamSymbol's discipline: assert the dump
# names something expected BEFORE asserting the absence of something forbidden, so a broken build
# (or a mapping.txt from the wrong task, or a fully-vacuous R8 run with no reachability root) fails
# loud here instead of making every absence-assertion below pass on an empty/irrelevant file.
recorder_entries="$(grep -c 'io\.github\.ygaray\.sttengine\.recorder\.' "${MAPPING_FILE}" || true)"
[ "${recorder_entries}" -gt 0 ] || fail "mapping.txt names ZERO io.github.ygaray.sttengine.recorder.*
entries. Either the mapping.txt is stale/from the wrong build, or R8 had no reachability root and
shrank the whole module to nothing — every assertion below would otherwise pass vacuously. Found
${recorder_entries} matching lines in ${MAPPING_FILE}."

echo "check-r8-mapping: mapping.txt names ${recorder_entries} io.github.ygaray.sttengine.recorder.* entries (non-vacuous)"

# --- (c) the five recognition-only internal classes must be ABSENT as retained-class keys ---------
#
# <!-- planner-discipline-allow: SttEngineImpl -->
# <!-- planner-discipline-allow: RecognizerHandle -->
# <!-- planner-discipline-allow: PlatformRecognizerHandle -->
# <!-- planner-discipline-allow: RecognizerIntents -->
# <!-- planner-discipline-allow: RecognizerBundles -->
forbidden_classes=(
  "SttEngineImpl"
  "RecognizerHandle"
  "PlatformRecognizerHandle"
  "RecognizerIntents"
  "RecognizerBundles"
)

any_leaked=0
for cls in "${forbidden_classes[@]}"; do
  # Match the class as a retained-class KEY: the left-hand side of an R8 mapping.txt class line
  # ("original.package.ClassName -> obfuscated:"), anchored at line start (never indented — member
  # lines are always indented) so a substring match inside an unrelated signature can't false-fire.
  hit="$(grep -E "^[A-Za-z0-9_.]*\\b${cls}\\b -> " "${MAPPING_FILE}" || true)"
  if [ -n "${hit}" ]; then
    echo "check-r8-mapping: FAIL — recognition-only class '${cls}' is retained in mapping.txt:" >&2
    echo "${hit}" | sed 's/^/    /' >&2
    any_leaked=1
  fi
done

[ "${any_leaked}" -eq 0 ] || fail "one or more recognition-only internal classes are retained in a
record-only consumer's minified release build. See output above for which class(es) and their
mapping.txt entry."

echo "check-r8-mapping: OK — mapping.txt is non-vacuous and names none of the five recognition-only classes."
echo "  (best-case proof only: unminified/debug consumer builds and manifest-merged"
echo "   RECORD_AUDIO/<queries> entries are NOT covered by this check — see this script's header.)"
