#!/usr/bin/env python3
"""Evaluate the lead proposal on aligned MedleyVox main-vs-rest excerpts.

Requires extracted MedleyVox directory, its official segment metadata JSON,
and original-song MedleyDB MELODY1 CSV. No audio is added to the repository.
"""

import argparse
import csv
import json
import subprocess
import tempfile
from pathlib import Path

from benchmark_song_pipeline import note_metrics

ROOT = Path(__file__).resolve().parents[1]


def transcribe(python, audio):
    result = subprocess.run([str(python), str(ROOT / "src/main/resources/lead_transcribe.py"), str(audio)],
                            check=True, capture_output=True, text=True)
    notes, alternatives = [], []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 5 and parts[0] in ("N", "A"):
            item = (float(parts[1]), float(parts[2]), int(parts[3]))
            (notes if parts[0] == "N" else alternatives).append(item)
    return notes, alternatives


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("song_dir", type=Path, help="MedleyVox/rest/<song>")
    parser.add_argument("metadata", type=Path, help="official <song>.json")
    parser.add_argument("f0", type=Path, help="original-song MELODY1.csv")
    parser.add_argument("--python", type=Path, default=Path.home() / ".local/share/intonation-trainer/ml-venv/bin/python")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text())
    with args.f0.open(newline="") as handle:
        source_f0 = [(float(a), float(b)) for a, b in csv.reader(handle)]
    results = []
    with tempfile.TemporaryDirectory(prefix="intonation-medleyvox-") as temporary:
        local_f0 = Path(temporary) / "f0.csv"
        for segment, info in sorted(metadata.items()):
            folder = args.song_dir / segment
            if not folder.exists():
                continue
            start = float(info["start_sec"])
            duration = float(info["end_sec"]) - start
            with local_f0.open("w", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerows((round(t - start, 6), hz) for t, hz in source_f0
                                 if start <= t < start + duration)
            mix = next((folder / "mix").glob("*.wav"))
            main = next(p for p in (folder / "gt").glob("*.wav")
                        if info["main_vocal"] in p.name)
            entry = {"segment": segment, "type": info["type"],
                     "other_voices": len(info["other_vocals"])}
            for name, audio in (("mixture", mix), ("isolated_main", main)):
                notes, alternatives = transcribe(args.python, audio)
                entry[name] = {**note_metrics(local_f0, notes, duration),
                               "notes": len(notes), "alternative_notes": len(alternatives)}
                entry[name + "_candidate_oracle"] = note_metrics(local_f0,
                                                                    notes + alternatives, duration,
                                                                    any_pitch=True)
            results.append(entry)
            print(json.dumps(entry, ensure_ascii=False), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
