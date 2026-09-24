package ru.diplom.intonation.song;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class LeadVocalTranscriberTest {
    @Test void readsLeadAndHarmonyWithoutConfusingWarnings() throws IOException {
        var result = LeadVocalTranscriber.parse("model warning\nD\t2.0\nN\t0.1\t0.5\t60\t0.8\n"
                + "A\t0.1\t0.5\t64\t0.6\n");
        assertEquals(2.0, result.durationSeconds());
        assertEquals(60, result.notes().getFirst().midi());
        assertEquals(0.8, result.notes().getFirst().confidence());
        assertEquals(64, result.alternatives().getFirst().midi());
    }

    @Test void rejectsMissingDuration() {
        assertThrows(IOException.class, () -> LeadVocalTranscriber.parse("N\t0\t1\t60\t0.8"));
    }
}
