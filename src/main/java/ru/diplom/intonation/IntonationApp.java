package ru.diplom.intonation;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import ru.diplom.intonation.audio.*;
import ru.diplom.intonation.exercise.*;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class IntonationApp extends Application {
    private final MicrophoneCapture capture = new MicrophoneCapture();
    private final ConcurrentLinkedQueue<PitchSample> samples = new ConcurrentLinkedQueue<>();
    private final PitchTimeline timeline = new PitchTimeline();
    private final ExerciseHistory history = new ExerciseHistory();
    private ComboBox<MicrophoneCapture.Device> devices;
    private ComboBox<Exercise> exercises;
    private Button microphoneButton;
    private Button exerciseButton;
    private Label note;
    private Label cents;
    private Label status;
    private Label target;
    private Label exerciseFeedback;
    private Label recentResults;
    private ProgressBar exerciseProgress;
    private PitchChart chart;
    private ExerciseSession session;
    private AnimationTimer timer;
    private double smoothedMidi = Double.NaN;

    private record PitchSample(long timeNanos, Optional<PitchResult> pitch) {}

    @Override public void start(Stage stage) {
        Label brand = label("Тренировка интонации", "brand");
        Label subtitle = label("Пойте точнее. Видьте свой прогресс.", "subtitle");
        VBox heading = new VBox(3, brand, subtitle);

        devices = new ComboBox<>();
        devices.setMaxWidth(Double.MAX_VALUE);
        devices.setPromptText("Выберите микрофон");
        Button refresh = new Button("Обновить");
        refresh.setOnAction(e -> refreshDevices());
        microphoneButton = primaryButton("Начать микрофон");
        microphoneButton.setOnAction(e -> toggleCapture());
        HBox controls = new HBox(10, devices, refresh, microphoneButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(devices, Priority.ALWAYS);
        controls.getStyleClass().add("card");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Живой голос", liveView()),
                new Tab("Распевки", exerciseView()),
                new Tab("Песни", songView())
        );
        VBox.setVgrow(tabs, Priority.ALWAYS);

        status = label("Выберите микрофон и нажмите «Начать микрофон»", "muted");
        VBox root = new VBox(15, heading, controls, tabs, status);
        root.setPadding(new Insets(22));
        Scene scene = new Scene(root, 980, 700);
        scene.getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Тренировка интонации");
        stage.setMinWidth(720);
        stage.setMinHeight(580);
        stage.setOnCloseRequest(e -> capture.stop());

        refreshDevices();
        refreshHistory();
        timer = new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (now - previous < 33_000_000) return;
                previous = now;
                PitchSample sample;
                while ((sample = samples.poll()) != null) updatePitch(sample);
                chart.draw(timeline, now);
                updateExercise(now);
            }
        };
        timer.start();
        stage.show();
    }

    private VBox liveView() {
        note = label("—", "note-value");
        cents = label("Спойте ноту", "metric");
        VBox current = new VBox(4, label("СЕЙЧАС", "muted"), note, cents);
        current.setAlignment(Pos.CENTER_LEFT);
        current.getStyleClass().add("card");

        Canvas canvas = new Canvas(900, 310);
        chart = new PitchChart(canvas);
        StackPane graphBox = new StackPane(canvas);
        graphBox.getStyleClass().add("graph-card");
        canvas.widthProperty().bind(graphBox.widthProperty());
        canvas.heightProperty().bind(graphBox.heightProperty());
        VBox.setVgrow(graphBox, Priority.ALWAYS);
        VBox view = new VBox(15, current, label("Высота голоса · последние 10 секунд", "section-title"), graphBox);
        view.setPadding(new Insets(12, 0, 0, 0));
        return view;
    }

    private VBox exerciseView() {
        List<Exercise> presets = Exercise.beginners();
        exercises = new ComboBox<>(FXCollections.observableArrayList(presets));
        exercises.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Exercise item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.title());
            }
        });
        exercises.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Exercise item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Выберите распевку" : item.title());
            }
        });
        exercises.setValue(presets.get(0));
        Button listen = new Button("Прослушать пример");
        listen.setOnAction(e -> TonePlayer.playAsync(exercises.getValue()));
        exerciseButton = primaryButton("Начать распевку");
        exerciseButton.setOnAction(e -> startExercise());
        HBox actions = new HBox(10, exercises, listen, exerciseButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        target = label("Целевая нота: —", "note-value");
        exerciseFeedback = label("Сначала прослушайте пример. Для занятия включите микрофон.", "muted");
        exerciseProgress = new ProgressBar(0);
        exerciseProgress.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(16, label("Распевка", "section-title"), actions, target,
                exerciseFeedback, exerciseProgress);
        card.getStyleClass().add("card");
        recentResults = label("Пока нет завершённых занятий", "muted");
        VBox historyCard = new VBox(10, label("Последние занятия", "section-title"), recentResults);
        historyCard.getStyleClass().add("card");
        VBox view = new VBox(15, card, historyCard);
        view.setPadding(new Insets(12, 0, 0, 0));
        return view;
    }

    private VBox songView() {
        Label text = label("Разбор MP3 находится на этапе проверки точности моделей.\n"
                + "После проверки здесь появятся загрузка песни и пение по нотной линии.", "muted");
        text.setWrapText(true);
        VBox card = new VBox(12, label("Песни", "section-title"), text);
        card.getStyleClass().add("card");
        VBox view = new VBox(card);
        view.setPadding(new Insets(12, 0, 0, 0));
        return view;
    }

    private void refreshDevices() {
        MicrophoneCapture.Device selected = devices.getValue();
        List<MicrophoneCapture.Device> found = MicrophoneCapture.devices();
        devices.setItems(FXCollections.observableArrayList(found));
        if (selected != null) found.stream().filter(d -> d.info().equals(selected.info())).findFirst()
                .ifPresent(devices::setValue);
        if (devices.getValue() == null && !found.isEmpty()) devices.setValue(found.get(0));
        if (found.isEmpty() && status != null) status.setText("Микрофон не найден. Подключите устройство и обновите список.");
    }

    private void toggleCapture() {
        if (capture.isRunning()) {
            capture.stop();
            samples.clear();
            updatePitch(new PitchSample(System.nanoTime(), Optional.empty()));
            microphoneButton.setText("Начать микрофон");
            devices.setDisable(false);
            session = null;
            exerciseButton.setDisable(false);
            status.setText("Микрофон остановлен");
            return;
        }
        MicrophoneCapture.Device device = devices.getValue();
        if (device == null) { status.setText("Сначала выберите микрофон"); return; }
        try {
            samples.clear();
            timeline.clear();
            smoothedMidi = Double.NaN;
            capture.start(device, pitch -> samples.add(new PitchSample(System.nanoTime(), pitch)), error -> Platform.runLater(() -> {
                microphoneButton.setText("Начать микрофон");
                devices.setDisable(false);
                session = null;
                exerciseButton.setDisable(false);
                status.setText("Ошибка микрофона: " + error);
            }));
            microphoneButton.setText("Остановить микрофон");
            devices.setDisable(true);
            status.setText("Слушаю микрофон · звук остаётся на этом устройстве");
        } catch (LineUnavailableException | IllegalArgumentException e) {
            status.setText("Не удалось открыть микрофон: " + e.getMessage());
        }
    }

    private void updatePitch(PitchSample sample) {
        Optional<PitchResult> result = sample.pitch();
        if (result.isEmpty()) {
            smoothedMidi = Double.NaN;
            note.setText("—");
            cents.setText("Нет устойчивой ноты");
            timeline.add(sample.timeNanos(), Double.NaN);
        } else {
            double hz = result.get().frequencyHz();
            double midi = 69 + 12 * Math.log(hz / 440.0) / Math.log(2);
            smoothedMidi = Double.isNaN(smoothedMidi) || Math.abs(midi - smoothedMidi) > 2
                    ? midi : smoothedMidi * 0.65 + midi * 0.35;
            double smoothHz = 440 * Math.pow(2, (smoothedMidi - 69) / 12);
            Note pitch = Note.fromFrequency(smoothHz);
            note.setText(pitch.display());
            cents.setText(String.format("%+.0f центов · %.1f Гц", pitch.cents(), smoothHz));
            timeline.add(sample.timeNanos(), smoothedMidi);
        }
        if (session != null) session.accept(sample.timeNanos(), smoothedMidi);
    }

    private void startExercise() {
        if (!capture.isRunning()) {
            exerciseFeedback.setText("Сначала включите микрофон в верхней части окна.");
            return;
        }
        session = new ExerciseSession(exercises.getValue(), System.nanoTime());
        exerciseButton.setDisable(true);
        exercises.setDisable(true);
        exerciseProgress.setProgress(0);
        exerciseFeedback.setText("Приготовьтесь. Через 2 секунды начнётся первая нота.");
    }

    private void updateExercise(long now) {
        if (session == null) return;
        if (session.finished(now)) {
            ExerciseSession.Result result = session.result();
            session = null;
            exerciseButton.setDisable(false);
            exercises.setDisable(false);
            target.setText("Готово");
            exerciseProgress.setProgress(1);
            exerciseFeedback.setText("Попадание: " + result.score() + "% · голос звучал: "
                    + result.coverage() + "% · среднее отклонение: " + result.averageErrorCents() + " центов");
            try { history.append(result); refreshHistory(); }
            catch (IOException e) { status.setText("Не удалось сохранить историю: " + e.getMessage()); }
            return;
        }
        int index = session.noteIndex(now);
        if (index < 0) {
            target.setText("Старт через " + session.countdown(now));
            return;
        }
        int midi = session.exercise().notes().get(index);
        double hz = 440 * Math.pow(2, (midi - 69) / 12.0);
        Note expected = Note.fromFrequency(hz);
        target.setText(expected.display());
        exerciseFeedback.setText("Нота " + (index + 1) + " из " + session.exercise().notes().size()
                + " · пойте её до смены подсказки");
        exerciseProgress.setProgress((now - session.startNanos()) /
                (session.exercise().durationSeconds() * 1e9));
    }

    private void refreshHistory() {
        try {
            List<String> rows = history.recent(5);
            if (rows.isEmpty()) return;
            StringBuilder text = new StringBuilder();
            for (String row : rows) {
                String[] fields = row.split(",");
                if (fields.length == 5) text.append(fields[0], 0, Math.min(10, fields[0].length()))
                        .append(" · ").append(fields[1]).append(" · ").append(fields[2]).append("%\n");
            }
            recentResults.setText(text.toString().stripTrailing());
        } catch (IOException e) {
            recentResults.setText("История недоступна: " + e.getMessage());
        }
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    @Override public void stop() {
        timer.stop();
        capture.stop();
    }

    public static void main(String[] args) { launch(args); }
}
