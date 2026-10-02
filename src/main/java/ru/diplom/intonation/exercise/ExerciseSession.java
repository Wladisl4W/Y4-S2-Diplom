package ru.diplom.intonation.exercise;

public final class ExerciseSession {
    private static final long COUNTDOWN_NANOS = 2_000_000_000L;
    private static final long SCORE_INTERVAL_NANOS = 100_000_000L;
    private final Exercise exercise;
    private final long startNanos;
    private final int slotsPerNote;
    private final boolean[] voiced;
    private final boolean[] hit;
    private final double[] errorCents;

    public record Result(String exerciseId, int score, int coverage, int averageErrorCents) {}

    public ExerciseSession(Exercise exercise, long nowNanos) {
        this.exercise = exercise;
        this.startNanos = nowNanos + COUNTDOWN_NANOS;
        this.slotsPerNote = Math.max(1, (int) Math.ceil(exercise.secondsPerNote() * 1e9 / SCORE_INTERVAL_NANOS));
        this.voiced = new boolean[exercise.notes().size() * slotsPerNote];
        this.hit = new boolean[voiced.length];
        this.errorCents = new double[voiced.length];
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
        long noteStart = startNanos + (long) (index * exercise.secondsPerNote() * 1e9);
        int slotWithinNote = (int) Math.min(slotsPerNote - 1,
                Math.max(0, (nowNanos - noteStart) / SCORE_INTERVAL_NANOS));
        int slot = index * slotsPerNote + slotWithinNote;
        if (Double.isFinite(detectedMidi)) {
            double cents = Math.abs(detectedMidi - exercise.notes().get(index)) * 100;
            voiced[slot] = true;
            errorCents[slot] = cents;
            hit[slot] = cents <= 50;
        } else if (!voiced[slot]) {
            hit[slot] = false;
        }
    }

    public Result result() {
        int slots = 0, voicedSlots = 0, hitSlots = 0;
        double absoluteCents = 0;
        for (int note = 0; note < exercise.notes().size(); note++) {
            if (exercise.notes().get(note) == Exercise.REST) continue;
            for (int within = 0; within < slotsPerNote; within++) {
                int slot = note * slotsPerNote + within;
                slots++;
                if (voiced[slot]) {
                    voicedSlots++;
                    absoluteCents += errorCents[slot];
                }
                if (hit[slot]) hitSlots++;
            }
        }
        int score = slots == 0 ? 0 : (int) Math.round(100.0 * hitSlots / slots);
        int coverage = slots == 0 ? 0 : (int) Math.round(100.0 * voicedSlots / slots);
        int error = voicedSlots == 0 ? 0 : (int) Math.round(absoluteCents / voicedSlots);
        return new Result(exercise.id(), score, coverage, error);
    }

    private long durationNanos() { return (long) (exercise.durationSeconds() * 1e9); }
}
