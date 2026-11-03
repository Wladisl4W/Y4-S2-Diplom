package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.diplom.intonation.exercise.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarmupControllerTest {
    @TempDir Path temp;

    private WarmupController controller(List<String> errors) {
        return new WarmupController(new ExerciseHistory(temp.resolve("progress.csv")),
                new PianoGuide(errors::add), errors::add);
    }

    private static WarmupRoute route() {
        Exercise exercise = new Exercise("test", "Test", List.of(60, Exercise.REST, 62), 1.0);
        ExerciseCatalog.Option option = new ExerciseCatalog.Option(
                ExerciseCatalog.Kind.SET, "Test", exercise, List.of(0, 2));
        return new WarmupRoute(option, List.of(), 60, 0);
    }

    @Test void countdownNoteBoundariesRestAndCompletion() {
        try (WarmupController controller = controller(new ArrayList<>())) {
            controller.start(route(), 0, true);
            assertTrue(controller.isRunning());
            assertEquals(2_000_000_000L, controller.chart().startNanos());
            controller.update(1_000_000_000L, true, false, false);
            assertEquals("Старт через 1", controller.snapshot().target());
            controller.accept(2_100_000_000L, 60);
            controller.update(2_000_000_000L, true, false, false);
            assertEquals("До / C4", controller.snapshot().target());
            controller.update(3_000_000_000L, true, false, false);
            assertEquals("Пауза · следующий паттерн", controller.snapshot().target());
            controller.update(4_000_000_000L, true, false, false);
            assertEquals("Ре / D4", controller.snapshot().target());
            controller.update(5_000_000_000L, true, false, false);
            assertFalse(controller.isRunning());
            assertEquals("Готово", controller.snapshot().target());
            assertEquals(1, controller.snapshot().progress());
            assertTrue(controller.consumeHistoryChanged());
            assertFalse(controller.consumeHistoryChanged());
            controller.update(5_000_000_000L + PitchTimeline.WINDOW_NANOS / 2, true, false, false);
            assertNull(controller.chart());
        }
    }

    @Test void cancellationAndMissingMicrophone() {
        try (WarmupController controller = controller(new ArrayList<>())) {
            controller.start(route(), 0, false);
            controller.update(5_000_000_000L, false, false, false);
            assertTrue(controller.snapshot().feedback().contains("без оценки"));
            assertFalse(controller.consumeHistoryChanged());
            controller.start(route(), 0, true);
            controller.update(2_100_000_000L, false, false, false);
            assertFalse(controller.isRunning());
            assertNull(controller.chart());
            assertTrue(controller.snapshot().feedback().contains("микрофон"));
            controller.start(route(), 0, true);
            controller.cancel("Остановлено");
            assertFalse(controller.isRunning());
            assertEquals("Остановлено", controller.snapshot().feedback());
        }
    }

    @Test void pauseKeepsSessionOnOnePlaybackClock() {
        PlaybackClock clock = new PlaybackClock();
        try (WarmupController controller = controller(new ArrayList<>())) {
            controller.start(route(), clock.time(0), true);
            clock.pause(2_500_000_000L);
            controller.pause();
            assertEquals(2_500_000_000L, clock.time(9_000_000_000L));
            assertTrue(controller.isRunning());
            clock.resume(9_000_000_000L);
            controller.update(clock.time(9_500_000_000L), true, false, false);
            assertEquals("Пауза · следующий паттерн", controller.snapshot().target());
            assertTrue(controller.isRunning());
        }
    }
}
