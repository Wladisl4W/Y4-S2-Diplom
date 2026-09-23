package ru.diplom.intonation.audio;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.PitchTimeline;
import ru.diplom.intonation.PitchViewport;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PitchDetectorTest {
    @Test void detectsLowC2WithMicrophoneRange() {
        YinPitchDetector lowDetector = new YinPitchDetector(44100, 4096, 60, 1000);
        double hz = 440 * Math.pow(2, (36 - 69) / 12.0);
        PitchResult result = lowDetector.detect(sine(hz, 0.4)).orElseThrow();
        assertEquals(36, Note.fromFrequency(result.frequencyHz()).midi());
    }

    private static final int RATE = 44100;
    private static final int SIZE = 4096;
    private final YinPitchDetector detector = new YinPitchDetector(RATE, SIZE, 80, 1000);

    @Test void steadyNotes() {
        checkTone(110, "A2");
        checkTone(220, "A3");
        checkTone(440, "A4");
        checkTone(523.25, "C5");
        checkTone(880, "A5");
    }

    @Test void silenceAndNoise() {
        assertTrue(detector.detect(new float[SIZE]).isEmpty());
        assertTrue(detector.detect(sine(440, 0.004)).isEmpty());
        float[] noise = new float[SIZE];
        Random random = new Random(7);
        for (int i = 0; i < SIZE; i++) noise[i] = (random.nextFloat() - 0.5f) * 0.3f;
        assertTrue(detector.detect(noise).isEmpty());
    }

    @Test void noteAndCents() {
        Note sharp = Note.fromFrequency(440 * Math.pow(2, 25.0 / 1200));
        assertEquals("A", sharp.letter());
        assertEquals(4, sharp.octave());
        assertEquals(25, sharp.cents(), 0.01);
    }

    @Test void timelineUsesActualTimeAndStableScale() {
        PitchTimeline timeline = new PitchTimeline();
        long start = 20_000_000_000L;
        timeline.add(start, 69);
        timeline.add(start + 1_000_000_000L, 69.2);
        timeline.add(start + 2_000_000_000L, Double.NaN);
        assertEquals(69, timeline.centerMidi());
        assertEquals(3, timeline.points().size());
        timeline.add(start + 3_000_000_000L, 72);
        assertEquals(69, timeline.centerMidi());
        timeline.add(start + 4_000_000_000L, 80);
        assertEquals(74, timeline.centerMidi());
        assertEquals(250, PitchViewport.live(start + 5_000_000_000L)
                .x(start + 4_000_000_000L, 50, 500), 0.001);
        timeline.prune(start + 15_000_000_000L);
        assertTrue(timeline.points().isEmpty());
    }

    private void checkTone(double hz, String expected) {
        PitchResult result = detector.detect(sine(hz, 0.4)).orElseThrow();
        Note note = Note.fromFrequency(result.frequencyHz());
        assertEquals(expected, note.letter() + note.octave());
        assertEquals(0, note.cents(), 15);
    }

    private static float[] sine(double hz, double amplitude) {
        float[] samples = new float[SIZE];
        for (int i = 0; i < SIZE; i++) samples[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
        return samples;
    }
}
