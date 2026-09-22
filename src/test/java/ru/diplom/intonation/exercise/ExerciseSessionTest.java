package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseSessionTest {
    @Test void countdownTargetsAndScore() {
        Exercise exercise = new Exercise("test", "Test", List.of(60, 62), 1.0);
        ExerciseSession session = new ExerciseSession(exercise, 0);
        assertEquals(2, session.countdown(0));
        assertEquals(-1, session.noteIndex(1_000_000_000L));
        assertEquals(0, session.noteIndex(2_100_000_000L));
        assertEquals(1, session.noteIndex(3_100_000_000L));
        session.accept(2_100_000_000L, 60.1);
        session.accept(2_200_000_000L, Double.NaN);
        session.accept(3_100_000_000L, 61.0);
        assertTrue(session.finished(4_000_000_000L));
        ExerciseSession.Result result = session.result();
        assertEquals(33, result.score());
        assertEquals(67, result.coverage());
        assertEquals(55, result.averageErrorCents());
    }
}
