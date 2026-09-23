package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PianoCueTest {
    @Test void melodyAndTransitionChordsCanBeMutedIndependently() {
        WarmupRoute route = WarmupRoute.create(ExerciseCatalog.forRoot(36).getFirst(), 36, 2);
        long start = 2_000_000_000L;
        assertEquals(PianoCue.SILENCE, PianoCue.at(route, start - 1, start, true, true));
        assertEquals(new PianoCue(36, -1), PianoCue.at(route, start, start, true, true));
        assertEquals(PianoCue.SILENCE, PianoCue.at(route, start, start, false, true));
        assertEquals(PianoCue.SILENCE, PianoCue.at(route, start + 1_200_000_000L, start, true, true));
        long rest = start + (long) (5 * 1.5e9);
        assertEquals(new PianoCue(-1, 36), PianoCue.at(route, rest, start, true, true));
        assertEquals(new PianoCue(-1, 37), PianoCue.at(route, rest + 900_000_000L, start, true, true));
        assertEquals(PianoCue.SILENCE, PianoCue.at(route, rest, start, true, false));
        assertEquals(PianoCue.SILENCE, PianoCue.at(route,
                start + (long) (route.option().exercise().durationSeconds() * 1e9), start, true, true));
    }
}
