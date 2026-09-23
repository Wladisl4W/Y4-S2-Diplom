package ru.diplom.intonation.song;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SongAnalyzerTest {
    @Test void ignoresIsolatedFramesAndSeparatesSilence() {
        var notes = SongAnalyzer.groupFrames(List.of(-1, 60, 60, 60, -1, 62, -1, 62, 62, 62));
        assertEquals(2, notes.size());
        assertEquals(60, notes.get(0).midi());
        assertEquals(62, notes.get(1).midi());
        assertTrue(notes.get(0).startSeconds() < notes.get(1).startSeconds());
    }

    @Test void briefDetectionGapDoesNotSplitSustainedNote() {
        var notes = SongAnalyzer.groupFrames(List.of(69, 69, 69, -1, 69, 69, 69));
        assertEquals(1, notes.size());
        assertEquals(69, notes.getFirst().midi());
        assertTrue(notes.getFirst().durationSeconds() > 0.3);
    }
}
