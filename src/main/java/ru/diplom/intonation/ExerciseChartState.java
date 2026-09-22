package ru.diplom.intonation;

import ru.diplom.intonation.exercise.Exercise;

import java.util.ArrayList;
import java.util.List;

/** Pitch samples and timing for one exercise attempt, independent of the live rolling history. */
public final class ExerciseChartState {
    private final Exercise exercise;
    private final List<PitchTimeline.Point> points = new ArrayList<>();
    private long startNanos;
    private long displayNanos;
    private boolean running;

    public ExerciseChartState(Exercise exercise) { this.exercise = exercise; }
    public Exercise exercise() { return exercise; }
    public List<PitchTimeline.Point> points() { return List.copyOf(points); }
    public long startNanos() { return startNanos; }
    public long displayNanos() { return displayNanos; }
    public boolean running() { return running; }

    public void start(long startNanos) {
        this.startNanos = startNanos;
        this.displayNanos = startNanos;
        this.running = true;
        points.clear();
    }

    public void add(long timeNanos, double midi) {
        if (running && timeNanos >= startNanos && timeNanos < endNanos())
            points.add(new PitchTimeline.Point(timeNanos, midi));
    }

    public void advance(long nowNanos) {
        if (running) displayNanos = Math.max(startNanos, Math.min(nowNanos, endNanos()));
    }

    public void finish() { running = false; }
    public long endNanos() { return startNanos + (long) (exercise.durationSeconds() * 1e9); }
}
