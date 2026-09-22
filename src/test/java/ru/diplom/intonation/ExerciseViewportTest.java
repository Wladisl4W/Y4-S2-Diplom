package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseViewportTest {
    @Test void previewShowsWholeExerciseAndRunningPlayheadMovesToLeftThird() {
        ExerciseChartState state = new ExerciseChartState(
                new Exercise("scale", "Scale", List.of(60, 62, 64, 65, 67), 2));
        ExerciseViewport preview = ExerciseViewport.forState(state);
        assertEquals(10, preview.visibleSeconds());
        assertEquals(1000, preview.x(10, 0, 1000));
        state.start(10_000_000_000L);
        state.advance(16_000_000_000L);
        ExerciseViewport moving = ExerciseViewport.forState(state);
        assertEquals(6, moving.visibleSeconds());
        assertEquals(4, moving.windowStartSeconds());
        assertEquals(1000.0 / 3, moving.x(6, 0, 1000), 0.01);
        assertEquals(moving.x(6, 0, 1000),
                moving.x((16_000_000_000L - state.startNanos()) / 1e9, 0, 1000));
    }
}
