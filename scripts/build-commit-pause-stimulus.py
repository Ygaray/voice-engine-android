#!/usr/bin/env python3
"""Build the graded-gap stimulus for the Phase 26 commit-pause spike (decision D-01).

The spike asks one question of the platform recognizer: how long after the speaker stops does a
final commit, and does a shorter silence hint make it commit EARLY in the middle of a sentence?
Answering it needs audio whose silences are known exactly, so this builder produces one WAV and one
manifest of offsets:

* a LEAD-IN of silence;
* an INTER block: ten utterances separated by exact gaps of 600, 800, 1000, 1200 and 1500 ms (two
  utterances per gap value, the four short fluent fixtures cycling). A final that lands inside a gap
  says "the platform committed after at most this much silence";
* an INTRA block: eight clips, two per pause value 400, 600, 800 and 1000 ms. Each clip is a pinned
  excerpt of a long fixture cut at a pinned, measured phrase pause, and exactly the declared pause is
  inserted in place of that natural pause. A final BEFORE the clip's speech end is an early commit on
  a thinking pause. Every intra clip is followed by a 3000 ms separator so a final can be attributed
  to one clip without ambiguity (that is why inter and intra utterances are kept apart).

Every source clip is TRIMMED to its speech first (leading and trailing 10 ms frames more than 20 dB
below the clip's median active level are dropped, with a 20 ms guard), so a declared gap is the
silence between speech and a manifest `endMs` is a real speech end.

Why the intra pauses use two long fixtures only: measured at plan time (10 ms frames, 15 dB under
the median active level), none of the four short fluent fixtures has an internal pause of 250 ms or
more (the widest is 100 ms), so a "quietest frame" cut there would split a word or a phoneme. They
are inter-block material only; a source with no qualifying low-energy run is a REFUSAL, never a
blind cut. A quiet run is a verified low-energy interval, not a proven word boundary.

Why three variants: digital zero is not what a room sounds like, and an endpointer may decide
faster on digital silence than on a real microphone (assumption A5). `clean` is exact digital zero;
`n55` and `n45` add Gaussian noise at -55 and -45 dBFS RMS to the whole stream (fixed seed). The
manifest is identical across variants except for the variant name and the noise level.

Only the public, byte-pinned LibriSpeech fixtures are read. The builder refuses any path containing
the private-recording directory name before it reads a single sample: a person's voice must never
become spike audio.

Output is 16 kHz mono PCM16 WAV (the format the debug feed requires) plus the manifest JSON.

Usage (server venv: numpy and soundfile live there):
    server/.venv/bin/python -I android/scripts/build-commit-pause-stimulus.py \\
        --variant clean --out-wav out.wav --out-manifest out.json
    server/.venv/bin/python -I android/scripts/build-commit-pause-stimulus.py --variant clean --check-only
"""

from __future__ import annotations

import argparse
import json
import sys
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 16_000
FRAME = RATE // 100  # 10 ms analysis frame
SAMPLES_PER_MS = RATE // 1000

LEAD_IN_MS = 1_000
INTRA_LEAD_MS = 3_000  # silence between the last inter gap and the first intra clip
SEPARATOR_MS = 3_000  # silence after every intra clip

INTER_GAPS_MS = (600, 800, 1000, 1200, 1500)
INTRA_PAUSES_MS = (400, 600, 800, 1000)

ACTIVE_WITHIN_DB = 40.0  # "active" frames: within this many dB of the loudest frame
QUIET_UNDER_DB = 15.0  # a quiet run: frames more than this far under the median active level
TRIM_UNDER_DB = 20.0  # trim: edge frames more than this far under the median active level
TRIM_GUARD_FRAMES = 2  # 20 ms kept around the trimmed speech
MIN_SPLIT_MS = 250  # a split run must be at least this wide
SPLIT_TOLERANCE_MS = 40  # re-detected split bounds must sit this close to the pinned bounds
WIDTH_TOLERANCE_MS = 40  # measured speech-to-speech silence vs the declared pause
RMS_TOLERANCE_DB = 1.5  # noise self-check

NOISE_DBFS = {"clean": None, "n55": -55.0, "n45": -45.0}
NOISE_SEED = 26_001

PRIVATE_DIR_NAME = "own_voice"

# The four short fluent fixtures, ascending duration (2.1, 3.4, 3.8, 4.0 s before trimming).
# INTER-block material only: none has an internal pause of 250 ms or more.
SHORT_FLUENT = ("1089-134691-0000", "1089-134691-0007", "1284-1181-0002", "1284-1181-0000")

# PINNED intra sources (fixture time, measured at plan time with the detector below).
# Every excerpt bound lies inside a quiet run, so trimming leaves whole phrases.
PINNED_INTRA = {
    # phrase pauses at 2940 ms (420 ms wide) and 5260 ms (400 ms wide) after a 560 ms lead-in
    "1089-134691-0016": {"excerpt_ms": (540, 5280), "split_ms": (2940, 3360)},
    # phrase pauses at 4020 ms (410 ms wide) and 6850 ms (330 ms wide) after a 440 ms lead-in
    "1284-1181-0001": {"excerpt_ms": (420, 6870), "split_ms": (4020, 4430)},
}


class BuilderError(Exception):
    """A build refusal or failure (message is user-facing)."""


class PrivateAudioRefusal(BuilderError):
    """A path that names the private-recording directory."""


class NoQualifyingRun(ValueError):
    """No low-energy run qualifies as a split point (a refusal, never a blind cut)."""


# ---------------------------------------------------------------------------------------------
# analysis
# ---------------------------------------------------------------------------------------------


def frame_db(x: np.ndarray) -> np.ndarray:
    """RMS level (dBFS) of every complete 10 ms frame of an int16 signal."""
    n = len(x) // FRAME
    if n == 0:
        return np.zeros(0)
    f = x[: n * FRAME].astype(np.float64).reshape(n, FRAME) / 32768.0
    rms = np.sqrt((f * f).mean(axis=1))
    return 20.0 * np.log10(np.maximum(rms, 1e-9))


def active_median_db(db: np.ndarray) -> float:
    """Median level of the active frames (within ACTIVE_WITHIN_DB of the loudest)."""
    active = db > db.max() - ACTIVE_WITHIN_DB
    return float(np.median(db[active]))


def find_quiet_runs(x: np.ndarray, under_db: float = QUIET_UNDER_DB, min_ms: int = 0, internal_only: bool = False):
    """Return (median_active_db, [(start_ms, end_ms), ...]) of low-energy runs.

    A low-energy run is a stretch of 10 ms frames more than `under_db` under the median active level.
    `internal_only` drops runs that touch the first or last frame (lead-in and tail silence).
    """
    db = frame_db(x)
    if len(db) == 0:
        return 0.0, []
    med = active_median_db(db)
    low = db < med - under_db
    runs = []
    i = 0
    n = len(low)
    while i < n:
        if low[i]:
            j = i
            while j < n and low[j]:
                j += 1
            if not (internal_only and (i == 0 or j == n)) and (j - i) * 10 >= min_ms:
                runs.append((i * 10, j * 10))
            i = j
        else:
            i += 1
    return med, runs


def speech_frame_bounds(db: np.ndarray, med: float):
    """(first, last) frame index whose level is within TRIM_UNDER_DB of the median active level."""
    keep = np.nonzero(db >= med - TRIM_UNDER_DB)[0]
    if len(keep) == 0:
        raise BuilderError("source has no speech frames")
    return int(keep[0]), int(keep[-1])


def trim_speech(x: np.ndarray) -> np.ndarray:
    """Drop leading and trailing frames more than 20 dB under the median active level (20 ms guard)."""
    db = frame_db(x)
    first, last = speech_frame_bounds(db, active_median_db(db))
    f0 = max(0, first - TRIM_GUARD_FRAMES)
    f1 = min(len(db), last + 1 + TRIM_GUARD_FRAMES)
    return x[f0 * FRAME : f1 * FRAME]


def locate_split(fixture_id: str, x: np.ndarray):
    """Re-detect the pinned split run of a fixture and return its (start_ms, end_ms).

    Refuses (NoQualifyingRun) a fixture with no internal quiet run of at least MIN_SPLIT_MS, a fixture
    that is not pinned, and a pinned run that cannot be found within SPLIT_TOLERANCE_MS of its pin.
    """
    _, runs = find_quiet_runs(x, min_ms=MIN_SPLIT_MS, internal_only=True)
    if not runs:
        raise NoQualifyingRun(
            f"{fixture_id}: no qualifying low-energy run exists (need an internal run of at least "
            f"{MIN_SPLIT_MS} ms, more than {QUIET_UNDER_DB:g} dB under the median active level); refusing a blind cut"
        )
    pin = PINNED_INTRA.get(fixture_id)
    if pin is None:
        raise NoQualifyingRun(
            f"{fixture_id}: no qualifying low-energy run is pinned for this fixture; it is inter-block material only"
        )
    want_lo, want_hi = pin["split_ms"]
    for lo, hi in runs:
        if abs(lo - want_lo) <= SPLIT_TOLERANCE_MS and abs(hi - want_hi) <= SPLIT_TOLERANCE_MS:
            return lo, hi
    raise NoQualifyingRun(
        f"{fixture_id}: no qualifying low-energy run within {SPLIT_TOLERANCE_MS} ms of the pinned "
        f"{want_lo}..{want_hi} ms (found {runs})"
    )


def measure_quiet_width(samples: np.ndarray, pause_start_ms: int, pause_ms: int, clip_start_ms: int, clip_end_ms: int) -> int:
    """Width (ms) of the low-energy run that contains an inserted pause, measured on the written samples."""
    clip = samples[clip_start_ms * SAMPLES_PER_MS : clip_end_ms * SAMPLES_PER_MS]
    db = frame_db(clip)
    low = db < active_median_db(db) - QUIET_UNDER_DB
    first = (pause_start_ms - clip_start_ms) // 10
    left = first
    while left > 0 and low[left - 1]:
        left -= 1
    right = first + pause_ms // 10
    while right < len(low) and low[right]:
        right += 1
    return (right - left) * 10


# ---------------------------------------------------------------------------------------------
# assembly
# ---------------------------------------------------------------------------------------------


def refuse_private(path) -> None:
    if PRIVATE_DIR_NAME in str(path):
        raise PrivateAudioRefusal(f"refusing {path}: the path contains {PRIVATE_DIR_NAME}; a person's voice is never spike audio")


def read_fixture(path: Path) -> np.ndarray:
    refuse_private(path)
    try:
        data, rate = sf.read(str(path), dtype="int16")
    except Exception as exc:  # unreadable fixture
        raise BuilderError(f"{path.name}: cannot read fixture ({exc})") from exc
    if rate != RATE or data.ndim != 1:
        raise BuilderError(f"{path.name}: expected 16 kHz mono, got {rate} Hz with shape {data.shape}")
    return data


def zeros_ms(ms: int) -> np.ndarray:
    return np.zeros(ms * SAMPLES_PER_MS, dtype=np.int16)


def make_intra_clip(fixture_id: str, x: np.ndarray, pause_ms: int):
    """Return (clip, pause_start_sample_in_clip, split_run_ms) for a pinned excerpt with `pause_ms` inserted."""
    pin = PINNED_INTRA[fixture_id]
    lo_ms, hi_ms = locate_split(fixture_id, x)
    a_ms, b_ms = pin["excerpt_ms"]
    excerpt = x[a_ms * SAMPLES_PER_MS : b_ms * SAMPLES_PER_MS]
    db = frame_db(excerpt)
    med = active_median_db(db)
    first, last = speech_frame_bounds(db, med)
    s_frame = (lo_ms - a_ms) // 10
    e_frame = (hi_ms - a_ms) // 10
    pre_first = max(0, first - TRIM_GUARD_FRAMES)
    post_last = min(len(db), last + 1 + TRIM_GUARD_FRAMES)
    pre = excerpt[pre_first * FRAME : s_frame * FRAME]
    post = excerpt[e_frame * FRAME : post_last * FRAME]
    clip = np.concatenate([pre, zeros_ms(pause_ms), post])
    return clip, len(pre), hi_ms - lo_ms


def add_noise(clean: np.ndarray, dbfs: float) -> np.ndarray:
    rng = np.random.default_rng(NOISE_SEED)
    sigma = (10.0 ** (dbfs / 20.0)) * 32768.0
    noisy = clean.astype(np.float64) + rng.standard_normal(len(clean)) * sigma
    return np.clip(np.rint(noisy), -32768, 32767).astype(np.int16)


def build(fixtures_dir, variant: str):
    """Build the stimulus in memory. Returns (int16 samples, manifest dict)."""
    fixtures_dir = Path(fixtures_dir)
    refuse_private(fixtures_dir)
    if variant not in NOISE_DBFS:
        raise BuilderError(f"unknown variant {variant!r}; choose one of {sorted(NOISE_DBFS)}")
    ids = list(SHORT_FLUENT) + sorted(PINNED_INTRA)
    paths = {fid: fixtures_dir / f"{fid}.flac" for fid in ids}
    for path in paths.values():
        refuse_private(path)
    audio = {fid: read_fixture(paths[fid]) for fid in ids}

    chunks = [zeros_ms(LEAD_IN_MS)]
    utterances = []
    t = LEAD_IN_MS
    for i in range(10):
        fid = SHORT_FLUENT[i % len(SHORT_FLUENT)]
        clip = trim_speech(audio[fid])
        dur = len(clip) // SAMPLES_PER_MS
        gap = INTER_GAPS_MS[i % len(INTER_GAPS_MS)]
        utterances.append({"index": i, "source": fid, "pauseKind": "inter", "startMs": t, "endMs": t + dur, "gapAfterMs": gap})
        chunks += [clip, zeros_ms(gap)]
        t += dur + gap
    chunks.append(zeros_ms(INTRA_LEAD_MS))
    t += INTRA_LEAD_MS

    for pause in INTRA_PAUSES_MS:
        for fid in sorted(PINNED_INTRA):
            clip, pause_at, split_ms = make_intra_clip(fid, audio[fid], pause)
            dur = len(clip) // SAMPLES_PER_MS
            utterances.append({
                "index": len(utterances), "source": fid, "pauseKind": "intra", "startMs": t, "endMs": t + dur,
                "gapAfterMs": SEPARATOR_MS, "intraPauseStartMs": t + pause_at // SAMPLES_PER_MS,
                "intraPauseMs": pause, "splitRunMs": split_ms,
            })
            chunks += [clip, zeros_ms(SEPARATOR_MS)]
            t += dur + SEPARATOR_MS

    clean = np.concatenate(chunks).astype(np.int16)
    noise = NOISE_DBFS[variant]
    manifest = {
        "schema": "commit-pause-stimulus/1",
        "variant": variant,
        "noiseDbfs": noise,
        "rate": RATE,
        "leadInMs": LEAD_IN_MS,
        "intraLeadMs": INTRA_LEAD_MS,
        "totalMs": len(clean) // SAMPLES_PER_MS,
        "utterances": utterances,
    }
    problems = self_check(clean, manifest, "clean") + split_width_problems(clean, manifest)
    if problems:
        raise BuilderError("self-check failed on the clean build: " + "; ".join(problems))
    samples = clean if noise is None else add_noise(clean, noise)
    return samples, manifest


# ---------------------------------------------------------------------------------------------
# self-check
# ---------------------------------------------------------------------------------------------


def declared_silences(manifest: dict):
    """Every region the manifest declares to be silent: (label, start_ms, end_ms)."""
    regions = [("lead-in", 0, manifest["leadInMs"])]
    inter = [u for u in manifest["utterances"] if u["pauseKind"] == "inter"]
    for u in manifest["utterances"]:
        regions.append((f"gap after #{u['index']}", u["endMs"], u["endMs"] + u["gapAfterMs"]))
        if u["pauseKind"] == "intra":
            regions.append((f"pause in #{u['index']}", u["intraPauseStartMs"], u["intraPauseStartMs"] + u["intraPauseMs"]))
    if inter:
        end = inter[-1]["endMs"] + inter[-1]["gapAfterMs"]
        regions.append(("intra lead", end, end + manifest["intraLeadMs"]))
    return regions


def self_check(samples: np.ndarray, manifest: dict, variant: str) -> list:
    """Recompute every declared gap from the samples. Returns a list of problems (empty when sound).

    clean: each region is exact digital zero and the zero run ends within 2 ms of its declared end.
    noisy: each region's measured RMS is within 1.5 dB of the requested level. This is an RMS check;
    a per-sample peak is never compared against an RMS level (Gaussian peaks sit ~12 dB above RMS).
    """
    problems = []
    if len(samples) != manifest["totalMs"] * SAMPLES_PER_MS:
        problems.append(f"length {len(samples)} != totalMs {manifest['totalMs']} ms")
        return problems
    target = NOISE_DBFS[variant]
    regions = declared_silences(manifest)
    starts = {lo for _, lo, _ in regions}
    for label, lo, hi in regions:
        seg = samples[lo * SAMPLES_PER_MS : hi * SAMPLES_PER_MS]
        if target is None:
            if np.any(seg != 0):
                problems.append(f"{label}: {lo}..{hi} ms is not exact digital zero")
                continue
            tail = samples[hi * SAMPLES_PER_MS : hi * SAMPLES_PER_MS + 32]
            nonzero = np.nonzero(tail)[0]
            if hi not in starts and len(tail) == 32 and len(nonzero) == 0:
                problems.append(f"{label}: zero run extends past the declared end {hi} ms")
        else:
            rms = float(np.sqrt(np.mean((seg.astype(np.float64) / 32768.0) ** 2)))
            level = 20.0 * np.log10(max(rms, 1e-12))
            if abs(level - target) > RMS_TOLERANCE_DB:
                problems.append(f"{label}: measured {level:.2f} dBFS RMS vs requested {target:g}")
    for u in manifest["utterances"]:
        seg = samples[u["startMs"] * SAMPLES_PER_MS : u["endMs"] * SAMPLES_PER_MS]
        if len(seg) == 0 or int(np.abs(seg.astype(np.int32)).max()) < 200:
            problems.append(f"utterance #{u['index']} has no speech energy")
    return problems


def split_width_problems(clean: np.ndarray, manifest: dict) -> list:
    """The inserted pause must be the true speech-to-speech silence at the split (within 40 ms)."""
    problems = []
    for u in manifest["utterances"]:
        if u["pauseKind"] != "intra":
            continue
        width = measure_quiet_width(clean, u["intraPauseStartMs"], u["intraPauseMs"], u["startMs"], u["endMs"])
        if abs(width - u["intraPauseMs"]) > WIDTH_TOLERANCE_MS:
            problems.append(f"utterance #{u['index']}: quiet width {width} ms vs declared pause {u['intraPauseMs']} ms")
    return problems


# ---------------------------------------------------------------------------------------------
# output
# ---------------------------------------------------------------------------------------------


def write_wav(path: Path, samples: np.ndarray) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.astype("<i2").tobytes())


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        if (w.getnchannels(), w.getsampwidth(), w.getframerate()) != (1, 2, RATE):
            raise BuilderError(f"{path}: written WAV is not 16 kHz mono PCM16")
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.int16)


def main(argv=None) -> int:
    default_fixtures = Path(__file__).resolve().parents[2] / "server" / "fixtures" / "librispeech"
    p = argparse.ArgumentParser(description="Build the graded-gap commit-pause stimulus (Phase 26, D-01).")
    p.add_argument("--fixtures-dir", default=str(default_fixtures), help="LibriSpeech fixtures (default: the repo's)")
    p.add_argument("--variant", choices=sorted(NOISE_DBFS), required=True, help="clean | n55 | n45 (noise floor RMS dBFS)")
    p.add_argument("--out-wav", help="output 16 kHz mono PCM16 WAV")
    p.add_argument("--out-manifest", help="output manifest JSON")
    p.add_argument("--check-only", action="store_true", help="build in memory, run the self-check, write nothing")
    args = p.parse_args(argv)
    if not args.check_only and not (args.out_wav and args.out_manifest):
        p.error("--out-wav and --out-manifest are required unless --check-only is given")
    try:
        samples, manifest = build(args.fixtures_dir, args.variant)
        problems = self_check(samples, manifest, args.variant)
        if problems:
            raise BuilderError("self-check failed: " + "; ".join(problems))
        if not args.check_only:
            wav_path, manifest_path = Path(args.out_wav), Path(args.out_manifest)
            write_wav(wav_path, samples)
            manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            problems = self_check(read_wav(wav_path), manifest, args.variant)
            if problems:
                raise BuilderError("self-check failed on the written file: " + "; ".join(problems))
    except PrivateAudioRefusal as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except BuilderError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except NoQualifyingRun as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"ok: variant={args.variant} totalMs={manifest['totalMs']} utterances={len(manifest['utterances'])}"
          f"{' (check only, nothing written)' if args.check_only else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
