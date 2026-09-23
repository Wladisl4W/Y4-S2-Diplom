package ru.diplom.intonation;

import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

/** Selected targets and timing; the voice trace always comes from PitchTimeline. */
public final class ExerciseChartState {
    private final Exercise exercise;
    private final List<Integer> patternStarts;
    private long startNanos;
    private boolean running;

    public ExerciseChartState(Exercise exercise) { this(exercise, List.of(0)); }
    public ExerciseChartState(Exercise exercise, List<Integer> patternStarts) {
        this.exercise = exercise;
        this.patternStarts = List.copyOf(patternStarts);
    }

    public Exercise exercise() { return exercise; }
    public List<Integer> patternStarts() { return patternStarts; }
    public long startNanos() { return startNanos; }
    public boolean running() { return running; }

    public void start(long startNanos) {
        this.startNanos = startNanos;
        this.running = true;
    }

    public void finish() { running = false; }

    public long endNanos() { return startNanos + (long) (exercise.durationSeconds() * 1e9); }

    /** Keep targets at their actual attempt times after completion. */
    public long targetStartNanos() { return startNanos; }

    /** The last target has left the centered ten-second viewport. */
    public boolean expired(long nowNanos) {
        return startNanos != 0 && !running
                && nowNanos >= endNanos() + PitchTimeline.WINDOW_NANOS / 2;
    }
}
