package ru.diplom.intonation;

import java.util.ArrayDeque;

/** Bounded handoff from the audio thread to JavaFX; old frames are never replayed after a stall. */
final class PitchSampleBuffer<T> {
    private final int capacity;
    private final ArrayDeque<T> samples = new ArrayDeque<>();

    PitchSampleBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }

    synchronized void add(T sample) {
        if (samples.size() == capacity) samples.removeFirst();
        samples.addLast(sample);
    }

    synchronized T poll() { return samples.pollFirst(); }
    synchronized void clear() { samples.clear(); }
    synchronized int size() { return samples.size(); }
}
