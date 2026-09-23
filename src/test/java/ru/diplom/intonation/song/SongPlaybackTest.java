package ru.diplom.intonation.song;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SongPlaybackTest {
    @Test void independentStemsAndClipping() {
        byte[] backing = {0x10, 0x27}; // 10000
        byte[] voice = {0x20, 0x4e}; // 20000
        byte[] output = new byte[2];
        SongPlayback.mix(backing, voice, output, 2, true, false);
        assertArrayEquals(backing, output);
        SongPlayback.mix(backing, voice, output, 2, false, true);
        assertArrayEquals(voice, output);
        SongPlayback.mix(backing, voice, output, 2, true, true);
        assertEquals(30000, (short) ((output[0] & 0xff) | (output[1] << 8)));
        SongPlayback.mix(backing, voice, output, 2, false, false);
        assertArrayEquals(new byte[2], output);
    }
}
