#!/usr/bin/env python3
"""Reproducible local song benchmark; no audio or model is written into the repo.

Manifest CSV columns: id,mix,vocals,f0,notes. Optional columns may be empty.
F0 CSV has two numeric columns time_seconds,frequency_hz (0 for silence).
Notes CSV has onset_seconds,offset_seconds,midi; use manually verified notes.
Run with the Python environment from setup_song_analysis.sh.
"""

import argparse
import csv
import importlib.util
import json
import math
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("lead_transcribe", ROOT / "src/main/resources/lead_transcribe.py")
lead = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lead)
MODELS = (
    "UVR_MDXNET_KARA_2.onnx",
    "UVR-MDX-NET_Main_406.onnx",
    "htdemucs_ft.yaml",
    "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
    "vocals_mel_band_roformer.ckpt",
)


def pcm(path, seconds):
    proc = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                           "-i", str(path), "-t", str(seconds), "-ac", "1", "-ar", "16000",
                           "-f", "f32le", "pipe:1"], capture_output=True, check=True)
    return np.frombuffer(proc.stdout, dtype="<f4").astype(float)


def si_sdr(reference, estimate):
    length = min(len(reference), len(estimate))
    reference, estimate = reference[:length], estimate[:length]
    reference -= reference.mean()
    estimate -= estimate.mean()
    target = np.dot(estimate, reference) / (np.dot(reference, reference) + 1e-12) * reference
    noise = estimate - target
    return round(10 * math.log10((np.dot(target, target) + 1e-12) /
                                 (np.dot(noise, noise) + 1e-12)), 2)


def note_metrics(f0_path, notes, seconds, any_pitch=False):
    reference = []
    with open(f0_path, newline="") as handle:
        for row in csv.reader(handle):
            try:
                t, hz = map(float, row[:2])
            except (ValueError, IndexError):
                continue
            if 0 <= t < seconds:
                reference.append((t, hz))
    voiced = correct = missed = false_voice = silence = 0
    for t, hz in reference:
        found = [n[2] for n in notes if n[0] <= t < n[0] + n[1]]
        if not any_pitch:
            found = found[:1]
        if hz > 0:
            voiced += 1
            if not found:
                missed += 1
            elif any(abs(midi - (69 + 12 * math.log2(hz / 440))) <= 0.5 for midi in found):
                correct += 1
        else:
            silence += 1
            false_voice += bool(found)
    return {"vocal_frames": voiced, "correct_pitch_rate": round(correct / voiced, 3) if voiced else None,
            "miss_rate": round(missed / voiced, 3) if voiced else None,
            "silent_frames": silence,
            "false_voice_rate": round(false_voice / silence, 3) if silence else None}


def event_metrics(reference_path, predicted, seconds):
    reference = []
    with open(reference_path, newline="") as handle:
        for row in csv.reader(handle):
            try:
                start, end, midi = float(row[0]), float(row[1]), int(row[2])
            except (ValueError, IndexError):
                continue
            if 0 <= start < seconds and end > start:
                reference.append((start, min(end, seconds), midi))
    matched = set()
    onset_errors = []
    offset_errors = []
    for start, duration, midi, *_ in sorted(predicted, key=lambda note: note[0]):
        options = [(abs(start - onset) + abs(start + duration - offset), index,
                    abs(start - onset), abs(start + duration - offset))
                   for index, (onset, offset, truth) in enumerate(reference)
                   if index not in matched and midi == truth and abs(start - onset) <= 0.1
                   and abs(start + duration - offset) <= 0.2]
        if options:
            _, index, onset_error, offset_error = min(options)
            matched.add(index)
            onset_errors.append(onset_error)
            offset_errors.append(offset_error)
    precision = len(matched) / len(predicted) if predicted else 0.0
    recall = len(matched) / len(reference) if reference else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"reference_notes": len(reference), "detected_notes": len(predicted),
            "matched_notes": len(matched), "note_event_f1": round(f1, 3),
            "onset_mae_seconds": round(sum(onset_errors) / len(onset_errors), 3) if matched else None,
            "offset_mae_seconds": round(sum(offset_errors) / len(offset_errors), 3) if matched else None}


def run_one(row, model, args):
    with tempfile.TemporaryDirectory(prefix="intonation-benchmark-") as temp:
        directory = Path(temp)
        input_wav = directory / "input.wav"
        subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", row["mix"], "-t", str(args.seconds), "-ac", "2", "-ar", "44100",
                        str(input_wav)], check=True)
        start = time.monotonic()
        separation_command = [str(args.separator), str(input_wav), "-m", model,
                        "--output_format", "WAV", "--output_dir", str(directory),
                        "--model_file_dir", str(args.models), "--log_level", "error"]
        memory_file = directory / "memory-kb.txt"
        if Path("/usr/bin/time").exists():
            separation_command = ["/usr/bin/time", "-f", "%M", "-o", str(memory_file)] + separation_command
        separation = subprocess.run(separation_command, capture_output=True, timeout=900)
        if separation.returncode:
            raise RuntimeError("separator failed (exit " + str(separation.returncode) + "): "
                               + separation.stderr.decode(errors="replace")[-500:])
        separation_seconds = round(time.monotonic() - start, 2)
        stem = next(p for p in directory.glob("*.wav") if "(vocals)" in p.name.lower())
        start = time.monotonic()
        from basic_pitch.inference import predict
        import contextlib
        import io
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            _, _, raw = predict(str(stem), onset_threshold=0.45,
                                frame_threshold=0.26, minimum_note_length=100)
        events = sorted((float(a), float(b), int(n), float(c)) for a, b, n, c, *_ in raw
                        if b > a and 36 <= n <= 96)
        notes, alternatives = lead.select_lead(events, min(args.seconds, len(pcm(stem, args.seconds)) / 16000))
        result = {"id": row["id"], "model": model, "separation_seconds": separation_seconds,
                  "transcription_seconds": round(time.monotonic() - start, 2),
                  "notes": len(notes), "alternatives": len(alternatives)}
        if memory_file.exists():
            result["separator_peak_rss_mb"] = round(int(memory_file.read_text().strip()) / 1024)
        if row.get("vocals"):
            result["vocal_si_sdr_db"] = si_sdr(pcm(Path(row["vocals"]), args.seconds),
                                               pcm(stem, args.seconds))
        if row.get("f0"):
            result.update(note_metrics(Path(row["f0"]), notes, args.seconds))
        if row.get("notes"):
            result.update(event_metrics(Path(row["notes"]), notes, args.seconds))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--models", type=Path, default=Path.home() / ".local/share/intonation-trainer/models")
    parser.add_argument("--separator", type=Path, default=Path.home() / ".local/share/intonation-trainer/python/bin/audio-separator")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--model", action="append", choices=MODELS, help="repeat; default: all four")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.manifest.open(newline="") as handle:
        rows = list(csv.DictReader(handle))
    results = []
    for row in rows:
        for model in args.model or MODELS:
            if not (args.models / model).exists():
                results.append({"id": row["id"], "model": model, "skipped": "model not downloaded"})
                continue
            try:
                results.append(run_one(row, model, args))
            except Exception as error:
                results.append({"id": row["id"], "model": model, "error": str(error)})
            print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
