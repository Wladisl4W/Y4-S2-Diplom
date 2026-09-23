package ru.diplom.intonation.exercise;

import java.util.ArrayList;
import java.util.List;

/** Repeats one vocal pattern in neighboring keys, then returns to the starting key. */
public record WarmupRoute(ExerciseCatalog.Option option, List<Transition> transitions,
                          int rootMidi, int climbSemitones) {
    public record Transition(int restIndex, int fromRoot, int toRoot) {}

    public WarmupRoute {
        transitions = List.copyOf(transitions);
    }

    public static WarmupRoute create(ExerciseCatalog.Option choice, int rootMidi, int climbSemitones) {
        if (climbSemitones != 0 && climbSemitones != 2 && climbSemitones != 4)
            throw new IllegalArgumentException("Unsupported climb");
        if (choice.kind() == ExerciseCatalog.Kind.SET || climbSemitones == 0)
            return new WarmupRoute(choice, List.of(), rootMidi, 0);
        if (choice.exercise().notes().stream().mapToInt(Integer::intValue).max().orElse(0)
                + climbSemitones > 83)
            throw new IllegalArgumentException("Pattern exceeds microphone pitch range");

        List<Integer> shifts = new ArrayList<>();
        for (int step = 0; step <= climbSemitones; step++) shifts.add(step);
        for (int step = climbSemitones - 1; step >= 0; step--) shifts.add(step);
        List<Integer> notes = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        for (int i = 0; i < shifts.size(); i++) {
            if (i > 0) {
                int restIndex = notes.size();
                notes.add(Exercise.REST);
                transitions.add(new Transition(restIndex, rootMidi + shifts.get(i - 1),
                        rootMidi + shifts.get(i)));
            }
            starts.add(notes.size());
            int shift = shifts.get(i);
            choice.exercise().notes().forEach(note -> notes.add(note + shift));
        }
        Exercise base = choice.exercise();
        Exercise expanded = new Exercise(base.id() + "_route" + climbSemitones,
                base.title() + " · вверх и вниз", notes, 1.5);
        ExerciseCatalog.Option option = new ExerciseCatalog.Option(choice.kind(), expanded.title(),
                expanded, starts);
        return new WarmupRoute(option, transitions, rootMidi, climbSemitones);
    }

    public Transition transitionAt(int noteIndex) {
        for (Transition transition : transitions)
            if (transition.restIndex() == noteIndex) return transition;
        return null;
    }
}
