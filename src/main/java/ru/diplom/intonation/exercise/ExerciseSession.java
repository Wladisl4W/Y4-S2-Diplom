package ru.diplom.intonation.exercise;

public final class ExerciseSession {
    private static final long COUNTDOWN_NANOS = 2_000_000_000L;
    private final Exercise exercise;
    private final long startNanos;
    private int frames;
    private int voicedFrames;
    private int hitFrames;
    private double absoluteCents;

    public record Result(String exerciseId, int score, int coverage, int averageErrorCents) {}

    public ExerciseSession(Exercise exercise, long nowNanos) {
        this.exercise = exercise;
        this.startNanos = nowNanos + COUNTDOWN_NANOS;
    }

    public Exercise exercise() { return exercise; }
    public long startNanos() { return startNanos; }
    public boolean finished(long nowNanos) { return nowNanos >= startNanos + durationNanos(); }
    public int countdown(long nowNanos) { return (int) Math.ceil(Math.max(0, startNanos - nowNanos) / 1e9); }

    public int noteIndex(long nowNanos) {
        if (nowNanos < startNanos || finished(nowNanos)) return -1;
        return Math.min(exercise.notes().size() - 1,
                (int) ((nowNanos - startNanos) / (exercise.secondsPerNote() * 1e9)));
    }

    public void accept(long nowNanos, double detectedMidi) {
        int index = noteIndex(nowNanos);
        if (index < 0) return;
        if (exercise.notes().get(index) == Exercise.REST) return;
        frames++;
        if (Double.isFinite(detectedMidi)) {
            voicedFrames++;
            double cents = Math.abs(detectedMidi - exercise.notes().get(index)) * 100;
            absoluteCents += cents;
            if (cents <= 50) hitFrames++;
        }
    }

    public Result result() {
        int score = frames == 0 ? 0 : (int) Math.round(100.0 * hitFrames / frames);
        int coverage = frames == 0 ? 0 : (int) Math.round(100.0 * voicedFrames / frames);
        int error = voicedFrames == 0 ? 0 : (int) Math.round(absoluteCents / voicedFrames);
        return new Result(exercise.id(), score, coverage, error);
    }

    private long durationNanos() { return (long) (exercise.durationSeconds() * 1e9); }
}
