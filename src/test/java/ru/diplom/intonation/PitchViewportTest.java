package ru.diplom.intonation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PitchViewportTest {
    @Test void microphoneAndTargetsShareOneTimeAxis() {
        long now = 30_000_000_000L;
        PitchViewport live = PitchViewport.live(now);
        PitchViewport targets = PitchViewport.withTargets(now);
        assertEquals(1000, live.x(now, 0, 1000));
        assertEquals(1000.0 / 3, targets.x(now, 0, 1000), 0.001);
        assertEquals(targets.x(now + 2_000_000_000L, 0, 1000),
                targets.x(now, 0, 1000) + 200, 0.001);
    }
}
