#!/usr/bin/env python3
"""Compare Essentia melody baselines on a local file with time,Hz annotation.

Run with an optional, separate Essentia Python environment. The multi-pitch
figure is oracle candidate coverage, not automatic lead-selection accuracy.
"""

import argparse
import bisect
import csv
import json
import math
import time

import essentia.standard as es

RATE = 22050
HOP = 256


def reference_rows(path):
    rows = []
    with open(path, newline="") as handle:
        for row in csv.reader(handle):
            try:
                rows.append((float(row[0]), float(row[1])))
            except (ValueError, IndexError):
                continue
    return rows


def score(reference, candidate, seconds):
    times = [row[0] for row in reference]
    voiced = correct = missed = silence = false_voice = 0
    for i, pitches in enumerate(candidate):
        t = i * HOP / RATE
        if t >= seconds:
            break
        index = bisect.bisect_left(times, t)
        if index >= len(reference):
            continue
        hz = reference[index][1]
        active = [float(p) for p in pitches if p > 0]
        if hz > 0:
            voiced += 1
            if not active:
                missed += 1
            elif any(abs(12 * math.log2(p / hz)) <= 0.5 for p in active):
                correct += 1
        else:
            silence += 1
            false_voice += bool(active)
    return {"vocal_frames": voiced, "pitch_rate": round(correct / voiced, 3) if voiced else None,
            "miss_rate": round(missed / voiced, 3) if voiced else None,
            "false_voice_rate": round(false_voice / silence, 3) if silence else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio")
    parser.add_argument("f0")
    parser.add_argument("--seconds", type=int, default=30)
    args = parser.parse_args()
    signal = es.MonoLoader(filename=args.audio, sampleRate=RATE)()[:args.seconds * RATE]
    reference = reference_rows(args.f0)
    start = time.monotonic()
    predominant, confidence = es.PredominantPitchMelodia(sampleRate=RATE, hopSize=HOP,
                            minFrequency=70, maxFrequency=1000)(signal)
    predominant_seconds = time.monotonic() - start
    start = time.monotonic()
    multipitch = es.MultiPitchMelodia(sampleRate=RATE, hopSize=HOP,
                            minFrequency=70, maxFrequency=1000)(signal)
    multi_seconds = time.monotonic() - start
    print(json.dumps({
        "predominant": {**score(reference, ([p] for p in predominant), args.seconds),
                        "seconds": round(predominant_seconds, 2)},
        "multipitch_oracle_coverage": {**score(reference, multipitch, args.seconds),
                                       "seconds": round(multi_seconds, 2)},
        "note": "Multi-pitch checks whether the lead is among candidates; it does not choose a lead.",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
