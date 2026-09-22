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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class IntonationApp extends Application {
    private final MicrophoneCapture capture = new MicrophoneCapture();
    private final ConcurrentLinkedQueue<PitchSample> samples = new ConcurrentLinkedQueue<>();
    private final PitchTimeline timeline = new PitchTimeline();
    private final ExerciseHistory history = new ExerciseHistory();
    private ComboBox<MicrophoneCapture.Device> devices;
    private ComboBox<ExerciseCatalog.Option> exercises;
    private Button microphoneButton;
    private Button exerciseButton;
    private Button cancelExerciseButton;
    private Label note;
    private Label cents;
    private Label status;
    private Label target;
    private Label exerciseFeedback;
    private Label recentResults;
    private ProgressBar exerciseProgress;
    private PitchChart chart;
    private ExerciseChartState exerciseChart;
    private ToggleButton liveMode;
    private ToggleButton exerciseMode;
    private VBox exerciseControls;
    private VBox exerciseDetails;
    private Label graphTitle;
    private volatile ExerciseSession session;
    private AnimationTimer timer;
    private double smoothedMidi = Double.NaN;

    private record PitchSample(long timeNanos, Optional<PitchResult> pitch) {}

    @Override public void start(Stage stage) {
        Label brand = label(AppVersion.displayName(), "brand");
        VBox heading = new VBox(brand);

        devices = new ComboBox<>();
        devices.setMaxWidth(Double.MAX_VALUE);
        devices.setPromptText("Выберите микрофон");
        Button refresh = new Button("Обновить");
        refresh.setMinWidth(105);
        refresh.setOnAction(e -> refreshDevices());
        microphoneButton = primaryButton("Начать микрофон");
        microphoneButton.setMinWidth(175);
        microphoneButton.setOnAction(e -> toggleCapture());
        HBox controls = new HBox(10, devices, refresh, microphoneButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(devices, Priority.ALWAYS);
        controls.getStyleClass().add("card");

        ToggleGroup mode = new ToggleGroup();
        liveMode = new ToggleButton("Живой голос");
        exerciseMode = new ToggleButton("Распевки");
        liveMode.setToggleGroup(mode);
        exerciseMode.setToggleGroup(mode);
        liveMode.setSelected(true);
        liveMode.getStyleClass().add("mode-button");
        exerciseMode.getStyleClass().add("mode-button");
        Button songsLater = new Button("Песни · позже");
        songsLater.setDisable(true);
        songsLater.setTooltip(new Tooltip("Импорт песен появится после проверки качества распознавания нот"));
        HBox modeBar = new HBox(8, liveMode, exerciseMode, songsLater);
        modeBar.setAlignment(Pos.CENTER_LEFT);

        exerciseView();
        exerciseControls.setVisible(false);
        exerciseControls.setManaged(false);
        exerciseDetails.setVisible(false);
        exerciseDetails.setManaged(false);
        mode.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) { old.setSelected(true); return; }
            boolean active = selected == exerciseMode;
            exerciseControls.setVisible(active);
            exerciseControls.setManaged(active);
            exerciseDetails.setVisible(active);
            exerciseDetails.setManaged(active);
            graphTitle.setText(active ? "Высота голоса · целевые ноты распевки" : "Высота голоса · последние 10 секунд");
        });

        HBox pitchHeader = new HBox(16);
        pitchHeader.setAlignment(Pos.CENTER_LEFT);
        graphTitle = label("Высота голоса · последние 10 секунд", "section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        note = label("—", "compact-note");
        cents = label("Спойте ноту", "compact-metric");
        pitchHeader.getChildren().addAll(graphTitle, spacer, note, cents);

        Canvas canvas = new Canvas();
        chart = new PitchChart(canvas);
        StackPane graphBox = new StackPane(canvas);
        canvas.setManaged(false);
        graphBox.getStyleClass().add("graph-card");
        graphBox.setMinHeight(250);
        graphBox.setPrefHeight(460);
        canvas.widthProperty().bind(graphBox.widthProperty());
        canvas.heightProperty().bind(graphBox.heightProperty());
        VBox.setVgrow(graphBox, Priority.ALWAYS);
        VBox workspace = new VBox(6, modeBar, exerciseControls, pitchHeader, graphBox, exerciseDetails);
        VBox.setVgrow(workspace, Priority.ALWAYS);
        status = label("Выберите микрофон и нажмите «Начать микрофон»", "muted");
        VBox root = new VBox(6, heading, controls, workspace, status);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 980, 740);
        scene.getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle(AppVersion.displayName());
        stage.setMinWidth(720);
        stage.setMinHeight(650);
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
                updateExercise(now);
                if (exerciseMode.isSelected()) {
                    exerciseChart.advance(now);
                    chart.drawExercise(exerciseChart);
                } else chart.drawLive(timeline, now);
            }
        };
        timer.start();
        stage.show();
    }

    private void exerciseView() {
        List<ExerciseCatalog.Option> presets = ExerciseCatalog.beginners();
        exercises = new ComboBox<>(FXCollections.observableArrayList(presets));
        exercises.setPrefWidth(250);
        exercises.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ExerciseCatalog.Option item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
            }
        });
        exercises.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(ExerciseCatalog.Option item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Выберите распевку" : item.toString());
            }
        });
        exercises.setValue(presets.get(0));
        exerciseChart = chartState(presets.get(0));
        exercises.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                exerciseChart = chartState(selected);
                if (target != null) target.setText("Целевая нота: —");
                if (exerciseProgress != null) exerciseProgress.setProgress(0);
                if (exerciseFeedback != null) exerciseFeedback.setText("Нажмите «Начать распевку», чтобы петь по полосам на графике.");
            }
        });
        Button listen = new Button("Пример");
        listen.setTooltip(new Tooltip("Прослушать выбранный паттерн или набор"));
        listen.setOnAction(e -> TonePlayer.playAsync(exercises.getValue().exercise()));
        exerciseButton = primaryButton("Начать");
        exerciseButton.setTooltip(new Tooltip("Начать выбранную распевку"));
        exerciseButton.setOnAction(e -> startExercise());
        cancelExerciseButton = new Button("Прервать");
        cancelExerciseButton.setDisable(true);
        cancelExerciseButton.setOnAction(e -> cancelExercise("Распевка прервана. Можно начать заново."));
        HBox actions = new HBox(10, exercises, listen, exerciseButton, cancelExerciseButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        target = label("Целевая нота: —", "target-note");
        exerciseFeedback = label("Сначала прослушайте пример. Для занятия включите микрофон.", "muted");
        exerciseFeedback.setWrapText(true);
        exerciseFeedback.setPrefWidth(450);
        HBox.setHgrow(exerciseFeedback, Priority.ALWAYS);
        exerciseProgress = new ProgressBar(0);
        exerciseProgress.setMaxWidth(Double.MAX_VALUE);
        exerciseControls = new VBox(10, actions);
        exerciseControls.getStyleClass().add("exercise-toolbar");
        recentResults = label("Пока нет завершённых занятий", "muted");
        Button historyButton = new Button("История");
        historyButton.setOnAction(e -> showHistory());
        HBox historyRow = new HBox(10, recentResults, historyButton);
        historyRow.setAlignment(Pos.CENTER_LEFT);
        HBox summary = new HBox(16, target, exerciseFeedback);
        summary.setAlignment(Pos.CENTER_LEFT);
        exerciseDetails = new VBox(5, summary, exerciseProgress, historyRow);
        exerciseDetails.getStyleClass().add("exercise-summary");
    }

    private static ExerciseChartState chartState(ExerciseCatalog.Option choice) {
        return new ExerciseChartState(choice.exercise(), choice.starts());
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
            if (session != null) cancelExercise("Микрофон остановлен во время распевки.");
            status.setText("Микрофон остановлен");
            return;
        }
        MicrophoneCapture.Device device = devices.getValue();
        if (device == null) { status.setText("Сначала выберите микрофон"); return; }
        try {
            samples.clear();
            timeline.clear();
            smoothedMidi = Double.NaN;
            capture.start(device, pitch -> {
                // Keep every exercise frame for scoring; stale live-only frames may be dropped.
                while (session == null && samples.size() >= 8) samples.poll();
                samples.add(new PitchSample(System.nanoTime(), pitch));
            }, error -> Platform.runLater(() -> {
                microphoneButton.setText("Начать микрофон");
                devices.setDisable(false);
                if (session != null) cancelExercise("Распевка прервана из-за ошибки микрофона.");
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
        if (session != null) {
            session.accept(sample.timeNanos(), smoothedMidi);
            exerciseChart.add(sample.timeNanos(), smoothedMidi);
        }
    }

    private void startExercise() {
        if (!capture.isRunning()) {
            exerciseFeedback.setText("Сначала включите микрофон в верхней части окна.");
            return;
        }
        session = new ExerciseSession(exercises.getValue().exercise(), System.nanoTime());
        exerciseChart.start(session.startNanos());
        exerciseButton.setDisable(true);
        cancelExerciseButton.setDisable(false);
        exercises.setDisable(true);
        exerciseProgress.setProgress(0);
        exerciseFeedback.setText("Приготовьтесь. Через 2 секунды начнётся первая нота.");
    }

    private void cancelExercise(String message) {
        session = null;
        exerciseChart = chartState(exercises.getValue());
        exerciseButton.setDisable(false);
        cancelExerciseButton.setDisable(true);
        exercises.setDisable(false);
        exerciseProgress.setProgress(0);
        target.setText("Целевая нота: —");
        exerciseFeedback.setText(message);
    }

    private void updateExercise(long now) {
        if (session == null) return;
        if (session.finished(now)) {
            ExerciseSession.Result result = session.result();
            session = null;
            exerciseChart.advance(now);
            exerciseChart.finish();
            exerciseButton.setDisable(false);
            cancelExerciseButton.setDisable(true);
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
        ExerciseCatalog.Option choice = exercises.getValue();
        String part = choice.kind() == ExerciseCatalog.Kind.SET
                ? "Паттерн " + choice.partNumber(index) + "/" + choice.partCount() + " · " : "";
        exerciseFeedback.setText(part + "нота " + choice.noteNumberInPart(index) + "/"
                + choice.notesInPart(index) + " · пойте до смены подсказки");
        exerciseProgress.setProgress((now - session.startNanos()) /
                (session.exercise().durationSeconds() * 1e9));
    }

    private void refreshHistory() {
        try {
            List<String> rows = history.recent(1);
            recentResults.setText(rows.isEmpty() ? "Пока нет завершённых занятий" :
                    "Последнее: " + formatHistoryRow(rows.getFirst(), false));
        } catch (IOException e) {
            recentResults.setText("История недоступна: " + e.getMessage());
        }
    }

    private void showHistory() {
        try {
            List<String> rows = history.recent(30);
            ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                    rows.stream().map(row -> formatHistoryRow(row, true)).toList()));
            list.setPrefSize(560, 300);
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(exerciseButton.getScene().getWindow());
            dialog.setTitle("История занятий");
            dialog.setHeaderText("Последние занятия хранятся только на этом компьютере");
            dialog.getDialogPane().setContent(rows.isEmpty()
                    ? label("Пока нет завершённых занятий", "muted") : list);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        } catch (IOException e) {
            status.setText("Не удалось открыть историю: " + e.getMessage());
        }
    }

    private static String formatHistoryRow(String row, boolean detailed) {
        String[] fields = row.split(",");
        if (fields.length != 5) return "Повреждённая запись истории";
        String date;
        try { date = Instant.parse(fields[0]).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy")); }
        catch (RuntimeException e) { date = fields[0].substring(0, Math.min(10, fields[0].length())); }
        Map<String, String> titles = ExerciseCatalog.beginners().stream()
                .collect(Collectors.toMap(choice -> choice.exercise().id(), ExerciseCatalog.Option::toString));
        String summary = date + " · " + titles.getOrDefault(fields[1], fields[1]) + " · " + fields[2] + "%";
        return detailed ? summary + " · голос: " + fields[3] + "% · ошибка: " + fields[4] + " центов" : summary;
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
