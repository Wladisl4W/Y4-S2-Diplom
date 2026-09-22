package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.exercise.Exercise;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseChartStateTest {
    @Test void previewAndAttemptUseStableTargetTimes() {
        ExerciseChartState state = new ExerciseChartState(
                new Exercise("test", "test", List.of(60, 62, 64), 2), List.of(0, 2));
        long now = 10_000_000_000L;
        assertEquals(now + 2_000_000_000L, state.targetStartNanos(now));
        state.start(12_000_000_000L);
        assertTrue(state.running());
        assertEquals(12_000_000_000L, state.targetStartNanos(now));
        assertEquals(18_000_000_000L, state.endNanos());
        assertEquals(List.of(0, 2), state.patternStarts());
        state.finish();
        assertEquals(12_000_000_000L, state.targetStartNanos(20_000_000_000L));
        assertEquals(30_000_000_000L, state.targetStartNanos(28_000_000_000L));
    }
}
