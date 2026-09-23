package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WarmupRouteTest {
    @Test void selectedPaceControlsSingleAndRoutedExercises() {
        ExerciseCatalog.Option base = ExerciseCatalog.forRoot(36).getFirst();
        WarmupRoute single = WarmupRoute.create(base, 36, 0, 2.5);
        WarmupRoute routed = WarmupRoute.create(base, 36, 2, 1.0);
        assertEquals(2.5, single.option().exercise().secondsPerNote());
        assertEquals(12.5, single.option().exercise().durationSeconds());
        assertEquals(1.0, routed.option().exercise().secondsPerNote());
        assertEquals(29.0, routed.option().exercise().durationSeconds());
        assertEquals(1, new ExerciseSession(routed.option().exercise(), 0)
                .noteIndex(2_000_000_000L + 1_000_000_000L));
    }

    @Test void fourSemitoneRouteReturnsToItsStartingPitch() {
        ExerciseCatalog.Option base = ExerciseCatalog.forRoot(36).getFirst();
        WarmupRoute route = WarmupRoute.create(base, 36, 4);
        assertEquals(List.of(0, 1, 2, 3, 4, 3, 2, 1, 0), route.option().starts().stream()
                .map(index -> route.option().exercise().notes().get(index) - 36).toList());
        assertEquals(8, route.transitions().size());
        assertEquals(53, route.option().exercise().notes().size());
    }

    @Test void risesBySemitonesAndReturnsWithUnscoredTransitions() {
        ExerciseCatalog.Option base = ExerciseCatalog.forRoot(36).getFirst();
        WarmupRoute route = WarmupRoute.create(base, 36, 2);
        assertEquals(List.of(36, 38, 40, 41, 43), route.option().exercise().notes().subList(0, 5));
        assertEquals(List.of(0, 1, 2, 1, 0), route.option().starts().stream()
                .map(index -> route.option().exercise().notes().get(index) - 36).toList());
        assertEquals(4, route.transitions().size());
        assertEquals(Exercise.REST, route.option().exercise().notes().get(5));
        assertEquals(new WarmupRoute.Transition(5, 36, 37), route.transitionAt(5));
        assertEquals(29, route.option().exercise().notes().size());
        assertEquals(43.5, route.option().exercise().durationSeconds());
    }

    @Test void transitionIsExcludedFromScore() {
        ExerciseCatalog.Option base = ExerciseCatalog.forRoot(36).getFirst();
        WarmupRoute route = WarmupRoute.create(base, 36, 2);
        ExerciseSession session = new ExerciseSession(route.option().exercise(), 0);
        long restTime = session.startNanos() + (long) (5 * 1.5e9) + 100_000_000L;
        session.accept(restTime, 36);
        assertEquals(0, session.result().coverage());
        assertEquals(0, session.result().score());
    }
}
