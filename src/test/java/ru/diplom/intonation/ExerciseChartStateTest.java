package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseChartStateTest {
    @Test void previewAndAttemptKeepAlignedSamplesAndFreezeAtEnd() {
        Exercise exercise = new Exercise("test", "test", List.of(60, 62, 64), 2);
        ExerciseChartState state = new ExerciseChartState(exercise);
        assertEquals(0, state.startNanos());
        state.start(10_000_000_000L);
        state.add(9_000_000_000L, 60);
        state.add(10_000_000_000L, 60);
        state.add(12_000_000_000L, Double.NaN);
        state.add(16_000_000_001L, 64);
        assertEquals(2, state.points().size());
        assertFalse(state.points().get(1).voiced());
        state.advance(20_000_000_000L);
        assertEquals(state.endNanos(), state.displayNanos());
        state.finish();
        state.advance(25_000_000_000L);
        assertEquals(state.endNanos(), state.displayNanos());
        state.start(30_000_000_000L);
        assertTrue(state.points().isEmpty());
    }
}
