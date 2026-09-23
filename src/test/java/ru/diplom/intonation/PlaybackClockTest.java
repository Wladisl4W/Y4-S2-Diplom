package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackClockTest {
    @Test void pausesAndResumesWithoutSkippingExerciseTime() {
        PlaybackClock clock = new PlaybackClock();
        assertEquals(100, clock.time(100));
        clock.pause(150);
        assertEquals(150, clock.time(500));
        clock.resume(500);
        assertEquals(200, clock.time(550));
        clock.pause(600);
        clock.resume(700);
        assertEquals(250, clock.time(700));
    }
}
