package ru.diplom.intonation.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SongEditStoreTest {
    @TempDir Path temporary;

    @Test void savesAndRestoresCorrectionsWithoutDiscardingAlternatives() throws Exception {
        Path song = temporary.resolve("song.mp3");
        Files.writeString(song, "audio fixture");
        var store = new SongEditStore(temporary.resolve("edits"));
        var original = new SongAnalyzer.Result(List.of(new SongAnalyzer.NoteEvent(0, 1, 60)), 2,
                List.of(new SongAnalyzer.Alternative(0, 1, 64, 0.7)));
        var corrected = new SongAnalyzer.Result(List.of(new SongAnalyzer.NoteEvent(0, 1, 64, 0.72)), 2,
                original.alternatives());
        store.save(song, corrected);
        var restored = store.load(song, original);
        assertEquals(64, restored.notes().getFirst().midi());
        assertEquals(0.72, restored.notes().getFirst().confidence());
        assertEquals(original.alternatives(), restored.alternatives());
        Files.writeString(song, "updated song");
        assertEquals(original, store.load(song, original));
    }
}
