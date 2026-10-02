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
        assertEquals(5, result.score());
        assertEquals(10, result.coverage());
        assertEquals(55, result.averageErrorCents());
    }

    @Test void missingMicrophoneFramesCannotEarnPerfectScore() {
        Exercise exercise = new Exercise("long", "Long", List.of(60), 10.0);
        ExerciseSession session = new ExerciseSession(exercise, 0);
        for (int slot = 0; slot < 10; slot++)
            session.accept(session.startNanos() + slot * 100_000_000L, 60);
        assertEquals(10, session.result().score());
        assertEquals(10, session.result().coverage());
    }

    @Test void extraFramesInsideOneIntervalDoNotIncreaseScore() {
        Exercise exercise = new Exercise("one", "One", List.of(60), 1.0);
        ExerciseSession session = new ExerciseSession(exercise, 0);
        for (int frame = 0; frame < 20; frame++)
            session.accept(session.startNanos() + frame * 1_000_000L, 60);
        assertEquals(10, session.result().score());
        assertEquals(10, session.result().coverage());
    }
}
