package ru.diplom.intonation;

import org.junit.jupiter.api.Test;
import ru.diplom.intonation.song.SongAnalyzer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SongChartStateTest {
    @Test void targetUsesImportedNoteTimesAndPreservesGaps() {
        var state = new SongChartState(List.of(
                new SongAnalyzer.NoteEvent(1, 0.5, 60),
                new SongAnalyzer.NoteEvent(2, 0.5, 62)), 10_000_000_000L, 3);
        assertEquals(-1, state.targetAt(10_500_000_000L));
        assertEquals(60, state.targetAt(11_200_000_000L));
        assertEquals(-1, state.targetAt(11_700_000_000L));
        assertEquals(62, state.targetAt(12_100_000_000L));
    }
}
