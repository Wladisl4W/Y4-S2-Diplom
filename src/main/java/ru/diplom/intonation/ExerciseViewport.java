package ru.diplom.intonation;

import ru.diplom.intonation.exercise.Exercise;

/** Time-to-screen mapping shared by targets, recorded voice and playhead. */
public record ExerciseViewport(double windowStartSeconds, double visibleSeconds) {
    public static ExerciseViewport forState(ExerciseChartState state) {
        Exercise exercise = state.exercise();
        double duration = exercise.durationSeconds();
        if (state.startNanos() == 0) return new ExerciseViewport(0, duration);
        double visible = Math.min(duration, Math.max(6, exercise.secondsPerNote() * 3));
        double current = (state.displayNanos() - state.startNanos()) / 1e9;
        return new ExerciseViewport(Math.max(0, current - visible / 3), visible);
    }

    public double x(double seconds, double left, double plotWidth) {
        return left + (seconds - windowStartSeconds) * plotWidth / visibleSeconds;
    }
}
