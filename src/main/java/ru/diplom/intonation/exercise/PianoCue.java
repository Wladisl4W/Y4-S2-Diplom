package ru.diplom.intonation.exercise;

/** Pure timing decision for the two optional piano channels. */
public record PianoCue(int melodyNote, int chordRoot) {
    public static final PianoCue SILENCE = new PianoCue(-1, -1);

    public static PianoCue at(WarmupRoute route, long nowNanos, long startNanos,
                              boolean melodyEnabled, boolean transitionsEnabled) {
        Exercise exercise = route.option().exercise();
        if (nowNanos < startNanos) return SILENCE;
        double position = (nowNanos - startNanos) / (exercise.secondsPerNote() * 1e9);
        int index = (int) position;
        if (index >= exercise.notes().size()) return SILENCE;
        int midi = exercise.notes().get(index);
        double progress = position - index;
        if (midi != Exercise.REST)
            return new PianoCue(melodyEnabled && progress < 0.7 ? midi : -1, -1);
        WarmupRoute.Transition transition = route.transitionAt(index);
        return new PianoCue(-1, transitionsEnabled && transition != null
                ? progress < 0.5 ? transition.fromRoot() : transition.toRoot() : -1);
    }
}
