package ru.diplom.intonation;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import ru.diplom.intonation.audio.Note;

/** Renders a fixed-duration piano-roll-like pitch contour. */
public final class PitchChart {
    private static final Color BACKGROUND = Color.web("#101a2b");
    private static final Color GRID = Color.web("#27354a");
    private static final Color LABEL = Color.web("#aab8cb");
    private static final Color TRACE = Color.web("#51d6b7");
    private static final double LEFT = 48;
    private static final double RIGHT = 16;
    private static final double TOP = 18;
    private static final double BOTTOM = 28;
    private static final double HALF_RANGE = 7;

    private final Canvas canvas;

    public PitchChart(Canvas canvas) { this.canvas = canvas; }

    public void draw(PitchTimeline timeline, long nowNanos) {
        timeline.prune(nowNanos);
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= LEFT + RIGHT || h <= TOP + BOTTOM) return;
        double plotWidth = w - LEFT - RIGHT;
        double plotHeight = h - TOP - BOTTOM;
        double center = timeline.centerMidi();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font(11));

        for (int midi = (int) center - 6; midi <= (int) center + 6; midi++) {
            double y = y(midi, center, plotHeight);
            g.setStroke(midi == (int) center ? Color.web("#425872") : GRID);
            g.setLineWidth(1);
            g.strokeLine(LEFT, y, w - RIGHT, y);
            if (Math.floorMod(midi, 2) == 0) {
                double hz = 440 * Math.pow(2, (midi - 69) / 12.0);
                Note note = Note.fromFrequency(hz);
                g.setFill(LABEL);
                g.fillText(note.letter() + note.octave(), 8, y + 4);
            }
        }

        for (int secondsAgo = 0; secondsAgo <= 10; secondsAgo += 2) {
            double x = LEFT + plotWidth * (1 - secondsAgo / 10.0);
            g.setStroke(GRID);
            g.strokeLine(x, TOP, x, h - BOTTOM);
            g.setFill(LABEL);
            g.fillText(secondsAgo == 0 ? "сейчас" : "−" + secondsAgo + " с", x - (secondsAgo == 0 ? 31 : 12), h - 8);
        }

        g.setStroke(TRACE);
        g.setLineWidth(2.5);
        boolean connected = false;
        double previousX = 0, previousY = 0;
        long previousTime = 0;
        for (PitchTimeline.Point point : timeline.points()) {
            double x = PitchTimeline.x(point.timeNanos(), nowNanos, LEFT, plotWidth);
            double y = y(point.midi(), center, plotHeight);
            boolean visible = point.voiced() && x >= LEFT && x <= w - RIGHT && y >= TOP && y <= h - BOTTOM;
            if (visible && connected && point.timeNanos() - previousTime < 250_000_000L)
                g.strokeLine(previousX, previousY, x, y);
            connected = visible;
            previousX = x;
            previousY = y;
            previousTime = point.timeNanos();
        }
    }

    private static double y(double midi, double center, double plotHeight) {
        return TOP + plotHeight * (0.5 - (midi - center) / (2 * HALF_RANGE));
    }
}
