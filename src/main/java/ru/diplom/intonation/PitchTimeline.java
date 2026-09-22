package ru.diplom.intonation;

import java.util.ArrayDeque;
import java.util.List;

/** Time based pitch history. All methods are called on the JavaFX application thread. */
public final class PitchTimeline {
    public static final long WINDOW_NANOS = 10_000_000_000L;
    private static final double HALF_RANGE = 7.0;
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    private double centerMidi = 60;
    private boolean hasPitch;

    public record Point(long timeNanos, double midi) {
        public boolean voiced() { return Double.isFinite(midi); }
    }

    public void clear() {
        points.clear();
        centerMidi = 60;
        hasPitch = false;
    }

    public void add(long timeNanos, double midi) {
        points.addLast(new Point(timeNanos, midi));
        if (Double.isFinite(midi)) {
            if (!hasPitch) {
                centerMidi = Math.rint(midi);
                hasPitch = true;
            } else if (midi > centerMidi + HALF_RANGE - 1) {
                centerMidi = Math.ceil(midi - HALF_RANGE + 1);
            } else if (midi < centerMidi - HALF_RANGE + 1) {
                centerMidi = Math.floor(midi + HALF_RANGE - 1);
            }
        }
        prune(timeNanos);
    }

    public void prune(long nowNanos) {
        while (!points.isEmpty() && points.peekFirst().timeNanos() < nowNanos - WINDOW_NANOS)
            points.removeFirst();
    }

    public List<Point> points() { return List.copyOf(points); }
    public double centerMidi() { return centerMidi; }
    public boolean hasPitch() { return hasPitch; }

    public static double x(long timeNanos, long nowNanos, double left, double width) {
        return left + width * (1 - (nowNanos - timeNanos) / (double) WINDOW_NANOS);
    }
}
