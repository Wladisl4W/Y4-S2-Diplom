package ru.diplom.intonation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PitchSampleBufferTest {
    @Test void keepsOnlyNewest64FramesInOrder() {
        PitchSampleBuffer<Integer> buffer = new PitchSampleBuffer<>(64);
        for (int i = 0; i < 100; i++) buffer.add(i);
        assertEquals(64, buffer.size());
        for (int i = 36; i < 100; i++) assertEquals(i, buffer.poll());
        assertNull(buffer.poll());
    }

    @Test void clearDiscardsFramesBeforeNewAttemptOrDevice() {
        PitchSampleBuffer<Integer> buffer = new PitchSampleBuffer<>(64);
        buffer.add(1);
        buffer.add(2);
        buffer.clear();
        buffer.add(3);
        assertEquals(3, buffer.poll());
        assertNull(buffer.poll());
    }

    @Test void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new PitchSampleBuffer<>(0));
    }
}
