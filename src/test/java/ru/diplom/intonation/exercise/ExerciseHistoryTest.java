package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseHistoryTest {
    @TempDir Path temporary;

    @Test void savesAndReturnsMostRecentResultsFirst() throws Exception {
        ExerciseHistory history = new ExerciseHistory(temporary.resolve("progress.csv"));
        history.append(new ExerciseSession.Result("ascending", 70, 80, 21));
        history.append(new ExerciseSession.Result("basic_set_c3", 90, 95, 12));
        assertEquals(2, history.recent(5).size());
        assertTrue(history.recent(1).getFirst().contains(",basic_set_c3,90,95,12"));
        assertTrue(history.recent(2).get(1).contains(",ascending,70,80,21"));
    }
}
