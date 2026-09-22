package ru.diplom.intonation.exercise;

import java.util.List;

public record Exercise(String id, String title, List<Integer> notes, double secondsPerNote) {
    public Exercise {
        notes = List.copyOf(notes);
        if (notes.isEmpty() || secondsPerNote <= 0) throw new IllegalArgumentException("Empty exercise");
    }

    public double durationSeconds() { return notes.size() * secondsPerNote; }

    public static List<Exercise> beginners() {
        return List.of(
                new Exercise("ascending", "Пять нот вверх", List.of(60, 62, 64, 65, 67), 2.0),
                new Exercise("descending", "Пять нот вниз", List.of(67, 65, 64, 62, 60), 2.0)
        );
    }
}
