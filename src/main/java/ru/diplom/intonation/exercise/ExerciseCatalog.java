package ru.diplom.intonation.exercise;

import java.util.ArrayList;
import java.util.List;

/** User-facing choices: a single pattern or an ordered set of patterns. */
public final class ExerciseCatalog {
    private ExerciseCatalog() {}

    public enum Kind {
        PATTERN("Паттерн распевки"), SET("Целый набор");
        private final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

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
        for (int octaveShift : new int[]{0, -12, 12}) {
            String root = octaveShift == 0 ? "C4" : (octaveShift < 0 ? "C3" : "C5");
            String suffix = octaveShift == 0 ? "" : "_" + root.toLowerCase();
            List<Integer> combined = new ArrayList<>();
            List<Integer> starts = new ArrayList<>();
            double secondsPerNote = patterns.getFirst().secondsPerNote();
            for (Exercise pattern : patterns) {
                if (pattern.secondsPerNote() != secondsPerNote)
                    throw new IllegalStateException("Set patterns must use the same tempo");
                List<Integer> shifted = pattern.notes().stream().map(note -> note + octaveShift).toList();
                Exercise variant = new Exercise(pattern.id() + suffix,
                        pattern.title() + " · " + root, shifted, secondsPerNote);
                choices.add(new Option(Kind.PATTERN, variant.title(), variant, List.of(0)));
                starts.add(combined.size());
                combined.addAll(shifted);
            }
            Exercise set = new Exercise("basic_set" + suffix,
                    "Основной набор · " + root, combined, secondsPerNote);
            choices.add(new Option(Kind.SET, set.title(), set, starts));
        }
        return List.copyOf(choices);
    }
}
