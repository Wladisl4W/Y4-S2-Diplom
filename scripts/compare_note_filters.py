#!/usr/bin/env python3
"""Select a Basic Pitch confidence threshold on half of Vocadito; report holdout metrics."""

import argparse
import json
from pathlib import Path

from evaluate_vocadito import evaluate, events_for

THRESHOLDS = (0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6)


def summarize(rows):
    voiced = sum(row["voiced_frames"] for row in rows)
    correct = sum(row["correct_frames"] for row in rows)
    detected = sum(row["detected_frames"] for row in rows)
    silence = sum(row["silence_frames"] for row in rows)
    false_voiced = sum(row["false_voiced_frames"] for row in rows)
    # A wrong-pitch vocal frame counts as both a missed correct note and a false note.
    false = detected - correct + false_voiced
    precision = correct / (correct + false) if correct + false else 0
    recall = correct / voiced if voiced else 0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0
    return {"voiced_frames": voiced, "silence_frames": silence,
            "correct_of_voiced": round(recall, 3),
            "coverage": round(detected / voiced, 3) if voiced else 0,
            "false_voiced_on_silence": round(false_voiced / silence, 3) if silence else 0,
            "precision": round(precision, 3), "f1": round(f1, 3)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--count", type=int, default=40)
    args = parser.parse_args()
    if args.count < 4 or args.count % 2:
        parser.error("count must be an even number of at least four")
    results = {threshold: {"train": [], "holdout": []} for threshold in THRESHOLDS}
    for number in range(1, args.count + 1):
        vocal = args.dataset / "Audio" / f"vocadito_{number}.wav"
        f0 = args.dataset / "Annotations" / "F0" / f"vocadito_{number}_f0.csv"
        events, _ = events_for(vocal)
        split = "train" if number <= args.count // 2 else "holdout"
        for threshold in THRESHOLDS:
            results[threshold][split].append(evaluate(events, f0, threshold))
    train = {threshold: summarize(rows["train"]) for threshold, rows in results.items()}
    selected = max(THRESHOLDS, key=lambda threshold: train[threshold]["f1"])
    report = {"selection_rule": "highest frame-level F1 on first half of recordings",
              "selected_threshold": selected,
              "train": train[selected],
              "holdout": summarize(results[selected]["holdout"]),
              "baseline_holdout": summarize(results[0.0]["holdout"]),
              "all_train_thresholds": train}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
