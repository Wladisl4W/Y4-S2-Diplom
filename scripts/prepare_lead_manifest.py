#!/usr/bin/env python3
"""Build an external benchmark manifest from official Vocadito and MedleyVox files.

MedleyDB MELODY1 is accepted only when its rank-1 stem matches MedleyVox's
declared main vocal. Split is fixed by song, not by segment.
"""

import argparse
import csv
import json
import math
from pathlib import Path


HOLDOUT_SONGS = {"ClaraBerryAndWooldog_Boys", "Meaxic_TakeAStep", "NightPanther_Fire"}


def rows(path):
    with path.open(newline="") as source:
        return list(csv.reader(source))


def write_rows(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as target:
        csv.writer(target).writerows(data)


def add_vocadito(root, output):
    result = []
    for index in range(1, 41):
        name = f"vocadito_{index}"
        audio = root / "Audio" / f"{name}.wav"
        f0 = root / "Annotations" / "F0" / f"{name}_f0.csv"
        notes = root / "Annotations" / "Notes" / f"{name}_notesA1.csv"
        if not all(path.is_file() for path in (audio, f0, notes)):
            raise FileNotFoundError(f"Incomplete Vocadito record: {name}")
        converted = output / "notes" / f"{name}.csv"
        write_rows(converted, ((float(start), float(start) + float(length),
                                round(69 + 12 * math.log2(float(hz) / 440)))
                               for start, hz, length in rows(notes) if float(hz) > 0))
        result.append({"id": name, "split": "holdout" if index % 2 == 0 else "development",
                       "audio": str(audio), "f0": str(f0), "notes": str(converted),
                       "source": "Vocadito v3 solo"})
    return result


def rank_one_stem(path):
    for row in rows(path):
        if len(row) == 2 and row[1] == "1":
            return row[0].split("_STEM_")[-1].split(".")[0]
    return None


def add_medleyvox(audio_root, config_root, annotation_root, output):
    result = []
    for song in sorted(audio_root.iterdir()):
        if not song.is_dir():
            continue
        config = config_root / f"{song.name}.json"
        f0 = annotation_root / "Melody" / "Melody1" / f"{song.name}_MELODY1.csv"
        ranking = annotation_root / "Melody" / "Ranking" / f"{song.name}_RANKING.txt"
        if not all(path.is_file() for path in (config, f0, ranking)):
            continue
        lead_stem = rank_one_stem(ranking)
        source_f0 = [(float(t), float(hz)) for t, hz in rows(f0)]
        for segment, info in sorted(json.loads(config.read_text()).items()):
            folder = song / segment
            if not folder.is_dir() or info["main_vocal"].split("_RAW_")[-1].split("_")[0] != lead_stem:
                continue
            mixes = list((folder / "mix").glob("*.wav"))
            if len(mixes) != 1:
                raise ValueError(f"Expected one vocal mix in {folder}")
            start, end = float(info["start_sec"]), float(info["end_sec"])
            aligned = output / "f0" / f"{song.name}_{segment}.csv"
            write_rows(aligned, ((round(t - start, 6), hz) for t, hz in source_f0
                                 if start <= t < end))
            result.append({"id": f"{song.name}_{segment}",
                           "split": "holdout" if song.name in HOLDOUT_SONGS else "development",
                           "audio": str(mixes[0]), "f0": str(aligned), "notes": "",
                           "source": "MedleyVox main-vs-rest plus MedleyDB MELODY1"})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vocadito", type=Path, required=True)
    parser.add_argument("--medleyvox", type=Path, required=True,
                        help="MedleyVox/rest directory")
    parser.add_argument("--config", type=Path, required=True,
                        help="MedleyVox V1_rest_vocals_only_config directory")
    parser.add_argument("--annotations", type=Path, required=True,
                        help="official medleydb/data/Annotations directory")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    result = add_vocadito(args.vocadito, args.output_dir)
    result.extend(add_medleyvox(args.medleyvox, args.config, args.annotations, args.output_dir))
    if len({row["id"].split("_seg_")[0] for row in result if "_seg_" in row["id"]}) < 3:
        raise ValueError("Need at least three MedleyVox songs with aligned lead annotations")
    write_rows(args.output_dir / "manifest.csv",
               [["id", "split", "audio", "f0", "notes", "source"],
                *(list(row.values()) for row in result)])
    print(f"Prepared {len(result)} recordings; manifest: {args.output_dir / 'manifest.csv'}")


if __name__ == "__main__":
    main()
