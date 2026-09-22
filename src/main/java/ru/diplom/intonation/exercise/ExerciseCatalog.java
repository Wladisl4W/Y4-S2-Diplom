package ru.diplom.intonation.exercise;

import java.util.ArrayList;
import java.util.List;

/** User-facing choices: a single pattern or an ordered set of patterns. */
public final class ExerciseCatalog {
    private ExerciseCatalog() {}

    public enum Kind { PATTERN, SET }

    public record Option(Kind kind, String title, Exercise exercise, List<Integer> starts) {
        public Option {
            starts = List.copyOf(starts);
            if (starts.isEmpty() || starts.getFirst() != 0) throw new IllegalArgumentException("First pattern must start at zero");
            for (int i = 1; i < starts.size(); i++) {
                if (starts.get(i) <= starts.get(i - 1) || starts.get(i) >= exercise.notes().size())
                    throw new IllegalArgumentException("Invalid pattern boundary");
            }
        }

        public int partNumber(int noteIndex) {
            int part = 1;
            for (int start : starts) if (noteIndex >= start && start != 0) part++;
            return part;
        }

        public int partCount() { return starts.size(); }

        public int noteNumberInPart(int noteIndex) {
            return noteIndex - starts.get(partNumber(noteIndex) - 1) + 1;
        }

        public int notesInPart(int noteIndex) {
            int part = partNumber(noteIndex);
            int end = part < starts.size() ? starts.get(part) : exercise.notes().size();
            return end - starts.get(part - 1);
        }

        @Override public String toString() {
            return (kind == Kind.PATTERN ? "Паттерн · " : "Набор · ") + title;
        }
    }

    public static List<Option> beginners() {
        List<Exercise> patterns = Exercise.beginners();
        List<Option> choices = new ArrayList<>();
        for (Exercise pattern : patterns)
            choices.add(new Option(Kind.PATTERN, pattern.title(), pattern, List.of(0)));
        List<Integer> combined = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        double secondsPerNote = patterns.getFirst().secondsPerNote();
        for (Exercise pattern : patterns) {
            if (pattern.secondsPerNote() != secondsPerNote)
                throw new IllegalStateException("Set patterns must use the same tempo");
            starts.add(combined.size());
            combined.addAll(pattern.notes());
        }
        Exercise set = new Exercise("basic_set", "Вверх и вниз", combined, secondsPerNote);
        choices.add(new Option(Kind.SET, "Вверх и вниз", set, starts));
        return List.copyOf(choices);
    }
}
