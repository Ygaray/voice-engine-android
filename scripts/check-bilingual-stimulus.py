#!/usr/bin/env python3
"""Re-runnable format + metadata gate for the committed bilingual androidTest stimulus.

Asserts the built WAV is 16 kHz mono 16-bit PCM above a minimum duration, and that
BilingualStimulus.kt records region-qualified BCP-47 tags for both languages. Exists so a later
regeneration of build-bilingual-stimulus.py cannot silently produce a malformed asset, and a
metadata/audio mismatch is not just an eyeball check.

Usage: python3 check-bilingual-stimulus.py
"""

from __future__ import annotations

import re
import sys
import wave
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_ROOT = SCRIPT_DIR.parent
WAV_PATH = ANDROID_ROOT / "stt" / "src" / "androidTest" / "assets" / "bilingual-stimulus-16k-mono.wav"
KT_PATH = (
    ANDROID_ROOT
    / "stt"
    / "src"
    / "androidTest"
    / "kotlin"
    / "io"
    / "github"
    / "ygaray"
    / "sttengine"
    / "probe"
    / "BilingualStimulus.kt"
)

MIN_DURATION_S = 12.0
EXPECTED_RATE = 16_000
EXPECTED_CHANNELS = 1
EXPECTED_SAMPWIDTH = 2


def check_wav() -> None:
    if not WAV_PATH.is_file():
        raise SystemExit(f"missing {WAV_PATH}")
    with wave.open(str(WAV_PATH), "rb") as w:
        channels = w.getnchannels()
        width = w.getsampwidth()
        rate = w.getframerate()
        duration = w.getnframes() / rate
    assert channels == EXPECTED_CHANNELS, f"expected mono, got {channels} channels"
    assert width == EXPECTED_SAMPWIDTH, f"expected 16-bit PCM, got {width * 8}-bit"
    assert rate == EXPECTED_RATE, f"expected {EXPECTED_RATE} Hz, got {rate} Hz"
    assert duration > MIN_DURATION_S, f"stimulus is {duration:.1f}s, need > {MIN_DURATION_S}s"
    print(f"WAV ok: {channels}ch {width * 8}-bit {rate}Hz {duration:.1f}s")


def check_metadata() -> None:
    if not KT_PATH.is_file():
        raise SystemExit(f"missing {KT_PATH}")
    text = KT_PATH.read_text(encoding="utf-8")
    assert "en-US" in text, "BilingualStimulus.kt does not record an en-US tag"
    assert "es-US" in text, "BilingualStimulus.kt does not record an es-US tag"
    # Region-qualified convention (DICT-03): reject a bare "es" tag masquerading as the language.
    bare_es = re.findall(r'"es"', text)
    assert not bare_es, 'found bare "es" tag(s) — must be region-qualified (es-US), never bare "es"'
    print('BilingualStimulus.kt ok: en-US and es-US tags present, no bare "es" tag')


def main() -> int:
    check_wav()
    check_metadata()
    print("check-bilingual-stimulus: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
