"""Offline polyphonic note proposals and a continuous lead line.

Protocol: N/A, start seconds, duration seconds, MIDI, confidence (tab separated).
This is a candidate generator, not a verified transcription.
"""

import contextlib
import io
import math
import statistics
import sys

STEP = 0.05


def select_lead(events, duration):
    """Track one continuous voice without assuming the highest note is the lead."""
    count = math.ceil(duration / STEP)
    choices = []
    order = sorted(range(len(events)), key=lambda i: events[i][0])
    cursor = 0
    active = []
    for frame in range(count):
        t = (frame + 0.5) * STEP
        while cursor < len(order) and events[order[cursor]][0] <= t:
            active.append(order[cursor])
            cursor += 1
        active = [i for i in active if events[i][1] > t]
        choices.append(active[:])
    solo_register = [events[frame[0]][2] for frame in choices if len(frame) == 1]
    register = statistics.median(solo_register) if len(solo_register) >= 8 else None
    states = {None: 0.0}
    backtrack = []
    for active in choices:
        options = active + [None]
        next_states = {}
        parents = {}
        for candidate in options:
            best = None
            best_parent = None
            for previous, score in states.items():
                if candidate is None:
                    emission = -0.22 if active else 0.0
                else:
                    start, end, midi, confidence = events[candidate][:4]
                    emission = 0.32 + 0.5 * confidence + min(end - start, 1.0) * 0.12
                    if register is not None:
                        emission -= 0.04 * min(abs(midi - register), 18)
                transition = 0.0
                if candidate is not None and previous is not None:
                    old_midi = events[previous][2]
                    new_midi = events[candidate][2]
                    transition = -0.035 * min(abs(new_midi - old_midi), 24)
                    if candidate == previous:
                        transition += 0.18
                elif candidate != previous and (candidate is None or previous is None):
                    transition = -0.08
                value = score + emission + transition
                if best is None or value > best:
                    best = value
                    best_parent = previous
            next_states[candidate] = best
            parents[candidate] = best_parent
        states = next_states
        backtrack.append(parents)
    if not states:
        return [], []
    current = max(states, key=states.get)
    path = []
    for parents in reversed(backtrack):
        path.append(current)
        current = parents[current]
    path.reverse()
    notes = []
    alternatives = []
    run_start = 0
    for frame in range(1, len(path) + 1):
        if frame < len(path) and path[frame] == path[run_start]:
            continue
        selected = path[run_start]
        if selected is not None and frame - run_start >= 2:
            source = events[selected]
            start = max(run_start * STEP, source[0])
            end = min(frame * STEP, source[1])
            if end - start >= 0.09:
                note = (round(start, 3), round(end - start, 3),
                        int(source[2]), round(float(source[3]), 3))
                notes.append(note)
                nearby = {index for frame_choices in choices[run_start:frame]
                          for index in frame_choices}
                for index in nearby:
                    other = events[index]
                    if index == selected or other[2] == source[2]:
                        continue
                    overlap = min(end, other[1]) - max(start, other[0])
                    if overlap >= min(0.12, (end - start) * 0.45):
                        alternatives.append((note[0], note[1], int(other[2]),
                                             round(float(other[3]), 3)))
        run_start = frame
    return notes, alternatives


def main():
    from basic_pitch.inference import predict
    import soundfile
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        _, _, raw = predict(sys.argv[1], onset_threshold=0.45,
                            frame_threshold=0.26, minimum_note_length=100)
    events = sorted((float(start), float(end), int(midi), float(confidence))
                    for start, end, midi, confidence, *_ in raw
                    if end > start and 36 <= midi <= 96)
    duration = float(soundfile.info(sys.argv[1]).duration)
    notes, alternatives = select_lead(events, duration)
    print("D", round(duration, 3), sep="\t")
    for kind, rows in (("N", notes), ("A", alternatives)):
        for row in rows:
            print(kind, *row, sep="\t")


if __name__ == "__main__":
    main()
