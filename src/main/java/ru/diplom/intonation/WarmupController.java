package ru.diplom.intonation;

import ru.diplom.intonation.audio.Note;
import ru.diplom.intonation.exercise.*;

import java.io.IOException;
import java.util.function.Consumer;

/** Session state, piano cues and scoring; the view only renders Snapshot. */
final class WarmupController implements AutoCloseable {
    record Snapshot(boolean running, boolean showPiano, boolean transitionsAvailable,
                    String target, String feedback, double progress, ExerciseChartState chart) {}

    private final ExerciseHistory history;
    private final PianoGuide piano;
    private final Consumer<String> status;
    private volatile ExerciseSession session;
    private WarmupRoute route;
    private ExerciseChartState chart;
    private boolean scoringEnabled;
    private String target = "Цель: —";
    private String feedback = "Выберите распевку в библиотеке или просто пойте в микрофон.";
    private double progress;
    private boolean historyChanged;

    WarmupController(ExerciseHistory history, PianoGuide piano, Consumer<String> status) {
        this.history = history;
        this.piano = piano;
        this.status = status;
    }

    boolean isRunning() { return session != null; }
    ExerciseChartState chart() { return chart; }
    boolean consumeHistoryChanged() {
        boolean changed = historyChanged;
        historyChanged = false;
        return changed;
    }

    Snapshot snapshot() {
        return new Snapshot(session != null, session != null, route != null && !route.transitions().isEmpty(),
                target, feedback, progress, chart);
    }

    void start(WarmupRoute selected, long now, boolean microphoneAvailable) {
        piano.silence();
        route = selected;
        scoringEnabled = microphoneAvailable;
        session = new ExerciseSession(selected.option().exercise(), now);
        chart = new ExerciseChartState(selected.option().exercise(), selected.option().starts());
        chart.start(session.startNanos());
        target = "Цель: —";
        progress = 0;
        feedback = microphoneAvailable
                ? "Приготовьтесь. Через 2 секунды начнётся первая нота."
                : "Микрофон недоступен. Ноты видны, но оценка голоса не будет получена.";
    }

    void accept(long timeNanos, double midi) {
        if (session != null) session.accept(timeNanos, midi);
    }

    void cancel(String message) {
        session = null;
        route = null;
        scoringEnabled = false;
        piano.silence();
        chart = null;
        target = "Целевая нота: —";
        feedback = message;
        progress = 0;
    }

    void pause() { piano.silence(); }

    void updatePiano(long now, boolean paused, boolean notePiano, boolean transitionPiano) {
        if (paused || session == null || route == null) { piano.silence(); return; }
        piano.update(route, now, session.startNanos(), notePiano, transitionPiano);
    }

    void update(long now, boolean microphoneAvailable, boolean notePiano, boolean transitionPiano) {
        if (session == null) {
            if (chart != null && chart.expired(now)) chart = null;
            return;
        }
        if (scoringEnabled && !microphoneAvailable) {
            cancel("Распевка прервана: микрофон перестал передавать звук.");
            return;
        }
        if (session.finished(now)) {
            ExerciseSession.Result result = session.result();
            session = null;
            route = null;
            piano.silence();
            chart.finish();
            target = "Готово";
            progress = 1;
            if (scoringEnabled) {
                feedback = "Попадание: " + result.score() + "% · голос звучал: "
                        + result.coverage() + "% · среднее отклонение: " + result.averageErrorCents() + " центов";
                try { history.append(result); historyChanged = true; }
                catch (IOException error) { status.accept("Не удалось сохранить историю: " + error.getMessage()); }
            } else feedback = "Маршрут завершён без оценки: микрофон не был подключён.";
            scoringEnabled = false;
            return;
        }
        int index = session.noteIndex(now);
        if (index < 0) {
            target = "Старт через " + session.countdown(now);
            return;
        }
        updatePiano(now, false, notePiano, transitionPiano);
        progress = (now - session.startNanos()) / (session.exercise().durationSeconds() * 1e9);
        int midi = session.exercise().notes().get(index);
        if (midi == Exercise.REST) {
            WarmupRoute.Transition transition = route.transitionAt(index);
            if (transition == null) {
                target = "Пауза · следующий паттерн";
                feedback = "Спокойно вдохните и подготовьтесь к следующему рисунку";
            } else {
                target = "Переход " + (transition.toRoot() > transition.fromRoot() ? "↑" : "↓");
                feedback = "Слушайте два аккорда и приготовьтесь к следующей высоте";
            }
            return;
        }
        target = Note.fromFrequency(440 * Math.pow(2, (midi - 69) / 12.0)).display();
        ExerciseCatalog.Option choice = route.option();
        String part = choice.partCount() > 1
                ? (route.climbSemitones() > 0 ? "Высота " : "Паттерн ")
                    + choice.partNumber(index) + "/" + choice.partCount() + " · " : "";
        feedback = part + "нота " + choice.noteNumberInPart(index) + "/"
                + choice.notesInPart(index) + " · пойте до смены подсказки";
    }

    @Override public void close() { piano.close(); }
}
