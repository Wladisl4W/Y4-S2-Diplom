package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseChartStateTest {
    @Test void finishedAttemptNeverReappearsAsPreview() {
        ExerciseChartState state = new ExerciseChartState(
                new Exercise("test", "test", List.of(60, 62, 64), 2), List.of(0, 2));
        long now = 10_000_000_000L;
        assertEquals(0, state.targetStartNanos());
        state.start(12_000_000_000L);
        assertTrue(state.running());
        assertEquals(12_000_000_000L, state.targetStartNanos());
        assertEquals(18_000_000_000L, state.endNanos());
        assertEquals(List.of(0, 2), state.patternStarts());
        state.finish();
        assertEquals(12_000_000_000L, state.targetStartNanos());
        assertFalse(state.expired(22_999_999_999L));
        assertTrue(state.expired(23_000_000_000L));
        assertEquals(12_000_000_000L, state.targetStartNanos());
    }
}
