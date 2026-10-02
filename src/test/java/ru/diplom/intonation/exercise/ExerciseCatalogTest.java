package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseCatalogTest {
    @Test void chromaticRootKeepsTheSameIntervalPatterns() {
        List<ExerciseCatalog.Option> c = ExerciseCatalog.forRoot(60);
        List<ExerciseCatalog.Option> sharp = ExerciseCatalog.forRoot(61);
        assertEquals("C♯4", ExerciseCatalog.noteName(61));
        assertEquals(5, sharp.size());
        for (int choice = 0; choice < c.size(); choice++) {
            assertEquals(c.get(choice).exercise().notes().stream()
                            .map(note -> note == Exercise.REST ? Exercise.REST : note + 1).toList(),
                    sharp.get(choice).exercise().notes());
            assertEquals(c.get(choice).starts(), sharp.get(choice).starts());
        }
    }

    @Test void setCombinesPatternsInOrderWithVisibleBoundary() {
        List<ExerciseCatalog.Option> options = ExerciseCatalog.beginners();
        assertEquals(15, options.size());
        ExerciseCatalog.Option set = options.get(4);
        assertEquals(ExerciseCatalog.Kind.SET, set.kind());
        assertEquals(List.of(0, 6, 12, 18), set.starts());
        assertEquals(23, set.exercise().notes().size());
        assertEquals(options.get(0).exercise().notes(), set.exercise().notes().subList(0, 5));
        assertEquals(Exercise.REST, set.exercise().notes().get(5));
        assertEquals(options.get(1).exercise().notes(), set.exercise().notes().subList(6, 11));
        assertEquals(46, set.exercise().durationSeconds());
        assertEquals(1, set.partNumber(4));
        assertEquals(2, set.partNumber(6));
        assertEquals(1, set.noteNumberInPart(6));
        assertEquals(5, set.notesInPart(6));
        assertEquals(48, options.get(5).exercise().notes().getFirst()); // C3
        assertEquals(72, options.get(10).exercise().notes().getFirst()); // C5
    }
}
