package ru.diplom.intonation.exercise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseCatalogTest {
    @Test void setCombinesPatternsInOrderWithVisibleBoundary() {
        List<ExerciseCatalog.Option> options = ExerciseCatalog.beginners();
        assertEquals(15, options.size());
        ExerciseCatalog.Option set = options.get(4);
        assertEquals(ExerciseCatalog.Kind.SET, set.kind());
        assertEquals(List.of(0, 5, 10, 15), set.starts());
        assertEquals(20, set.exercise().notes().size());
        assertEquals(options.get(0).exercise().notes(), set.exercise().notes().subList(0, 5));
        assertEquals(options.get(1).exercise().notes(), set.exercise().notes().subList(5, 10));
        assertEquals(40, set.exercise().durationSeconds());
        assertEquals(1, set.partNumber(4));
        assertEquals(2, set.partNumber(5));
        assertEquals(1, set.noteNumberInPart(5));
        assertEquals(5, set.notesInPart(5));
        assertEquals(48, options.get(5).exercise().notes().getFirst()); // C3
        assertEquals(72, options.get(10).exercise().notes().getFirst()); // C5
    }
}
