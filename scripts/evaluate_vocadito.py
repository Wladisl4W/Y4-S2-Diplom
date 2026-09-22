#!/usr/bin/env python3
"""Evaluate note events on locally downloaded Vocadito annotations.

The dataset is never committed to this repository. The optional mix mode adds
simple tones as accompaniment and compares direct transcription to separation.
"""

import argparse
import contextlib
import csv
import io
import json
import math
from pathlib import Path
import subprocess
import tempfile
import time

import numpy as np
import soundfile as sf
from basic_pitch.inference import predict


def events_for(path):
    start = time.monotonic()
    with contextlib.redirect_stdout(io.StringIO()):
        _, _, events = predict(str(path))
    return [(float(a), float(b), int(note), float(confidence))
            for a, b, note, confidence, *_ in events], time.monotonic() - start


def evaluate(events, f0_path, min_confidence=0.0):
    with open(f0_path, newline="") as handle:
        reference = [(float(t), float(hz)) for t, hz in csv.reader(handle)]
    voiced = 0
    correct = 0
    detected = 0
    silence = 0
    false_voiced = 0
    for t, hz in reference[::17]:  # approximately 100 ms between checks
        active = [event for event in events if event[0] <= t < event[1]
                  and event[3] >= min_confidence]
        selected = max(active, key=lambda event: event[3]) if active else None
        if hz > 0:
            voiced += 1
            if selected:
                detected += 1
                expected_midi = 69 + 12 * math.log2(hz / 440)
                if abs(selected[2] - expected_midi) <= 1:
                    correct += 1
        else:
            silence += 1
            if selected:
                false_voiced += 1
    return {"voiced_frames": voiced, "correct_frames": correct,
            "detected_frames": detected, "silence_frames": silence,
            "false_voiced_frames": false_voiced,
            "coverage": round(detected / voiced, 3) if voiced else 0,
            "correct_of_voiced": round(correct / voiced, 3) if voiced else 0,
            "false_voiced_on_silence": round(false_voiced / silence, 3) if silence else 0,
            "note_events": len(events)}


def make_mix(vocal_path, destination):
    audio, rate = sf.read(vocal_path, always_2d=True)
    voice = audio.mean(axis=1)
    t = np.arange(len(voice)) / rate
    accompaniment = 0.12 * np.sin(2 * np.pi * 130.81 * t)
    accompaniment += 0.09 * np.sin(2 * np.pi * 196 * t)
    mixed = np.clip(voice + accompaniment, -1, 1)
    wav = destination.with_suffix(".wav")
    sf.write(wav, np.column_stack([mixed, mixed]), rate)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i",
                    str(wav), str(destination)], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path, help="Extracted Vocadito directory")
    parser.add_argument("--ids", nargs="+", type=int, default=[1, 2, 3])
    parser.add_argument("--mix", action="store_true", help="Also compare a synthetic accompaniment")
    parser.add_argument("--model", default="UVR_MDXNET_KARA_2.onnx",
                        help="Audio Separator model filename")
    args = parser.parse_args()
    output = []
    for number in args.ids:
        vocal = args.dataset / "Audio" / f"vocadito_{number}.wav"
        f0 = args.dataset / "Annotations" / "F0" / f"vocadito_{number}_f0.csv"
        events, seconds = events_for(vocal)
        item = {"id": number, "solo": {**evaluate(events, f0), "seconds": round(seconds, 2)}}
        if args.mix:
            from audio_separator.separator import Separator
            with tempfile.TemporaryDirectory(prefix="intonation-vocadito-") as temporary:
                work = Path(temporary)
                mp3 = work / "mix.mp3"
                make_mix(vocal, mp3)
                events, seconds = events_for(mp3)
                item["mix_direct"] = {**evaluate(events, f0), "seconds": round(seconds, 2)}
                start = time.monotonic()
                separator = Separator(output_dir=str(work), output_format="WAV", log_level=40,
                                      model_file_dir=str(Path.home() / ".local/share/intonation-trainer/models"))
                separator.load_model(model_filename=args.model)
                files = separator.separate(str(mp3))
                vocals = next(work / filename for filename in files if "Vocals" in filename)
                events, _ = events_for(vocals)
                item["mix_separated"] = {**evaluate(events, f0), "model": args.model,
                                         "seconds": round(time.monotonic() - start, 2)}
        output.append(item)
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
