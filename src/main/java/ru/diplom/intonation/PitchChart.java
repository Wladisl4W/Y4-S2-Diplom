package ru.diplom.intonation;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import ru.diplom.intonation.audio.Note;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

/** The single live microphone canvas; exercise targets are an optional overlay. */
public final class PitchChart {
    private static final Color BACKGROUND = Color.web("#282828");
    private static final Color GRID = Color.web("#4b4b4b");
    private static final Color NATURAL_ROW = Color.web("#303030");
    private static final Color SHARP_ROW = Color.web("#363636");
    private static final Color LABEL = Color.web("#b8b8b5");
    private static final Color TRACE = Color.web("#6de1d5");
    private static final Color TARGET = Color.web("#c49050");
    private static final Color MISS = Color.web("#ff8a91");
    private static final double LEFT = 48, RIGHT = 16, TOP = 18, BOTTOM = 28;
    private final Canvas canvas;

    public PitchChart(Canvas canvas) { this.canvas = canvas; }

    public void draw(PitchTimeline timeline, long nowNanos, ExerciseChartState targets) {
        draw(timeline, nowNanos, targets, null);
    }

    public void draw(PitchTimeline timeline, long nowNanos, ExerciseChartState targets,
                     SongChartState song) {
        timeline.prune(nowNanos);
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= LEFT + RIGHT || h <= TOP + BOTTOM) return;
        double plotWidth = w - LEFT - RIGHT;
        double plotHeight = h - TOP - BOTTOM;
        PitchViewport viewport = targets == null ? PitchViewport.live(nowNanos)
                : PitchViewport.withTargets(nowNanos);
        double center = timeline.centerMidi();
        double halfRange = 7;
        if (targets != null) {
            Exercise exercise = targets.exercise();
            int min = exercise.notes().stream().mapToInt(Integer::intValue)
                    .filter(note -> note != Exercise.REST).min().orElse(60);
            int max = exercise.notes().stream().mapToInt(Integer::intValue)
                    .filter(note -> note != Exercise.REST).max().orElse(60);
            center = (min + max) / 2.0;
            halfRange = Math.max(5, (max - min) / 2.0 + 2);
        } else if (song != null && !song.notes().isEmpty()) {
            double at = (nowNanos - song.startNanos()) / 1e9;
            List<Integer> nearby = song.notes().stream()
                    .filter(item -> item.startSeconds() < at + 6
                            && item.startSeconds() + item.durationSeconds() > at - 4)
                    .map(item -> item.midi()).toList();
            if (!nearby.isEmpty()) {
                int min = nearby.stream().mapToInt(Integer::intValue).min().orElse(60);
                int max = nearby.stream().mapToInt(Integer::intValue).max().orElse(72);
                center = (min + max) / 2.0;
                halfRange = Math.max(7, (max - min) / 2.0 + 2);
            }
        }

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font(11));
        grid(g, w, h, center, halfRange);
        // Fixed two-second ticks move across the stationary playhead.
        long tick = 2_000_000_000L;
        long firstTick = Math.floorDiv(viewport.startNanos(), tick) * tick;
        for (long time = firstTick; time <= viewport.startNanos() + viewport.durationNanos(); time += tick) {
            double x = viewport.x(time, LEFT, plotWidth);
            if (x >= LEFT && x <= w - RIGHT)
                vertical(g, x, h, String.format("%+.0f с", (time - nowNanos) / 1_000_000_000.0));
        }
        if (targets != null || song != null) {
            g.setFill(TRACE);
            g.fillText("● в цели", w - 167, 13);
            g.setFill(MISS);
            g.fillText("● мимо", w - 88, 13);
        }

        g.save();
        g.beginPath();
        g.rect(LEFT, TOP, plotWidth, plotHeight);
        g.closePath();
        g.clip();
        if (targets != null) drawTargets(g, targets, nowNanos, viewport, w, h, center, halfRange);
        if (song != null) drawSongTargets(g, song, viewport, w, h, center, halfRange);
        trace(g, timeline.points(), viewport, w, h, center, halfRange, targets, song);
        double cursor = viewport.x(nowNanos, LEFT, plotWidth);
        g.setStroke(Color.web("#ffad57"));
        g.setLineWidth(2);
        g.strokeLine(cursor, TOP, cursor, h - BOTTOM);
        g.restore();
    }

    private void drawSongTargets(GraphicsContext g, SongChartState song, PitchViewport viewport,
                                 double w, double h, double center, double halfRange) {
        double plotWidth = w - LEFT - RIGHT;
        double plotHeight = h - TOP - BOTTOM;
        double bandHeight = plotHeight / (2 * halfRange);
        for (var note : song.notes()) {
            long from = song.startNanos() + (long) (note.startSeconds() * 1e9);
            long to = from + (long) (note.durationSeconds() * 1e9);
            double x = viewport.x(from, LEFT, plotWidth);
            double end = viewport.x(to, LEFT, plotWidth);
            if (end < LEFT || x > w - RIGHT) continue;
            double y = y(note.midi(), center, halfRange, plotHeight);
            g.setFill(TARGET);
            g.fillRoundRect(x + 1, y - bandHeight / 2, Math.max(2, end - x - 2),
                    bandHeight, 4, 4);
            if (end - x > 38) {
                g.setFill(Color.WHITE);
                g.fillText(noteName(note.midi()), Math.max(LEFT + 5, x + 5), y + 4);
            }
        }
    }

    private void drawTargets(GraphicsContext g, ExerciseChartState targets, long nowNanos,
                             PitchViewport viewport, double w, double h,
                             double center, double halfRange) {
        Exercise exercise = targets.exercise();
        long targetStart = targets.targetStartNanos();
        double plotWidth = w - LEFT - RIGHT;
        double plotHeight = h - TOP - BOTTOM;
        double toleranceHeight = plotHeight / (2 * halfRange); // ±50 cents
        long noteNanos = (long) (exercise.secondsPerNote() * 1e9);
        for (int i = 0; i < exercise.notes().size(); i++) {
            long from = targetStart + i * noteNanos;
            double x = viewport.x(from, LEFT, plotWidth);
            double nextX = viewport.x(from + noteNanos, LEFT, plotWidth);
            if (nextX < LEFT || x > w - RIGHT) continue;
            if (exercise.notes().get(i) == Exercise.REST) {
                g.setFill(Color.web("#3e3932"));
                g.fillRect(x, TOP, nextX - x, plotHeight);
                g.setFill(Color.web("#ffad57"));
                if (nextX - x > 50) {
                    int next = Math.min(i + 1, exercise.notes().size() - 1);
                    int previousStart = 0;
                    for (int start : targets.patternStarts()) {
                        if (start >= next) break;
                        previousStart = start;
                    }
                    String direction = exercise.notes().get(next) >= exercise.notes().get(previousStart)
                            ? "↑" : "↓";
                    g.fillText(direction + " переход", x + 5, TOP + 14);
                }
                continue;
            }
            double y = y(exercise.notes().get(i), center, halfRange, plotHeight);
            g.setFill(TARGET);
            g.fillRoundRect(x + 2, y - toleranceHeight / 2, nextX - x - 4,
                    toleranceHeight, 7, 7);
            g.setFill(Color.WHITE);
            g.fillText(noteName(exercise.notes().get(i)), Math.max(LEFT + 7, x + 9), y + 4);
        }
        for (int part = 1; part < targets.patternStarts().size(); part++) {
            long from = targetStart + targets.patternStarts().get(part) * noteNanos;
            double x = viewport.x(from, LEFT, plotWidth);
            if (x < LEFT || x > w - RIGHT) continue;
            g.setStroke(Color.web("#ffad57"));
            g.setLineWidth(1.5);
            g.strokeLine(x, TOP, x, h - BOTTOM);
            g.setFill(Color.web("#ffad57"));
            g.fillText("Этап " + (part + 1), x + 5, TOP + 12);
        }
    }

    private void grid(GraphicsContext g, double w, double h, double center, double halfRange) {
        int low = (int) Math.ceil(center - halfRange - 0.5);
        int high = (int) Math.floor(center + halfRange + 0.5);
        double plotHeight = h - TOP - BOTTOM;
        double plotBottom = h - BOTTOM;
        for (int midi = low; midi <= high; midi++) {
            double top = Math.max(TOP, y(midi + 0.5, center, halfRange, plotHeight));
            double bottom = Math.min(plotBottom, y(midi - 0.5, center, halfRange, plotHeight));
            if (bottom <= top) continue;
            int pitchClass = Math.floorMod(midi, 12);
            boolean sharp = pitchClass == 1 || pitchClass == 3 || pitchClass == 6
                    || pitchClass == 8 || pitchClass == 10;
            g.setFill(sharp ? SHARP_ROW : NATURAL_ROW);
            g.fillRect(LEFT, top, w - LEFT - RIGHT, bottom - top);
            g.setStroke(GRID);
            g.setLineWidth(1);
            g.strokeLine(LEFT, bottom, w - RIGHT, bottom);
            double middle = y(midi, center, halfRange, plotHeight);
            if (middle >= TOP + 5 && middle <= plotBottom - 5) {
                g.setFill(LABEL);
                g.fillText(noteName(midi), 8, middle + 4);
            }
        }
        g.setStroke(GRID);
        g.strokeLine(LEFT, TOP, w - RIGHT, TOP);
    }

    private void trace(GraphicsContext g, List<PitchTimeline.Point> points, PitchViewport viewport,
                       double w, double h, double center, double halfRange,
                       ExerciseChartState targets, SongChartState song) {
        g.setStroke(TRACE);
        g.setLineWidth(2.5);
        boolean connected = false;
        double previousX = 0, previousY = 0;
        long previousTime = 0;
        double height = h - TOP - BOTTOM;
        for (PitchTimeline.Point point : points) {
            double x = viewport.x(point.timeNanos(), LEFT, w - LEFT - RIGHT);
            double y = y(point.midi(), center, halfRange, height);
            boolean visible = point.voiced() && x >= LEFT && x <= w - RIGHT && y >= TOP && y <= h - BOTTOM;
            if (visible && connected && point.timeNanos() - previousTime < 250_000_000L) {
                if (targets != null && targets.startNanos() > 0
                        && point.timeNanos() >= targets.startNanos()
                        && point.timeNanos() < targets.endNanos()) {
                    Exercise exercise = targets.exercise();
                    int index = Math.min(exercise.notes().size() - 1,
                            (int) ((point.timeNanos() - targets.startNanos()) /
                                    (exercise.secondsPerNote() * 1e9)));
                    int expected = exercise.notes().get(index);
                    g.setStroke(expected == Exercise.REST ? LABEL :
                            Math.abs(point.midi() - expected) <= 0.5 ? TRACE : MISS);
                } else if (song != null) {
                    int expected = song.targetAt(point.timeNanos());
                    g.setStroke(expected < 0 ? LABEL :
                            Math.abs(point.midi() - expected) <= 0.5 ? TRACE : MISS);
                } else g.setStroke(TRACE);
                g.strokeLine(previousX, previousY, x, y);
            }
            connected = visible;
            previousX = x;
            previousY = y;
            previousTime = point.timeNanos();
        }
    }

    private static String noteName(int midi) {
        double hz = 440 * Math.pow(2, (midi - 69) / 12.0);
        Note note = Note.fromFrequency(hz);
        return note.letter() + note.octave();
    }

    private static void vertical(GraphicsContext g, double x, double h, String label) {
        g.setStroke(GRID);
        g.setLineWidth(1);
        g.strokeLine(x, TOP, x, h - BOTTOM);
        g.setFill(LABEL);
        g.fillText(label, Math.min(x + 3, g.getCanvas().getWidth() - 48), h - 8);
    }

    private static double y(double midi, double center, double halfRange, double height) {
        return TOP + height * (0.5 - (midi - center) / (2 * halfRange));
    }
}
