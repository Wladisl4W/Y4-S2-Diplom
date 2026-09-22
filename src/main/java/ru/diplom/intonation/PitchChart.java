package ru.diplom.intonation;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import ru.diplom.intonation.audio.Note;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

/** Shared pitch graph with a rolling live view and a timed exercise view. */
public final class PitchChart {
    private static final Color BACKGROUND = Color.web("#101a2b");
    private static final Color GRID = Color.web("#27354a");
    private static final Color LABEL = Color.web("#aab8cb");
    private static final Color TRACE = Color.web("#51d6b7");
    private static final Color TARGET = Color.web("#5d7799");
    private static final Color MISS = Color.web("#ff947f");
    private static final double LEFT = 48, RIGHT = 16, TOP = 18, BOTTOM = 28;
    private final Canvas canvas;

    public PitchChart(Canvas canvas) { this.canvas = canvas; }

    public void drawLive(PitchTimeline timeline, long nowNanos) {
        timeline.prune(nowNanos);
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (!usable(w, h)) return;
        double center = timeline.centerMidi();
        GraphicsContext g = prepare(w, h);
        grid(g, w, h, center, 7);
        double width = w - LEFT - RIGHT;
        for (int secondsAgo = 0; secondsAgo <= 10; secondsAgo += 2) {
            double x = LEFT + width * (1 - secondsAgo / 10.0);
            vertical(g, x, h, secondsAgo == 0 ? "сейчас" : "−" + secondsAgo + " с");
        }
        trace(g, timeline.points(), w, h, center, 7,
                time -> PitchTimeline.x(time, nowNanos, LEFT, width), null, 0);
    }

    public void drawExercise(ExerciseChartState state) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (!usable(w, h)) return;
        Exercise exercise = state.exercise();
        int min = exercise.notes().stream().mapToInt(Integer::intValue).min().orElse(60);
        int max = exercise.notes().stream().mapToInt(Integer::intValue).max().orElse(60);
        double center = (min + max) / 2.0;
        double halfRange = Math.max(5, (max - min) / 2.0 + 2);
        GraphicsContext g = prepare(w, h);
        grid(g, w, h, center, halfRange);
        g.setFill(TRACE);
        g.fillText("● в цели", w - 167, 13);
        g.setFill(MISS);
        g.fillText("● мимо", w - 88, 13);
        double secondsPerNote = exercise.secondsPerNote();
        double duration = exercise.durationSeconds();
        // Preview starts at zero. During singing, current time stays near the left third.
        double current = state.startNanos() == 0 ? 0 :
                (state.displayNanos() - state.startNanos()) / 1e9;
        ExerciseViewport viewport = ExerciseViewport.forState(state);
        double plotWidth = w - LEFT - RIGHT;
        double scale = plotWidth / viewport.visibleSeconds();
        double height = h - TOP - BOTTOM;
        g.save();
        g.beginPath();
        g.rect(LEFT, TOP, w - LEFT - RIGHT, height);
        g.closePath();
        g.clip();
        for (int i = 0; i < exercise.notes().size(); i++) {
            double from = i * secondsPerNote;
            double x = viewport.x(from, LEFT, plotWidth);
            double boxWidth = secondsPerNote * scale;
            double y = y(exercise.notes().get(i), center, halfRange, height);
            double toleranceHeight = height / (2 * halfRange); // ±50 cents
            g.setFill(TARGET);
            g.fillRoundRect(x + 2, y - toleranceHeight / 2, boxWidth - 4, toleranceHeight, 7, 7);
            g.setFill(Color.WHITE);
            g.fillText(noteName(exercise.notes().get(i)), x + 9, y + 4);
        }
        if (state.startNanos() != 0) {
            trace(g, state.points(), w, h, center, halfRange,
                    time -> viewport.x((time - state.startNanos()) / 1e9, LEFT, plotWidth),
                    exercise, state.startNanos());
            double cursor = viewport.x(current, LEFT, plotWidth);
            g.setStroke(Color.web("#f4cf70"));
            g.setLineWidth(2);
            g.strokeLine(cursor, TOP, cursor, h - BOTTOM);
        }
        g.restore();
        for (int second = 0; second <= (int) duration; second += 2) {
            double x = viewport.x(second, LEFT, plotWidth);
            if (x >= LEFT && x <= w - RIGHT) vertical(g, x, h, second + " с");
        }
    }

    private GraphicsContext prepare(double w, double h) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font(11));
        return g;
    }

    private void grid(GraphicsContext g, double w, double h, double center, double halfRange) {
        int low = (int) Math.ceil(center - halfRange);
        int high = (int) Math.floor(center + halfRange);
        for (int midi = low; midi <= high; midi++) {
            double y = y(midi, center, halfRange, h - TOP - BOTTOM);
            g.setStroke(GRID);
            g.setLineWidth(1);
            g.strokeLine(LEFT, y, w - RIGHT, y);
            if (Math.floorMod(midi, 2) == 0) {
                g.setFill(LABEL);
                g.fillText(noteName(midi), 8, y + 4);
            }
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

    private void trace(GraphicsContext g, List<PitchTimeline.Point> points, double w, double h,
                       double center, double halfRange, java.util.function.LongToDoubleFunction xOf,
                       Exercise exercise, long startNanos) {
        g.setStroke(TRACE);
        g.setLineWidth(2.5);
        boolean connected = false;
        double previousX = 0, previousY = 0;
        long previousTime = 0;
        double height = h - TOP - BOTTOM;
        for (PitchTimeline.Point point : points) {
            double x = xOf.applyAsDouble(point.timeNanos());
            double y = y(point.midi(), center, halfRange, height);
            boolean visible = point.voiced() && x >= LEFT && x <= w - RIGHT && y >= TOP && y <= h - BOTTOM;
            if (visible && connected && point.timeNanos() - previousTime < 250_000_000L) {
                if (exercise != null) {
                    double seconds = (point.timeNanos() - startNanos) / 1e9;
                    int index = Math.min(exercise.notes().size() - 1,
                            (int) (seconds / exercise.secondsPerNote()));
                    g.setStroke(Math.abs(point.midi() - exercise.notes().get(index)) <= 0.5 ? TRACE : MISS);
                }
                g.strokeLine(previousX, previousY, x, y);
            }
            connected = visible;
            previousX = x;
            previousY = y;
            previousTime = point.timeNanos();
        }
    }

    private static boolean usable(double w, double h) { return w > LEFT + RIGHT && h > TOP + BOTTOM; }
    private static double y(double midi, double center, double halfRange, double height) {
        return TOP + height * (0.5 - (midi - center) / (2 * halfRange));
    }
}
