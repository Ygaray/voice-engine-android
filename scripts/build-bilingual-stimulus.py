#!/usr/bin/env python3
"""Stitch the bilingual EN<->ES androidTest stimulus for the language-switch probe (plan 149-02).

Primary source: plan 149-01's already-committed switch-granularity corpus at
``server/fixtures/bilingual_en_es/`` — real human speech (LibriSpeech + OpenSLR), already 16 kHz
mono 16-bit PCM, already carrying verbatim reference text. Two inter-sentence clips
(``intersentence_en_es_01``, ``intersentence_en_es_02``) are selected and concatenated so the
composite alternates EN -> ES -> EN -> ES across every boundary. This guarantees the task can
complete regardless of what TTS voices are installed on the device.

Best-effort addition: single-voice mixed-language utterances from
``BilingualStimulusGeneratorTest`` (pass ``--tts-parts <pulled-dir>``), appended after the spliced
material when the pulled ``tts-availability.json`` says the es-US Google TTS voice was present. If
absent (or ``--tts-parts`` is omitted), the stimulus is built from the spliced clips alone — a
documented gap, not a failure.

**The gaps are load-bearing, not padding**, exactly like ``ProbeStimulus.GAP_MS``: the digital
silence the source clips already carry between their two utterances (1000 ms — see
``server/fixtures/bilingual_en_es/README.md``) is a real utterance boundary, but this probe wants
every boundary to be unambiguous to a language-switch mechanism, so any silence run at or above
``MIN_DETECTABLE_GAP_MS`` is padded up to ``GAP_MS`` (3000 ms, this script's own floor — NOT
``build-stimulus.py``'s convention, which is a coincidence of both scripts independently choosing
the same value the native probe corpus already established). ``build-stimulus.py`` is reused for
its WAV-reading and resampling helpers ONLY (loaded via ``importlib`` because its filename is
hyphenated and cannot be imported as a normal module) — it is never modified, and its
``MIN_DURATION_S = 110.0`` floor is irrelevant here; this script defines its own, much shorter
floor because a 110 s bilingual stimulus would make the instrumented run needlessly slow.

Usage:
    python3 build-bilingual-stimulus.py [--tts-parts <dir>] <output.wav>

Only the standard library plus numpy — both already present on the host; no ffmpeg/sox needed,
matching the whole probe suite's zero-native-audio-tooling constraint.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import wave
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent
SERVER_ROOT = ANDROID_ROOT.parent / "server"
BILINGUAL_FIXTURES = SERVER_ROOT / "fixtures" / "bilingual_en_es"

# This script's own parameters — deliberately NOT build-stimulus.py's (see module docstring).
TARGET_RATE = 16_000
GAP_MS = 3_000
LEAD_MS = 500
MIN_DURATION_S = 15.0

# A silence run at or above this length is treated as a deliberate utterance/language boundary and
# padded up to GAP_MS. Well above natural micro-pauses inside continuous speech (which are quiet,
# not exactly zero, and rarely exceed ~150 ms), well below the source clips' own 1000 ms boundary
# gap, so it reliably catches exactly the boundaries make_bilingual_fixtures.py inserted.
MIN_DETECTABLE_GAP_MS = 300
ZERO_THRESHOLD = 1e-4

# The two inter-sentence composites selected as primary source, in playback order. Each is
# EN-then-ES; the source composites are concatenated back-to-back with GAP_MS between them (ES
# from clip 1 -> EN from clip 2 is itself a language boundary), so the assembled sequence
# alternates EN -> ES -> EN -> ES across every one of its three boundaries. intrasentence_seam_01
# is deliberately excluded — it has NO gap at its internal boundary by design (a negative control
# for a different measurement), which would violate this stimulus's own load-bearing-silence
# requirement.
PRIMARY_CLIPS = ["intersentence_en_es_01", "intersentence_en_es_02"]

# Utterance text/language metadata for each primary clip, in playback order — must match
# BilingualStimulus.kt exactly; sourced verbatim from
# server/fixtures/bilingual_en_es/README.md's "Source clip ids" table.
PRIMARY_UTTERANCES: dict[str, list[tuple[str, str]]] = {
    "intersentence_en_es_01": [
        ("HE COULD WAIT NO LONGER", "en-US"),
        ("Los niños tienen mucha imaginación", "es-US"),
    ],
    "intersentence_en_es_02": [
        ("OJO EXAMINED THIS CURIOUS CONTRIVANCE WITH WONDER", "en-US"),
        ("¿Cuál es el hospital más cercano?", "es-US"),
    ],
}

# Text of the best-effort single-voice TTS utterances, in the same order
# BilingualStimulusGeneratorTest synthesises them. Both are spoken by the es-US voice.
TTS_UTTERANCES = [
    ("Voy a hacer un backup del archivo", "es-US"),
    ("Send me el link por WhatsApp", "es-US"),
]


def _load_build_stimulus_helpers():
    """Load read_wav_mono_f32/resample from the hyphenated build-stimulus.py without modifying it."""
    spec = importlib.util.spec_from_file_location(
        "_build_stimulus", SCRIPT_DIR / "build-stimulus.py"
    )
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.read_wav_mono_f32, module.resample


def find_runs(mask: np.ndarray) -> list[tuple[int, int]]:
    """Contiguous (start, end) index pairs where `mask` is True."""
    if mask.size == 0:
        return []
    diff = np.diff(mask.astype(np.int8))
    starts = np.where(diff == 1)[0] + 1
    ends = np.where(diff == -1)[0] + 1
    if mask[0]:
        starts = np.r_[0, starts]
    if mask[-1]:
        ends = np.r_[ends, mask.size]
    return list(zip(starts.tolist(), ends.tolist()))


def pad_internal_gaps(signal: np.ndarray, sample_rate: int, floor_ms: int) -> tuple[np.ndarray, int]:
    """Pad every digital-silence run >= MIN_DETECTABLE_GAP_MS up to `floor_ms`.

    Returns (padded_signal, gaps_padded_count). Runs shorter than the detection floor (natural
    micro-pauses inside continuous speech) are left untouched.
    """
    min_run = int(sample_rate * MIN_DETECTABLE_GAP_MS / 1000)
    floor_len = int(sample_rate * floor_ms / 1000)
    mask = np.abs(signal) <= ZERO_THRESHOLD
    long_runs = [(s, e) for s, e in find_runs(mask) if (e - s) >= min_run]
    if not long_runs:
        return signal, 0

    chunks: list[np.ndarray] = []
    prev_end = 0
    padded = 0
    for start, end in long_runs:
        chunks.append(signal[prev_end:start])
        run_len = end - start
        pad_len = max(run_len, floor_len)
        chunks.append(np.zeros(pad_len, dtype=signal.dtype))
        if pad_len > run_len:
            padded += 1
        prev_end = end
    chunks.append(signal[prev_end:])
    return np.concatenate(chunks), padded


def peak_normalise(data: np.ndarray, target_peak: float = 0.89) -> np.ndarray:
    """Same per-part peak-normalisation convention as build-stimulus.py."""
    peak = float(np.max(np.abs(data))) if data.size else 0.0
    if peak < 0.01:
        raise SystemExit(f"clip is effectively silent (peak {peak:.4f})")
    return data * (target_peak / peak)


def load_primary_clip(name: str, read_wav_mono_f32) -> np.ndarray:
    wav_path = BILINGUAL_FIXTURES / f"{name}.wav"
    if not wav_path.is_file():
        raise SystemExit(f"missing primary source clip {wav_path}")
    data, rate = read_wav_mono_f32(wav_path)
    if rate != TARGET_RATE:
        raise SystemExit(f"{wav_path}: expected {TARGET_RATE} Hz, got {rate} Hz")
    return data


def load_tts_parts(tts_dir: Path, read_wav_mono_f32, resample) -> list[np.ndarray]:
    """Load the best-effort TTS parts if the availability marker says the voice was present."""
    availability_path = tts_dir / "tts-availability.json"
    if not availability_path.is_file():
        print(f"  no tts-availability.json found in {tts_dir} — skipping TTS parts")
        return []
    availability = json.loads(availability_path.read_text(encoding="utf-8"))
    if not availability.get("spanishVoiceAvailable"):
        print(
            "  tts-availability.json reports spanishVoiceAvailable=false "
            f"(setLanguageResult={availability.get('setLanguageResult')}) — skipping TTS parts, "
            "building from spliced clips alone"
        )
        return []

    parts: list[np.ndarray] = []
    for i in range(len(TTS_UTTERANCES)):
        part_path = tts_dir / f"codeswitch-{i:02d}.wav"
        if not part_path.is_file():
            print(f"  expected TTS part {part_path} not found — skipping remaining TTS parts")
            break
        data, rate = read_wav_mono_f32(part_path)
        data = resample(data, rate, TARGET_RATE)
        peak = float(np.max(np.abs(data))) if data.size else 0.0
        print(f"  {part_path.name}: {data.size / TARGET_RATE:6.2f}s  src={rate}Hz  peak={peak:.3f}")
        if peak < 0.01:
            raise SystemExit(f"{part_path}: effectively silent (peak {peak:.4f}) — synthesis failed")
        parts.append(peak_normalise(data))
    return parts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="output WAV path")
    parser.add_argument(
        "--tts-parts",
        type=Path,
        default=None,
        help="directory pulled from BilingualStimulusGeneratorTest's output "
        "(bilingual-stimulus-parts/), containing tts-availability.json + codeswitch-*.wav",
    )
    args = parser.parse_args()

    read_wav_mono_f32, resample = _load_build_stimulus_helpers()

    gap = np.zeros(int(TARGET_RATE * GAP_MS / 1000), dtype=np.float32)
    lead = np.zeros(int(TARGET_RATE * LEAD_MS / 1000), dtype=np.float32)

    chunks: list[np.ndarray] = [lead]
    utterances: list[tuple[str, str]] = []
    total_gaps_padded = 0

    for i, name in enumerate(PRIMARY_CLIPS):
        data = load_primary_clip(name, read_wav_mono_f32)
        data, padded = pad_internal_gaps(data, TARGET_RATE, GAP_MS)
        total_gaps_padded += padded
        data = peak_normalise(data)
        print(
            f"  {name}: {data.size / TARGET_RATE:6.2f}s  internal gaps padded to {GAP_MS}ms: {padded}"
        )
        chunks.append(data)
        utterances.extend(PRIMARY_UTTERANCES[name])
        if i != len(PRIMARY_CLIPS) - 1:
            chunks.append(gap)

    tts_parts: list[np.ndarray] = []
    if args.tts_parts is not None:
        tts_parts = load_tts_parts(args.tts_parts, read_wav_mono_f32, resample)

    for i, part in enumerate(tts_parts):
        chunks.append(gap)
        chunks.append(part)
        utterances.append(TTS_UTTERANCES[i])

    chunks.append(lead)

    signal = np.concatenate(chunks)
    duration = signal.size / TARGET_RATE
    if duration < MIN_DURATION_S:
        raise SystemExit(f"stimulus is {duration:.1f}s, need >= {MIN_DURATION_S}s")

    pcm = np.clip(signal * 32767.0, -32768, 32767).astype("<i2")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.output), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(TARGET_RATE)
        w.writeframes(pcm.tobytes())

    print(
        f"wrote {args.output} — {duration:.1f}s, {TARGET_RATE} Hz mono 16-bit, "
        f"{len(utterances)} utterances, {total_gaps_padded} internal gap(s) padded, "
        f"{len(tts_parts)} TTS part(s) included"
    )
    print("Utterance sequence (for BilingualStimulus.kt cross-check):")
    for text, tag in utterances:
        print(f"  [{tag}] {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
