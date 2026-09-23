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
import java.util.prefs.Preferences;

public final class IntonationApp extends Application {
    private final MicrophoneCapture capture = new MicrophoneCapture();
    private final ConcurrentLinkedQueue<PitchSample> samples = new ConcurrentLinkedQueue<>();
    private final PitchTimeline timeline = new PitchTimeline();
    private final ExerciseHistory history = new ExerciseHistory();
    private final PlaybackClock clock = new PlaybackClock();
    private final Preferences preferences = Preferences.userNodeForPackage(IntonationApp.class);
    private static final String MICROPHONE_KEY = "microphone";
    private ExerciseCatalog.Option selectedExercise;
    private ComboBox<String> exercisePitch;
    private FlowPane patternCards;
    private Label selectionTitle;
    private VBox livePage;
    private VBox libraryPage;
    private Button transportButton;
    private MicrophoneCapture.Device selectedDevice;
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
    private VBox exerciseDetails;
    private volatile ExerciseSession session;
    private AnimationTimer timer;
    private double smoothedMidi = Double.NaN;

    private record PitchSample(long timeNanos, Optional<PitchResult> pitch) {}

    @Override public void start(Stage stage) {
        Label brand = label(AppVersion.displayName(), "brand");

        Region headingSpacer = new Region();
        HBox.setHgrow(headingSpacer, Priority.ALWAYS);
        transportButton = primaryButton("⏸ Пауза");
        transportButton.setId("transport-button");
        transportButton.setOnAction(e -> toggleTransport());
        Button settings = new Button("⚙ Настройки");
        settings.setId("settings-button");
        settings.setOnAction(e -> showMicrophoneSettings(stage, false));
        HBox topBar = new HBox(10, brand, headingSpacer, transportButton, settings);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");

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
        modeBar.getStyleClass().add("mode-bar");

        exerciseView();
        mode.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) { old.setSelected(true); return; }
            showPage(selected == exerciseMode);
        });

        HBox pitchHeader = new HBox(16);
        pitchHeader.setAlignment(Pos.CENTER_LEFT);
        Label graphTitle = label("Живой голос · график высоты", "section-title");
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
        livePage = new VBox(7, pitchHeader, graphBox, exerciseDetails);
        VBox.setVgrow(graphBox, Priority.ALWAYS);
        VBox.setVgrow(livePage, Priority.ALWAYS);
        showPage(false);
        VBox workspace = new VBox(9, modeBar, livePage, libraryPage);
        VBox.setVgrow(workspace, Priority.ALWAYS);
        status = label("Подготовка микрофона…", "muted");
        status.getStyleClass().add("status-line");
        VBox root = new VBox(6, topBar, workspace, status);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 980, 740);
        scene.getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle(AppVersion.displayName());
        stage.setMinWidth(720);
        stage.setMinHeight(650);
        stage.setOnCloseRequest(e -> capture.stop());

        refreshHistory();
        timer = new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (now - previous < 33_000_000) return;
                previous = now;
                if (clock.isPaused()) {
                    samples.clear();
                    chart.draw(timeline, clock.time(now), exerciseChart);
                    return;
                }
                PitchSample sample;
                while ((sample = samples.poll()) != null) updatePitch(sample);
                long time = clock.time(now);
                updateExercise(time);
                chart.draw(timeline, time, exerciseChart);
            }
        };
        timer.start();
        stage.show();
        if (!Boolean.getBoolean("intonation.skipMicrophoneSetup"))
            Platform.runLater(() -> initializeMicrophone(stage));
    }

    private void exerciseView() {
        List<ExerciseCatalog.Option> presets = ExerciseCatalog.beginners();
        selectedExercise = presets.getFirst();

        Label libraryTitle = label("Библиотека распевок", "library-title");
        Label description = label("Выберите форму и высоту. Целевые ноты появятся на главном полотне.", "muted");
        ToggleGroup kinds = new ToggleGroup();
        ToggleButton patterns = new ToggleButton("Паттерны");
        ToggleButton sets = new ToggleButton("Наборы");
        patterns.setToggleGroup(kinds);
        sets.setToggleGroup(kinds);
        patterns.setSelected(true);
        patterns.getStyleClass().add("library-filter");
        sets.getStyleClass().add("library-filter");
        HBox filters = new HBox(7, patterns, sets);
        patternCards = new FlowPane(12, 12);
        patternCards.setPrefWrapLength(720);
        ScrollPane scroll = new ScrollPane(patternCards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        exercisePitch = new ComboBox<>(FXCollections.observableArrayList("C3", "C4", "C5"));
        exercisePitch.setId("exercise-pitch");
        exercisePitch.setValue("C4");
        exercisePitch.valueProperty().addListener((obs, old, value) -> {
            if (value != null) populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        kinds.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null) { old.setSelected(true); return; }
            populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        selectionTitle = label("", "selection-title");
        Button listen = new Button("▶ Прослушать пример");
        listen.setOnAction(e -> TonePlayer.playAsync(selectedExercise.exercise()));
        exerciseButton = primaryButton("Повторить на полотне →");
        exerciseButton.setOnAction(e -> {
            launchExercise();
        });
        HBox selectionActions = new HBox(10, label("Высота", "muted"), exercisePitch,
                selectionTitle, listen, exerciseButton);
        selectionActions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(selectionTitle, Priority.ALWAYS);
        selectionActions.getStyleClass().add("selection-bar");
        libraryPage = new VBox(12, libraryTitle, description, filters, scroll, selectionActions);
        VBox.setVgrow(libraryPage, Priority.ALWAYS);
        populateCards(ExerciseCatalog.Kind.PATTERN);

        target = label("Цель: —", "target-note");
        exerciseFeedback = label("Выберите распевку в библиотеке или просто пойте в микрофон.", "muted");
        exerciseFeedback.setWrapText(true);
        HBox.setHgrow(exerciseFeedback, Priority.ALWAYS);
        exerciseProgress = new ProgressBar(0);
        exerciseProgress.setMaxWidth(Double.MAX_VALUE);
        recentResults = label("Пока нет завершённых занятий", "muted");
        Button historyButton = new Button("История");
        historyButton.setOnAction(e -> showHistory());
        cancelExerciseButton = new Button("Прервать");
        cancelExerciseButton.setDisable(true);
        cancelExerciseButton.setOnAction(e -> cancelExercise("Распевка прервана. Можно начать заново."));
        HBox historyRow = new HBox(10, recentResults, historyButton);
        historyRow.setAlignment(Pos.CENTER_LEFT);
        HBox summary = new HBox(14, target, exerciseFeedback, cancelExerciseButton);
        summary.setAlignment(Pos.CENTER_LEFT);
        exerciseDetails = new VBox(5, summary, exerciseProgress, historyRow);
        exerciseDetails.getStyleClass().add("exercise-summary");
    }

    private void populateCards(ExerciseCatalog.Kind kind) {
        patternCards.getChildren().clear();
        String root = exercisePitch.getValue();
        List<ExerciseCatalog.Option> options = ExerciseCatalog.beginners().stream()
                .filter(option -> option.kind() == kind && option.title().endsWith(" · " + root)).toList();
        if (options.isEmpty()) return;
        if (selectedExercise.kind() != kind || !selectedExercise.title().endsWith(" · " + root)) {
            selectedExercise = options.getFirst();
        }
        for (ExerciseCatalog.Option option : options) {
            Button card = new Button();
            card.getStyleClass().add("pattern-card");
            if (option.equals(selectedExercise)) card.getStyleClass().add("selected-card");
            Canvas preview = new Canvas(184, 84);
            drawPatternPreview(preview, option);
            String title = option.title().replace(" · " + root, "");
            Label name = label(title, "pattern-name");
            Label meta = label(option.exercise().notes().size() + " нот · " +
                    (int) option.exercise().durationSeconds() + " с", "pattern-meta");
            card.setGraphic(new VBox(9, preview, name, meta));
            card.setOnAction(e -> {
                selectedExercise = option;
                populateCards(kind);
                launchExercise();
            });
            patternCards.getChildren().add(card);
        }
        selectionTitle.setText(selectedExercise.title().replace(" · " + root, ""));
    }

    private static void drawPatternPreview(Canvas canvas, ExerciseCatalog.Option option) {
        var g = canvas.getGraphicsContext2D();
        g.setFill(javafx.scene.paint.Color.web("#292929"));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        List<Integer> notes = option.exercise().notes();
        int min = notes.stream().mapToInt(Integer::intValue).min().orElse(60);
        int max = notes.stream().mapToInt(Integer::intValue).max().orElse(67);
        double width = canvas.getWidth() / notes.size();
        String clipColor = switch (option.exercise().id().split("_")[0]) {
            case "ascending" -> "#66dcd3";
            case "descending" -> "#a8b9ff";
            case "three" -> "#e8d978";
            case "triad" -> "#f9a6ae";
            default -> "#ffad57";
        };
        for (int i = 0; i < notes.size(); i++) {
            double y = 66 - (notes.get(i) - min) * 46.0 / Math.max(5, max - min);
            g.setFill(javafx.scene.paint.Color.web(clipColor));
            g.fillRoundRect(i * width + 2, y, Math.max(3, width - 4), 8, 3, 3);
        }
        g.setStroke(javafx.scene.paint.Color.web("#555555"));
        g.strokeLine(0, 76, canvas.getWidth(), 76);
    }

    private void showPage(boolean library) {
        if (livePage == null || libraryPage == null) return;
        livePage.setVisible(!library);
        livePage.setManaged(!library);
        libraryPage.setVisible(library);
        libraryPage.setManaged(library);
    }

    private static ExerciseChartState chartState(ExerciseCatalog.Option choice) {
        return new ExerciseChartState(choice.exercise(), choice.starts());
    }

    private static String deviceKey(MicrophoneCapture.Device device) {
        var info = device.info();
        return info.getName() + "|" + info.getVendor() + "|" + info.getDescription();
    }

    private void initializeMicrophone(Stage owner) {
        String saved = preferences.get(MICROPHONE_KEY, "");
        List<MicrophoneCapture.Device> available = MicrophoneCapture.devices();
        selectedDevice = available.stream().filter(device -> deviceKey(device).equals(saved))
                .findFirst().orElse(null);
        if (selectedDevice == null) showMicrophoneSettings(owner, true);
        else startCapture();
    }

    private void showMicrophoneSettings(Stage owner, boolean required) {
        if (session != null) cancelExercise("Распевка прервана для смены микрофона.");
        boolean wasRunning = capture.isRunning();
        if (wasRunning) capture.stop();
        samples.clear();
        Dialog<MicrophoneCapture.Device> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(required ? "Первый запуск · микрофон" : "Настройки · микрофон");
        dialog.setHeaderText(required ? "Выберите микрофон и проверьте его" : "Микрофон для живого голоса");
        ComboBox<MicrophoneCapture.Device> choices = new ComboBox<>();
        choices.setPrefWidth(390);
        choices.setItems(FXCollections.observableArrayList(MicrophoneCapture.devices()));
        if (selectedDevice != null) choices.getItems().stream()
                .filter(device -> deviceKey(device).equals(deviceKey(selectedDevice)))
                .findFirst().ifPresent(choices::setValue);
        if (choices.getValue() == null && !choices.getItems().isEmpty()) choices.setValue(choices.getItems().getFirst());
        Label testResult = label("Спойте ноту после нажатия «Проверить».", "muted");
        Button test = new Button("Проверить микрофон");
        Button refresh = new Button("Обновить список");
        refresh.setOnAction(e -> {
            capture.stop();
            choices.setItems(FXCollections.observableArrayList(MicrophoneCapture.devices()));
            if (choices.getValue() == null && !choices.getItems().isEmpty()) choices.setValue(choices.getItems().getFirst());
            testResult.setText(choices.getItems().isEmpty() ? "Устройства не найдены" : "Выберите устройство для проверки");
        });
        test.setOnAction(e -> {
            capture.stop();
            MicrophoneCapture.Device chosen = choices.getValue();
            if (chosen == null) { testResult.setText("Микрофон не выбран"); return; }
            try {
                capture.start(chosen, pitch -> Platform.runLater(() ->
                        testResult.setText(pitch.map(result -> "Сигнал есть · " +
                                Note.fromFrequency(result.frequencyHz()).display())
                                .orElse("Слушаю… спойте протяжную ноту"))),
                        error -> Platform.runLater(() -> testResult.setText("Ошибка: " + error)));
            } catch (LineUnavailableException | IllegalArgumentException error) {
                testResult.setText("Не удалось открыть: " + error.getMessage());
            }
        });
        choices.valueProperty().addListener((obs, old, value) -> {
            capture.stop();
            testResult.setText("Нажмите «Проверить микрофон»");
        });
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
        dialog.getDialogPane().setContent(new VBox(12,
                label("Устройство ввода", "muted"), choices, new HBox(8, test, refresh), testResult));
        ButtonType save = new ButtonType("Сохранить и начать", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(choices.valueProperty().isNull());
        dialog.setResultConverter(button -> button == save ? choices.getValue() : null);
        Optional<MicrophoneCapture.Device> chosen = dialog.showAndWait();
        capture.stop();
        if (chosen.isPresent()) {
            selectedDevice = chosen.get();
            preferences.put(MICROPHONE_KEY, deviceKey(selectedDevice));
            try { preferences.flush(); }
            catch (java.util.prefs.BackingStoreException error) {
                status.setText("Не удалось сохранить выбор микрофона: " + error.getMessage());
            }
            startCapture();
        } else if (wasRunning && selectedDevice != null) startCapture();
        else status.setText("Микрофон не выбран · откройте настройки");
    }

    private void startCapture() {
        if (selectedDevice == null || capture.isRunning()) return;
        try {
            samples.clear();
            smoothedMidi = Double.NaN;
            capture.start(selectedDevice, pitch -> {
                if (clock.isPaused()) return;
                while (session == null && samples.size() >= 8) samples.poll();
                samples.add(new PitchSample(clock.time(System.nanoTime()), pitch));
            }, error -> Platform.runLater(() -> {
                if (session != null) cancelExercise("Распевка прервана из-за ошибки микрофона.");
                status.setText("Ошибка микрофона: " + error + " · откройте настройки");
            }));
            status.setText("Микрофон: " + selectedDevice + " · звук остаётся на этом устройстве");
        } catch (LineUnavailableException | IllegalArgumentException error) {
            status.setText("Не удалось открыть микрофон: " + error.getMessage() + " · откройте настройки");
        }
    }

    private void toggleTransport() {
        long now = System.nanoTime();
        if (clock.isPaused()) {
            clock.resume(now);
            transportButton.setText("⏸ Пауза");
            status.setText(capture.isRunning() ? "Полотно движется · микрофон активен" :
                    "Полотно движется · выберите микрофон в настройках");
        } else {
            clock.pause(now);
            samples.clear();
            transportButton.setText("▶ Продолжить");
            status.setText("Пауза · график и распевка остановлены");
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
        }
    }

    private void launchExercise() {
        liveMode.setSelected(true);
        if (clock.isPaused()) toggleTransport();
        startExercise();
    }

    private void startExercise() {
        if (!capture.isRunning()) startCapture();
        session = new ExerciseSession(selectedExercise.exercise(), clock.time(System.nanoTime()));
        exerciseChart = chartState(selectedExercise);
        exerciseChart.start(session.startNanos());
        exerciseButton.setDisable(true);
        cancelExerciseButton.setDisable(false);
        exerciseMode.setDisable(true);
        exerciseProgress.setProgress(0);
        exerciseFeedback.setText(capture.isRunning()
                ? "Приготовьтесь. Через 2 секунды начнётся первая нота."
                : "Микрофон недоступен. Ноты видны, но оценка голоса не будет получена.");
    }

    private void cancelExercise(String message) {
        session = null;
        exerciseChart = null;
        exerciseButton.setDisable(false);
        cancelExerciseButton.setDisable(true);
        exerciseMode.setDisable(false);
        exerciseProgress.setProgress(0);
        target.setText("Целевая нота: —");
        exerciseFeedback.setText(message);
    }

    private void updateExercise(long now) {
        if (session == null) {
            if (exerciseChart != null && exerciseChart.expired(now)) exerciseChart = null;
            return;
        }
        if (session.finished(now)) {
            ExerciseSession.Result result = session.result();
            session = null;
            exerciseChart.finish();
            exerciseButton.setDisable(false);
            cancelExerciseButton.setDisable(true);
            exerciseMode.setDisable(false);
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
        ExerciseCatalog.Option choice = selectedExercise;
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
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
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
