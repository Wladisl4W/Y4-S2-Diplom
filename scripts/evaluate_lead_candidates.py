#!/usr/bin/env python3
"""Compare lead trackers on the same cached Basic Pitch events.

Manifest CSV: id,split,audio,f0,notes. split is development or holdout;
f0 and notes are optional. All source audio and caches stay outside the repo.
"""

import argparse
import contextlib
import csv
import hashlib
import importlib.metadata
import io
import json
import time
from pathlib import Path

import soundfile
from basic_pitch.inference import predict

from benchmark_song_pipeline import event_metrics, note_metrics, lead


PRESETS = {
    "released_1_18": lead.BASELINE,
    "balanced": {**lead.BASELINE, "candidate_base": -0.05, "confidence": 0.75,
                 "duration": 0.15, "register": 0.05, "jump": 0.06,
                 "same": 0.24, "silence": -0.05},
    "cautious": {**lead.BASELINE, "candidate_base": -0.30, "confidence": 0.95,
                 "duration": 0.20, "jump": 0.04, "same": 0.20,
                 "silence": 0.0},
    "continuity": {**lead.BASELINE, "candidate_base": 0.20, "confidence": 0.55,
                   "register": 0.06, "jump": 0.08, "same": 0.30,
                   "silence": -0.10},
}


def events_for(audio, cache_dir):
    digest = hashlib.sha256()
    with audio.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    key = (digest.hexdigest() + "-basic-pitch-"
           + importlib.metadata.version("basic-pitch") + "-045-026-100.json")
    cache = cache_dir / key
    if cache.exists():
        return json.loads(cache.read_text()), True
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        _, _, raw = predict(str(audio), onset_threshold=0.45,
                            frame_threshold=0.26, minimum_note_length=100)
    events = sorted((float(start), float(end), int(midi), float(confidence))
                    for start, end, midi, confidence, *_ in raw
                    if end > start and 36 <= midi <= 96)
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(events))
    return events, False


def summarize(rows, preset):
    subset = [row["variants"][preset] for row in rows]
    keys = ("correct_pitch_rate", "miss_rate", "false_voice_rate", "note_event_f1")
    return {key: round(sum(item[key] for item in subset if item.get(key) is not None) /
                       len([item for item in subset if item.get(key) is not None]), 3)
            if any(item.get(key) is not None for item in subset) else None for key in keys}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    with args.manifest.open(newline="") as source:
        manifest = list(csv.DictReader(source))
    if not manifest or any(row["split"] not in ("development", "holdout") for row in manifest):
        parser.error("Manifest needs rows with development or holdout split")
    results = []
    for row in manifest:
        audio = Path(row["audio"])
        duration = soundfile.info(audio).duration
        started = time.monotonic()
        events, cached = events_for(audio, args.cache)
        item = {"id": row["id"], "song_id": row["id"].split("_seg_")[0],
                "source": row.get("source", ""), "split": row["split"],
                "duration_seconds": round(duration, 3),
                "candidate_events": len(events), "cache_hit": cached,
                "event_extraction_seconds": round(time.monotonic() - started, 2), "variants": {}}
        for name, config in PRESETS.items():
            notes, alternatives = lead.select_lead(events, duration, config)
            score = {"notes": len(notes), "alternatives": len(alternatives)}
            if row.get("f0"):
                score.update(note_metrics(Path(row["f0"]), notes, duration))
                score["candidate_oracle_pitch_rate"] = note_metrics(
                    Path(row["f0"]), notes + alternatives, duration, any_pitch=True)["correct_pitch_rate"]
            if row.get("notes"):
                score.update(event_metrics(Path(row["notes"]), notes, duration))
            item["variants"][name] = score
        results.append(item)
        print(json.dumps(item, ensure_ascii=False), flush=True)
    song_ids = sorted({row["song_id"] for row in results})
    sources = sorted({row["source"] for row in results})
    report = {"basic_pitch_version": importlib.metadata.version("basic-pitch"),
              "inference": {"onset_threshold": 0.45, "frame_threshold": 0.26,
                            "minimum_note_length_ms": 100, "midi_range": [36, 96]},
              "presets": PRESETS, "tracks": results,
              "macro": {split: {source: {name: summarize(
                  [row for row in results if row["split"] == split and row["source"] == source], name)
                  for name in PRESETS} for source in sources}
                        for split in ("development", "holdout")},
              "by_song": {song: {name: summarize(
                  [row for row in results if row["song_id"] == song], name)
                  for name in PRESETS} for song in song_ids}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
