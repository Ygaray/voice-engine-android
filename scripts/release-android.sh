#!/usr/bin/env bash
# release-android.sh — cut a PUBLIC git-tag release of the Android library via a subtree-split
# mirror that free JitPack builds (D-20 / D-21 / D-22 / D-23).
#
# ┌─ WHAT THIS DOES ────────────────────────────────────────────────────────────────────────────┐
# │ Development stays in the private `stt-engine` mono-repo. A release promotes THIS directory   │
# │ (`android/`) to the ROOT of a separate PUBLIC repo (`voice-engine-android`) via              │
# │ `git subtree split --prefix=android`, then tags `vX.Y.Z` there. JitPack builds that tag on   │
# │ first request and serves `com.github.<org>:voice-engine-android:vX.Y.Z`. Consumers need no   │
# │ credentials, no mono-repo checkout and no manual steps.                                      │
# └─────────────────────────────────────────────────────────────────────────────────────────────┘
#
# THIS FILE IS PUBLISHED. It sits inside the split prefix, so it becomes world-readable the moment
# the mirror is pushed. It therefore contains no private hostname, no server detail and no path
# from the development machine — the same invariant `android/CLAUDE.md` §5 puts on the four
# adoption documents. GATE 3b below enforces that invariant on the whole split tree, including on
# this script itself, which is why its hostname pattern is assembled as a regex rather than written
# as a literal: a literal would be both a leak and a self-match.
#
# ┌─ WHY THE GATES EXIST: A TAG IS IMMUTABLE ───────────────────────────────────────────────────┐
# │ JitPack resolves a coordinate to a git tag ONCE and caches the built artifact permanently.   │
# │ The tag string is used literally as the version segment. So every failure discovered AFTER   │
# │ the tag exists is a failure that cannot be undone — it can only be superseded by burning a   │
# │ version number. Everything checkable before the tag is checked before the tag.               │
# └─────────────────────────────────────────────────────────────────────────────────────────────┘
#
#   GATE 1 — the library builds and its public surface is frozen, across all three published
#            modules.
#            `:stt:assembleRelease` + `:stt:apiCheck`, `:recorder:assembleRelease` +
#            `:recorder:apiCheck`, `:voicenotes:assembleRelease` + `:voicenotes:apiCheck`. A surface
#            that drifted without a deliberate `apiDump` regeneration must never reach a tag:
#            consumers pin tags, and an accidental ABI change is only discovered in somebody else's
#            build.
#
#   GATE 2 — the JVM suite is green INCLUDING cross-language parity in HARD mode.
#            `ErrorCodeParityTest` reads `web/src/errors.ts`, which is a SIBLING of `android/` and
#            therefore above the split prefix — absent from the mirror. It degrades to an
#            `Assume`-skip when that file is missing (Obstacle 1, resolution A) so a curious
#            consumer running `./gradlew build` on the mirror does not see a red build.
#            `-Pstt.requireWebParity=true` converts that graceful skip into an explicit failure, so
#            a release can NEVER be tagged off a parity check that quietly did not run.
#            Then the repo-root `scripts/check-error-code-parity.sh`, which is the real gate: it is
#            in NEITHER split prefix and is the only place all three language mirrors (Python,
#            TypeScript, Kotlin) are visible at once. A gate that cannot see every input it
#            compares is not a gate.
#            Then, in the same place and for the same reason, the repo-root
#            `scripts/check-wire-frame-parity.sh` (the final wire frame across Python, TypeScript
#            and Kotlin against one golden fixture) and `scripts/check-correction-fixture-parity.sh`
#            (the two committed correction golden-vector copies). Neither gate has a bypass flag.
#            Both run with every fixture-override and test-input variable stripped from the child
#            environment (`env -u`) and with no fixture flag, so a stale or hostile shell can
#            neither redirect a gate to other fixtures nor block it: a release always scores the
#            committed fixtures.
#            NOTE: that script lives OUTSIDE the split prefix on purpose and is deliberately not
#            reachable from the mirror. That is not a defect — the mirror ships one of the three
#            languages, so it structurally cannot run a three-way check. The mono-repo is the only
#            place a release can be cut from, which this script's own path resolution assumes.
#
#   GATE 3 — dry-run the ACTUAL JitPack build against the ACTUAL split tree.
#            Produce the split branch, clone it into a temp dir, and run the literal command from
#            the mirror's own `jitpack.yml` against that clone. This converts four expensive
#            post-tag failures into pre-tag aborts:
#              (a) the Gradle wrapper did not survive the split          → `./gradlew: not found`
#              (b) a root-level file the build needs is not in the split → "Project 'x' not found"
#              (c) the SECOND module breaks configuration. `backup-engine`'s proven recipe has ONE
#                  module; this build has two (`:stt` publishes, `:demo` does not). JitPack
#                  configures the whole build regardless of which module `install:` names, so the
#                  demo module is the one real delta from the proven recipe (Obstacle 6).
#              (d) a wrong artifactId → a permanently wrong consumer coordinate.
#            The install command is EXTRACTED from `jitpack.yml` rather than retyped here, so the
#            gate cannot drift from the file JitPack actually reads; it is then asserted to still
#            contain `:stt:publishReleasePublicationToMavenLocal` so a silent rewrite of that file
#            fails loudly instead of dry-running something else.
#
#   GATE 3b — leak check on the split tree, BEFORE any push.
#            D-22 makes the mirror public deliberately, and that decision is justified ONLY while
#            the exposed surface is the Kotlin library alone. This re-proves that on every release
#            instead of once: no `.planning/`, no `server/`, no `local.properties`, no tailnet
#            hostname, no development-machine path. Cheap, and the alternative is a permanent
#            public disclosure (threats T-05-06, T-05-07).
#            It runs on the PRISTINE clone — between GATE 3's clone and GATE 3's build — and is
#            scoped to TRACKED paths and TRACKED content only. See the block itself for why: a
#            filesystem walk of the post-build tree produces false positives on AGP's own build
#            outputs, and tracked content is what `git push` actually sends.
#
#   PREREQUISITES — mono-repo release only. The wire-parity gate in GATE 2 scores three languages, so
#            the server virtual environment (server/.venv with pytest), the web dependencies
#            (web/node_modules) and the Gradle wrapper must already exist. They are untracked
#            installs, so a clean checkout lacks the first two. `scripts/check-wire-gate-prereqs.sh`
#            runs BEFORE GATE 1, checks all three, prints the exact setup command for each missing
#            one and stops in seconds instead of after the multi-minute build. It only checks: the
#            release never installs or creates anything for you.
#
# NO GATE MAY BE SKIPPED. There is no bypass flag, on purpose — the failures these catch are
# exactly the ones that are unrecoverable once the tag exists. The optional `preflight` argument is
# not a bypass: it runs EVERY gate and then stops before the irreversible push. It is strictly
# safer than the default, never less safe, and it is what makes the release path itself testable.
#
# Usage:
#   VERSION=<x.y.z> ./scripts/release-android.sh              # gates, then create the mirror tag
#   VERSION=<x.y.z> ./scripts/release-android.sh preflight    # gates only, no push, no tag
# Optional overrides (defaults in brackets):
#   GIT_HOST [github.com]   ORG [Ygaray]
#
# TAG NAMING (recorded decision, permanent once published). The mirror repo contains only the
# Android library, so its NAME already says "android" — a plain `vX.Y.Z` yields the clean
# `com.github.<org>:voice-engine-android:vX.Y.Z`, matching the sibling library's shape exactly. The
# alternative `android-vX.Y.Z` would produce `…:voice-engine-android:android-v0.1.0`, which is
# redundant and, because JitPack uses the tag string literally, permanent. If provenance in the
# mono-repo is wanted, tag the MONO-REPO separately as `android-vX.Y.Z`; a mono-repo tag is never a
# JitPack input.
#
# CREDENTIAL CAVEAT (carried over from `web/scripts/release-web.sh`): the https push authenticates
# through THIS machine's locally configured git credential helper, which is machine-local. CI or a
# second machine needs its own token, or a deploy key plus the ssh transport.

set -euo pipefail

MODE="${1:-publish}"
case "${MODE}" in
  publish|preflight) ;;
  *)
    echo "release-android: usage: VERSION=x.y.z $0 [preflight]" >&2
    exit 2
    ;;
esac

: "${VERSION:?set VERSION (semver without the leading v, e.g. 0.1.0)}"
GIT_HOST="${GIT_HOST:-github.com}"
ORG="${ORG:-Ygaray}"

# NOT env-driven: the artifactId baked into the module's publication block must EQUAL the mirror
# repository name (that is the rule that makes the consumer coordinate clean — proven in-house by
# the sibling library, whose module is `:backup` and whose artifactId is `backup-engine`). Making
# the repo name a variable would let the two drift into a permanently wrong coordinate.
MIRROR_REPO="voice-engine-android"
GROUP_ID="com.github.${ORG}"
TAG="v${VERSION}"
SPLIT_BRANCH="android-release"
PUSH_URL="https://${GIT_HOST}/${ORG}/${MIRROR_REPO}.git"

# Resolve paths from this script's own location: `android/` is the package root, the mono-repo root
# is its parent. Never from $PWD — a release must not depend on where it was invoked from.
ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${ANDROID_DIR}/.." && pwd)"

abort() {
  echo "release-android: ABORT — $1; refusing to tag ${TAG}." >&2
  exit 1
}

# The declared publication version and the tag being cut must agree. JitPack overrides the version
# from the resolved ref, so a mismatch would not break the published artifact — but it WOULD mean
# GATE 3 dry-ran a different version than the one being tagged, i.e. a gate verifying the wrong
# thing while reporting green.
DECLARED_VERSION="$(
  grep -oE '^[[:space:]]*version = "[0-9]+\.[0-9]+\.[0-9]+"' "${ANDROID_DIR}/stt/build.gradle.kts" \
    | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true
)"
[ "${DECLARED_VERSION}" = "${VERSION}" ] || abort \
  "stt/build.gradle.kts declares publication version '${DECLARED_VERSION}' but VERSION=${VERSION}"

# WR-02 (136-REVIEW.md / T-136-05): :stt's own literal agreeing with VERSION is not enough — all
# four published modules must agree with each other, or GATE 1-3 above would build/test/publish
# each module at whatever version its OWN build.gradle.kts declares (no cross-module check) and tag
# successfully, shipping four artifacts under one immutable tag at inconsistent internal versions.
# This used to be closed only by a human re-grepping by hand; close it at the tooling level instead.
for module in recorder voicenotes transcription corrections; do
  module_version="$(
    grep -oE '^[[:space:]]*version = "[0-9]+\.[0-9]+\.[0-9]+"' "${ANDROID_DIR}/${module}/build.gradle.kts" \
      | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true
  )"
  [ "${module_version}" = "${VERSION}" ] || abort \
    "${module}/build.gradle.kts declares publication version '${module_version}' but VERSION=${VERSION}"
done

# --- temp state, removed on EVERY exit path including failure and interrupt --------------------
TMP=""
cleanup() {
  local rc=$?
  [ -n "${TMP}" ] && [ -d "${TMP}" ] && rm -rf "${TMP}"
  # The split branch is throwaway on every path: leaving it behind makes the next run fail on an
  # existing ref, and it is reproducible from the prefix at any time.
  git -C "${REPO_ROOT}" branch -D "${SPLIT_BRANCH}" >/dev/null 2>&1 || true
  return "${rc}"
}
trap cleanup EXIT INT TERM

echo "release-android: preparing ${TAG} → ${PUSH_URL}   (mode: ${MODE})"
echo "release-android: coordinate will be ${GROUP_ID}:${MIRROR_REPO}:${TAG}"

# --- PREREQUISITES: the wire-parity gate needs untracked installs; fail in seconds, not after GATE 1 --
echo "release-android: PREREQUISITES - scripts/check-wire-gate-prereqs.sh (server virtual environment, web dependencies, Gradle wrapper) ..."
( cd "${REPO_ROOT}" && bash scripts/check-wire-gate-prereqs.sh ) || abort "PREREQUISITES failed - the wire-parity gates in GATE 2 need the server virtual environment and the web dependencies; the diagnostic above gives the exact setup commands; the release never installs them for you"

# --- GATE 1: all three libraries build and their public surfaces are frozen --------------------
# Covers :stt/:recorder/:voicenotes, not just :stt (RESEARCH.md Pitfall 3: the script used to gate
# only the first module ever published, silently shipping the other two with zero pre-tag proof).
echo "release-android: GATE 1 — :stt/:recorder/:voicenotes/:transcription/:corrections assembleRelease + apiCheck …"
( cd "${ANDROID_DIR}" && ./gradlew --no-daemon \
    :stt:assembleRelease :stt:apiCheck \
    :recorder:assembleRelease :recorder:apiCheck \
    :voicenotes:assembleRelease :voicenotes:apiCheck \
    :transcription:assembleRelease :transcription:apiCheck \
    :corrections:assembleRelease :corrections:apiCheck ) \
  || abort "GATE 1 failed — one of :stt/:recorder/:voicenotes/:transcription/:corrections does not \
build, or a frozen API surface changed without a deliberate ./gradlew <module>:apiDump. Read the \
apiCheck diff before regenerating the dump"

# --- GATE 2: JVM suite green across all three modules, parity in HARD mode on :stt, plus the ----
# three-way repo-root gate. The parity flag applies ONLY to :stt (ErrorCodeParityTest lives there);
# :recorder/:voicenotes carry no parity test of their own, so it is not added to their invocations.
echo "release-android: GATE 2 — :stt:testDebugUnitTest -Pstt.requireWebParity=true, \
:recorder:testDebugUnitTest, :voicenotes:testDebugUnitTest, :transcription:testDebugUnitTest, \
:corrections:testDebugUnitTest …"
( cd "${ANDROID_DIR}" && ./gradlew --no-daemon \
    :stt:testDebugUnitTest -Pstt.requireWebParity=true \
    :recorder:testDebugUnitTest \
    :voicenotes:testDebugUnitTest \
    :transcription:testDebugUnitTest \
    :corrections:testDebugUnitTest ) \
  || abort "GATE 2 failed — the JVM suite is not green across :stt/:recorder/:voicenotes/ \
:transcription/:corrections with parity in hard mode on :stt. If the failure is ErrorCodeParityTest \
reporting a MISSING web/src/errors.ts, this script is being run from somewhere that is not the \
mono-repo; a release cannot be cut from the mirror"

echo "release-android: GATE 2 — scripts/check-error-code-parity.sh (three-way, repo root) …"
( cd "${REPO_ROOT}" && bash scripts/check-error-code-parity.sh ) \
  || abort "GATE 2 failed — the error-code taxonomy has drifted across Python/TypeScript/Kotlin"

# The two gates below run with every override and test-input variable stripped from the child
# environment and with no fixture flag: the release scores the committed fixtures, never whatever a
# stale or hostile shell points at, and a stray variable cannot block the gate either.
echo "release-android: GATE 2 - scripts/check-wire-frame-parity.sh (Python/TypeScript/Kotlin final-frame golden fixture, repo root) ..."
( cd "${REPO_ROOT}" && env -u WIRE_FIXTURE_SERVER -u WIRE_FIXTURE_WEB -u WIRE_FIXTURE_ANDROID -u WIRE_GOLDEN_FIXTURE -u CORRECTION_FIXTURE_WEB -u CORRECTION_FIXTURE_ANDROID bash scripts/check-wire-frame-parity.sh ) || abort "GATE 2 failed - the owned wire protocol's final frame has drifted across Python/TypeScript/Kotlin"

echo "release-android: GATE 2 - scripts/check-correction-fixture-parity.sh (correction golden vectors, repo root) ..."
( cd "${REPO_ROOT}" && env -u WIRE_FIXTURE_SERVER -u WIRE_FIXTURE_WEB -u WIRE_FIXTURE_ANDROID -u WIRE_GOLDEN_FIXTURE -u CORRECTION_FIXTURE_WEB -u CORRECTION_FIXTURE_ANDROID bash scripts/check-correction-fixture-parity.sh ) || abort "GATE 2 failed - the correction golden-vector fixture copies have drifted between web and Android"

# --- GATE 3: dry-run the literal JitPack build against the actual split tree --------------------
echo "release-android: GATE 3 — git subtree split --prefix=android -b ${SPLIT_BRANCH} …"
git -C "${REPO_ROOT}" branch -D "${SPLIT_BRANCH}" >/dev/null 2>&1 || true
git -C "${REPO_ROOT}" subtree split --prefix=android -b "${SPLIT_BRANCH}" \
  || abort "GATE 3 failed — git subtree split could not produce ${SPLIT_BRANCH}"

TMP="$(mktemp -d)"
MIRROR="${TMP}/mirror"
git clone -q --branch "${SPLIT_BRANCH}" --no-local "${REPO_ROOT}" "${MIRROR}" \
  || abort "GATE 3 failed — could not clone the split branch"

[ -x "${MIRROR}/gradlew" ] \
  || abort "GATE 3 failed — the Gradle wrapper is absent or not executable in the split tree \
(Pitfall 8: a file above the split prefix does not exist for JitPack)"
[ -f "${MIRROR}/jitpack.yml" ] \
  || abort "GATE 3 failed — jitpack.yml is absent from the split tree; JitPack would not know how \
to build the mirror"

# --- GATE 3b: leak check on the split tree, before anything is pushed --------------------------
#
# ORDERING IS LOAD-BEARING, and was corrected after a real false positive. This runs on the
# PRISTINE clone — after the clone, before GATE 3's dry-run build writes anything into it. The
# first version of this gate ran after the build and scanned the FILESYSTEM, so it aborted on
# `stt/build/intermediates/.../merger.xml` and AGP's two manifest-merger reports, which embed the
# absolute source path of the machine that produced them. Those files are gitignored build outputs
# that `git push` never sends, so that was a false alarm — but it established the right rule:
#
#     THE LEAK SURFACE IS TRACKED CONTENT, BECAUSE TRACKED CONTENT IS WHAT GETS PUSHED.
#
# So every check below is scoped to tracked paths (`git ls-files`) or tracked content
# (`git grep`), never to a filesystem walk. Running before the build is then a free bonus: a leak
# aborts the release in seconds instead of after a full Android build.
echo "release-android: GATE 3b — leak check on the pristine split tree …"

# Directory checks run against TRACKED PATHS, not file contents. A content grep for these names
# would match this very script (which necessarily names what it forbids) and abort on a clean tree
# — the same false-positive shape that already bit a grep gate in this phase once.
leaked_paths="$(git -C "${MIRROR}" ls-files | grep -E '^(\.planning/|server/)' || true)"
[ -z "${leaked_paths}" ] || abort "GATE 3b failed — the split tree carries private mono-repo \
paths, which would become world-readable:
${leaked_paths}"

leaked_localprops="$(git -C "${MIRROR}" ls-files | grep -E '(^|/)local\.properties$' || true)"
[ -z "${leaked_localprops}" ] || abort "GATE 3b failed — local.properties is tracked in the split \
tree (threat T-05-06). It leaks an absolute SDK path from a development machine and JitPack sets \
ANDROID_HOME itself, so it is never needed:
${leaked_localprops}"

# Assembled as a regex, never written as a literal: writing the private hostname here would BE the
# leak, and would also make this check match itself. The generic shape catches any tailnet host,
# not merely the one this project happens to use.
TAILNET_RE='[a-z0-9-]+\.[a-z0-9-]+\.ts[.]net'
leaked_tailnet="$(git -C "${MIRROR}" grep -InE "${TAILNET_RE}" -- . || true)"
[ -z "${leaked_tailnet}" ] || abort "GATE 3b failed — a private tailnet hostname appears in the \
split tree:
${leaked_tailnet}"

# Development-machine paths: `android/CLAUDE.md` §5 puts this invariant on the four published
# documents; there is no reason the rest of the mirror should be held to a lower standard.
DEVPATH_RE='(/home/|/Users/)[A-Za-z0-9._-]+/'
leaked_devpath="$(git -C "${MIRROR}" grep -InE "${DEVPATH_RE}" -- . || true)"
[ -z "${leaked_devpath}" ] || abort "GATE 3b failed — an absolute development-machine path appears \
in the split tree:
${leaked_devpath}"

echo "release-android: GATE 3b — clean: no .planning/, no server/, no local.properties, no tailnet \
hostname, no development-machine path."

# Extract the install commands from the MIRROR's own jitpack.yml so this gate can never drift from
# the file JitPack actually reads. Only lines whose first non-space character is a list dash are
# taken, so an indented comment inside the block is never mistaken for a command.
mapfile -t JITPACK_CMDS < <(
  awk '
    /^install:/            { inblock = 1; next }
    inblock && /^[^[:space:]]/ { inblock = 0 }
    inblock && /^[[:space:]]*-[[:space:]]*/ {
      sub(/^[[:space:]]*-[[:space:]]*/, "")
      print
    }
  ' "${MIRROR}/jitpack.yml"
)
[ "${#JITPACK_CMDS[@]}" -gt 0 ] \
  || abort "GATE 3 failed — jitpack.yml declares no install command"
printf '%s\n' "${JITPACK_CMDS[@]}" \
  | grep -qx './gradlew :stt:publishReleasePublicationToMavenLocal' \
  || abort "GATE 3 failed — jitpack.yml no longer runs \
':stt:publishReleasePublicationToMavenLocal'; the dry run would be exercising a different build \
than the one JitPack will run. Found: ${JITPACK_CMDS[*]}"

# Each published module's artifactId is the mirror repo name plus its own suffix (:stt == the
# mirror name itself; :recorder/:voicenotes/:transcription/:corrections append
# "-recorder"/"-voicenotes"/"-transcription"/"-corrections" — the same artifactId-derived-from-
# mirror-name rule the header comment documents, now applied to all five published modules instead
# of just the first). Computed here so GATE 3's existence assertions below can loop instead of
# hardcoding a single path (RESEARCH.md Pitfall 3).
ARTIFACT_IDS=("${MIRROR_REPO}" "${MIRROR_REPO}-recorder" "${MIRROR_REPO}-voicenotes" "${MIRROR_REPO}-transcription" "${MIRROR_REPO}-corrections")

# Publish into the REAL local maven repository, with no extra flags, because that is exactly what
# JitPack does — fidelity here is the whole point of the gate. Each coordinate directory is a cache
# of a locally built artifact, so clearing all three first is safe and makes the assertions below
# deterministic rather than passing on a stale artifact from an earlier run.
for artifact_id in "${ARTIFACT_IDS[@]}"; do
  rm -rf "${HOME}/.m2/repository/${GROUP_ID//.//}/${artifact_id}/${VERSION}"
done

for cmd in "${JITPACK_CMDS[@]}"; do
  echo "release-android: GATE 3 — running literal jitpack.yml command in the split clone: ${cmd}"
  ( cd "${MIRROR}" && eval "${cmd}" ) \
    || abort "GATE 3 failed — the literal JitPack command failed against the split tree. This is \
the failure that would otherwise have been discovered against an immutable tag. Read the output \
above: a missing wrapper, a file that did not survive the split, a module breaking configuration, \
or a wrong artifactId"
done

# The artifactId rule made mechanical, across all three published modules: each module's artifact
# must land under ITS OWN coordinate directory, and the sources jar must be there too — that is the
# AGP-8 `singleVariant("release") { withSourcesJar() }` opt-in, the single most common
# JitPack-Android publish failure. Looping here (rather than asserting :stt alone) is what closes
# RESEARCH.md Pitfall 3 for good: a wrong or missing :recorder/:voicenotes artifact now aborts the
# release before the tag exists, instead of silently shipping unverified.
for artifact_id in "${ARTIFACT_IDS[@]}"; do
  m2_dir="${HOME}/.m2/repository/${GROUP_ID//.//}/${artifact_id}/${VERSION}"
  [ -f "${m2_dir}/${artifact_id}-${VERSION}.aar" ] \
    || abort "GATE 3 failed — no ${artifact_id}-${VERSION}.aar under ${m2_dir}. The artifactId must \
equal that module's own published coordinate name"
  [ -f "${m2_dir}/${artifact_id}-${VERSION}-sources.jar" ] \
    || abort "GATE 3 failed — no sources jar was published for ${artifact_id}. Restore \
android { publishing { singleVariant(\"release\") { withSourcesJar() } } } on that module"
  echo "release-android: GATE 3 — published from the split tree (${artifact_id}):"
  ls -1 "${m2_dir}"
done

# --- PREFLIGHT STOPS HERE ----------------------------------------------------------------------
if [ "${MODE}" = "preflight" ]; then
  echo
  echo "release-android: PREFLIGHT COMPLETE — every gate passed."
  echo "release-android: nothing was pushed and no tag was created."
  echo "release-android: to publish, re-run without the 'preflight' argument:"
  echo "    VERSION=${VERSION} ./scripts/release-android.sh"
  exit 0
fi

# --- PUSH + TAG on the PUBLIC MIRROR (never the mono-repo) -------------------------------------
# Everything below this line is irreversible. The mirror push is the point at which the split tree
# becomes world-readable, and the tag push is the point at which the coordinate string becomes
# permanent. Both target ${PUSH_URL} — the mirror — and nothing here ever pushes to the mono-repo's
# own remote.
echo "release-android: pushing ${SPLIT_BRANCH} → ${PUSH_URL} refs/heads/main"
git -C "${REPO_ROOT}" push "${PUSH_URL}" "${SPLIT_BRANCH}:refs/heads/main" \
  || abort "the mirror branch push failed — no tag was created"

echo "release-android: pushing ${SPLIT_BRANCH} → ${PUSH_URL} refs/tags/${TAG}"
git -C "${REPO_ROOT}" push "${PUSH_URL}" "${SPLIT_BRANCH}:refs/tags/${TAG}" \
  || abort "the tag push failed"

# (the throwaway split branch and the temp clone are removed by the EXIT trap)

cat <<CONSUMER

release-android: done — ${TAG} is published and IMMUTABLE.

JitPack builds lazily on first request and can take several minutes. Read the build log rather than
retrying blind:
  https://jitpack.io/com/github/${ORG}/${MIRROR_REPO}/${TAG}/build.log

Consumers add exactly two lines.

  settings.gradle.kts → dependencyResolutionManagement { repositories { … } }:
    maven { url = uri("https://jitpack.io") }

  the consuming module's build.gradle.kts:
    implementation("${GROUP_ID}:${MIRROR_REPO}:${TAG}")

Pin the tag or a commit SHA. Never a moving branch snapshot.
CONSUMER
