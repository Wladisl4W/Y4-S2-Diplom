package ru.diplom.intonation;

/** One time axis for microphone pitch and optional future exercise targets. */
public record PitchViewport(long startNanos, long durationNanos) {
    public static PitchViewport live(long nowNanos) {
        return new PitchViewport(nowNanos - PitchTimeline.WINDOW_NANOS / 2, PitchTimeline.WINDOW_NANOS);
    }

    public static PitchViewport withTargets(long nowNanos) {
        return new PitchViewport(nowNanos - PitchTimeline.WINDOW_NANOS / 2,
                PitchTimeline.WINDOW_NANOS);
    }

    public double x(long timeNanos, double left, double width) {
        return left + width * (timeNanos - startNanos) / (double) durationNanos;
    }
}
