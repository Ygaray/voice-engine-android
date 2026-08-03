#!/usr/bin/env python3
"""Stitch the per-utterance TTS parts into the committed probe stimulus.

Second half of the two-step stimulus pipeline that `StimulusGeneratorTest` starts. The generator
synthesises one WAV per utterance on the tester (Google TTS, whatever native rate it prefers); this
script resamples each to 16 kHz mono 16-bit PCM and concatenates them with exact digital silence
between, producing `probe-stimulus-16k-mono.wav`.

The inter-utterance silence is the point, not padding: `EXTRA_SEGMENTED_SESSION`'s discriminator
needs multi-second pauses inside ONE session, and TextToSpeech will not produce them.

Usage:
    python3 build-stimulus.py <parts-dir> <output.wav>

Only the standard library plus numpy — both already present on the host; no ffmpeg/sox needed.
"""

from __future__ import annotations

import sys
import wave
from pathlib import Path

import numpy as np

TARGET_RATE = 16_000
GAP_MS = 3_000
LEAD_MS = 500
MIN_DURATION_S = 110.0


def read_wav_mono_f32(path: Path) -> tuple[np.ndarray, int]:
    """Read a PCM WAV as float32 in [-1, 1], downmixed to mono."""
    with wave.open(str(path), "rb") as w:
        channels = w.getnchannels()
        width = w.getsampwidth()
        rate = w.getframerate()
        frames = w.readframes(w.getnframes())

    if width == 2:
        data = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 1:
        # 8-bit PCM WAV is unsigned with a 128 bias.
        data = (np.frombuffer(frames, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif width == 4:
        data = np.frombuffer(frames, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise SystemExit(f"{path}: unsupported sample width {width} bytes")

    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data, rate


def resample(data: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    """Linear resample. Adequate here: the source is band-limited synthetic speech and the
    destination rate is the one the recognizer wants, so no anti-alias filtering is warranted."""
    if src_rate == dst_rate or data.size == 0:
        return data
    duration = data.size / src_rate
    dst_n = int(round(duration * dst_rate))
    src_x = np.arange(data.size, dtype=np.float64) / src_rate
    dst_x = np.arange(dst_n, dtype=np.float64) / dst_rate
    return np.interp(dst_x, src_x, data).astype(np.float32)


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    parts_dir = Path(sys.argv[1])
    out_path = Path(sys.argv[2])

    parts = sorted(parts_dir.glob("utt-*.wav"))
    if not parts:
        raise SystemExit(f"no utt-*.wav parts found in {parts_dir}")

    gap = np.zeros(int(TARGET_RATE * GAP_MS / 1000), dtype=np.float32)
    lead = np.zeros(int(TARGET_RATE * LEAD_MS / 1000), dtype=np.float32)

    chunks: list[np.ndarray] = [lead]
    for i, part in enumerate(parts):
        data, rate = read_wav_mono_f32(part)
        data = resample(data, rate, TARGET_RATE)
        peak = float(np.max(np.abs(data))) if data.size else 0.0
        print(f"  {part.name}: {data.size / TARGET_RATE:6.2f}s  src={rate}Hz  peak={peak:.3f}")
        if peak < 0.01:
            raise SystemExit(f"{part}: effectively silent (peak {peak:.4f}) — synthesis failed")
        # Normalise each part to a common peak so no single utterance is quieter than the rest
        # over the speaker-loopback path, where headroom is the whole game.
        chunks.append(data * (0.89 / peak))
        if i != len(parts) - 1:
            chunks.append(gap)
    chunks.append(lead)

    signal = np.concatenate(chunks)
    duration = signal.size / TARGET_RATE
    if duration < MIN_DURATION_S:
        raise SystemExit(f"stimulus is {duration:.1f}s, need >= {MIN_DURATION_S}s")

    pcm = np.clip(signal * 32767.0, -32768, 32767).astype("<i2")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(TARGET_RATE)
        w.writeframes(pcm.tobytes())

    print(f"wrote {out_path} — {duration:.1f}s, {TARGET_RATE} Hz mono 16-bit, {len(parts)} utterances")
    return 0


if __name__ == "__main__":
    sys.exit(main())
