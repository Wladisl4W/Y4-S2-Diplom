#!/usr/bin/env python3
"""Reproducible smoke benchmark, not a singing-quality score.

Requires Python 3.10, basic-pitch, audio-separator, ffmpeg and a local model.
Generates five synthetic notes over two accompaniment tones in a temporary file.
"""

import contextlib
import io
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import wave

from audio_separator.separator import Separator
from basic_pitch.inference import predict

MODEL = "UVR_MDXNET_KARA_2.onnx"
NOTES = [60, 62, 64, 65, 67]
RATE = 22050


def make_audio(path: Path) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(RATE)
        for n in range(RATE * len(NOTES)):
            second = n // RATE
            hz = 440 * 2 ** ((NOTES[second] - 69) / 12)
            envelope = min(1, (n % RATE) / 1000, (RATE - n % RATE) / 1000)
            voice = 0.3 * envelope * math.sin(2 * math.pi * hz * n / RATE)
            backing = 0.13 * math.sin(2 * math.pi * 130.81 * n / RATE)
            backing += 0.10 * math.sin(2 * math.pi * 196 * n / RATE)
            value = round(32767 * (voice + backing))
            output.writeframesraw(struct.pack("<hh", value, value))


def notes_from(path: Path):
    with contextlib.redirect_stdout(io.StringIO()):
        _, _, events = predict(str(path))
    return sorted((round(float(a), 2), round(float(b), 2), int(n), round(float(c), 2))
                  for a, b, n, c, *_ in events)


def evaluate(events):
    correct = sum(any(a <= i + 0.5 <= b and note == expected
                      for a, b, note, _ in events)
                  for i, expected in enumerate(NOTES))
    extras = sum(note not in NOTES for _, _, note, _ in events)
    return {"correct_midpoints": correct, "expected_notes": len(NOTES),
            "other_pitch_events": extras, "total_events": len(events)}


def main():
    model_dir = Path.home() / ".local/share/intonation-trainer/models"
    with tempfile.TemporaryDirectory(prefix="intonation-research-") as temporary:
        directory = Path(temporary)
        wav = directory / "mix.wav"
        mp3 = directory / "mix.mp3"
        make_audio(wav)
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i",
                        str(wav), str(mp3)], check=True)
        start = time.monotonic()
        baseline = notes_from(mp3)
        baseline_seconds = time.monotonic() - start

        start = time.monotonic()
        separator = Separator(output_dir=str(directory), output_format="WAV",
                              model_file_dir=str(model_dir), log_level=40)
        separator.load_model(model_filename=MODEL)
        filenames = separator.separate(str(mp3))
        vocals = next(directory / filename for filename in filenames if "Vocals" in filename)
        separated = notes_from(vocals)
        separated_seconds = time.monotonic() - start
        print(json.dumps({
            "input": "generated five-note melody plus two-tone accompaniment",
            "baseline": {**evaluate(baseline), "seconds": round(baseline_seconds, 2), "events": baseline},
            "separated": {**evaluate(separated), "seconds": round(separated_seconds, 2), "events": separated},
            "model": MODEL,
        }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
