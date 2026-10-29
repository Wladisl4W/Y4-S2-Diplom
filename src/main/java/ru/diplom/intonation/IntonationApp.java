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
import ru.diplom.intonation.song.SongPipeline;
import ru.diplom.intonation.song.SongPlayback;

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
import java.util.stream.IntStream;

public final class IntonationApp extends Application {
    private final MicrophoneCapture capture = new MicrophoneCapture();
    private final ConcurrentLinkedQueue<PitchSample> samples = new ConcurrentLinkedQueue<>();
    private final PitchTimeline timeline = new PitchTimeline();
    private final ExerciseHistory history = new ExerciseHistory();
    private final PlaybackClock clock = new PlaybackClock();
    private final Preferences preferences = Preferences.userNodeForPackage(IntonationApp.class);
    private static final String MICROPHONE_KEY = "microphone";
    private CheckBox notePiano;
    private CheckBox transitionPiano;
    private HBox pianoControls;
    private VBox livePage;
    private VBox libraryPage;
    private WarmupLibrary warmupLibrary;
    private VBox songPage;
    private SongLibrary songLibrary;
    private ToggleButton songMode;
    private final SongPlayback songPlayback = new SongPlayback();
    private SongChartState songChart;
    private HBox songControls;
    private Label songPracticeStatus;
    private CheckBox instrumentalSound;
    private CheckBox vocalSound;
    private Button transportButton;
    private MicrophoneCapture.Device selectedDevice;
    private Button cancelExerciseButton;
    private Label note;
    private Label cents;
    private Label status;
    private final PianoGuide piano = new PianoGuide(message -> Platform.runLater(() -> status.setText(message)));
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
    private boolean exerciseScoringEnabled;
    private WarmupRoute activeRoute;
    private ExerciseCatalog.Option activeOption;
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
        songMode = new ToggleButton("Песни · эксперимент");
        songMode.setToggleGroup(mode);
        songMode.getStyleClass().add("mode-button");
        HBox modeBar = new HBox(8, liveMode, exerciseMode, songMode);
        modeBar.setAlignment(Pos.CENTER_LEFT);
        modeBar.getStyleClass().add("mode-bar");

        warmupLibrary = new WarmupLibrary(preferences, this::launchExercise);
        libraryPage = warmupLibrary.page();
        exerciseDetailsView();
        songLibrary = new SongLibrary(stage, preferences, this::stopSongPractice, this::startSongPractice);
        songPage = songLibrary.page();
        mode.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) { old.setSelected(true); return; }
            showPage(selected == exerciseMode, selected == songMode);
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
        songPracticeStatus = label("", "muted");
        instrumentalSound = new CheckBox("Инструментал");
        instrumentalSound.setSelected(true);
        vocalSound = new CheckBox("Вокал");
        instrumentalSound.selectedProperty().addListener((obs, old, value) ->
                songPlayback.setInstrumentalEnabled(value));
        vocalSound.selectedProperty().addListener((obs, old, value) ->
                songPlayback.setVocalsEnabled(value));
        Button stopSong = new Button("Остановить песню");
        stopSong.setOnAction(e -> stopSongPractice());
        songControls = new HBox(14, songPracticeStatus, instrumentalSound, vocalSound, stopSong);
        songControls.setAlignment(Pos.CENTER_LEFT);
        songControls.setVisible(false);
        songControls.setManaged(false);
        livePage = new VBox(7, pitchHeader, graphBox, songControls, exerciseDetails);
        VBox.setVgrow(graphBox, Priority.ALWAYS);
        VBox.setVgrow(livePage, Priority.ALWAYS);
        showPage(false, false);
        VBox workspace = new VBox(9, modeBar, livePage, libraryPage, songPage);
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
        stage.setOnCloseRequest(e -> { songLibrary.close(); songPlayback.close();
            capture.stop(); piano.close(); TonePlayer.stop(); });

        refreshHistory();
        timer = new AnimationTimer() {
            private long previous;
            @Override public void handle(long now) {
                if (now - previous < 33_000_000) return;
                previous = now;
                if (clock.isPaused()) {
                    samples.clear();
                    chart.draw(timeline, clock.time(now), exerciseChart, songChart);
                    return;
                }
                PitchSample sample;
                while ((sample = samples.poll()) != null) updatePitch(sample);
                long time = clock.time(now);
                updateExercise(time);
                updateSongPractice(time);
                chart.draw(timeline, time, exerciseChart, songChart);
            }
        };
        timer.start();
        stage.show();
        if (!Boolean.getBoolean("intonation.skipMicrophoneSetup"))
            Platform.runLater(() -> initializeMicrophone(stage));
    }

    private void exerciseDetailsView() {
        target = label("Цель: —", "target-note");
        notePiano = new CheckBox("Пианино · ноты");
        transitionPiano = new CheckBox("Пианино · переходы");
        notePiano.setSelected(true);
        transitionPiano.setSelected(true);
        notePiano.setTooltip(new Tooltip("Тихий фортепианный ориентир для каждой целевой ноты"));
        transitionPiano.setTooltip(new Tooltip("Два аккорда между высотами: прежняя → следующая"));
        pianoControls = new HBox(18, notePiano, transitionPiano,
                label("Для точной оценки используйте наушники", "muted"));
        pianoControls.setAlignment(Pos.CENTER_LEFT);
        pianoControls.setVisible(false);
        pianoControls.setManaged(false);
        notePiano.selectedProperty().addListener((obs, old, value) -> refreshPiano());
        transitionPiano.selectedProperty().addListener((obs, old, value) -> refreshPiano());
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
        exerciseDetails = new VBox(5, summary, pianoControls, exerciseProgress, historyRow);
        exerciseDetails.getStyleClass().add("exercise-summary");
    }

    private void startSongPractice() {
        SongPipeline.Result readySong = songLibrary.readySong();
        if (readySong == null || readySong.transcription().notes().isEmpty()) return;
        if (session != null) cancelExercise("Распевка остановлена для песни.");
        stopSongPractice();
        liveMode.setSelected(true);
        if (clock.isPaused()) toggleTransport();
        if (!capture.isRunning()) startCapture();
        long start = clock.time(System.nanoTime()) + 2_000_000_000L;
        songChart = new SongChartState(readySong.transcription().notes(), start,
                readySong.transcription().durationSeconds());
        songControls.setVisible(true);
        songControls.setManaged(true);
        exerciseDetails.setVisible(false);
        exerciseDetails.setManaged(false);
        songPracticeStatus.setText("Песня начнётся через 2 с");
        songPlayback.setInstrumentalEnabled(instrumentalSound.isSelected());
        songPlayback.setVocalsEnabled(vocalSound.isSelected());
        songPlayback.start(readySong.instrumental(), readySong.vocals(), start,
                () -> clock.time(System.nanoTime()),
                error -> Platform.runLater(() -> status.setText(error)));
    }

    private void stopSongPractice() {
        songPlayback.stop();
        songChart = null;
        if (songControls != null) {
            songControls.setVisible(false);
            songControls.setManaged(false);
        }
        if (exerciseDetails != null) {
            exerciseDetails.setVisible(true);
            exerciseDetails.setManaged(true);
        }
    }

    private void updateSongPractice(long now) {
        if (songChart == null) return;
        if (now < songChart.startNanos()) {
            int seconds = (int) Math.ceil((songChart.startNanos() - now) / 1e9);
            songPracticeStatus.setText("Песня начнётся через " + seconds + " с");
        } else if (now < songChart.endNanos()) {
            int targetMidi = songChart.targetAt(now);
            songPracticeStatus.setText(targetMidi < 0 ? "Пауза в вокале" :
                    "Цель: " + ExerciseCatalog.noteName(targetMidi));
        } else {
            songPracticeStatus.setText("Песня завершена");
            songPlayback.stop();
            if (songChart.expired(now)) songChart = null;
        }
    }

    private void showPage(boolean library, boolean songs) {
        if (livePage == null || libraryPage == null || songPage == null) return;
        livePage.setVisible(!library && !songs);
        livePage.setManaged(!library && !songs);
        libraryPage.setVisible(library);
        libraryPage.setManaged(library);
        songPage.setVisible(songs);
        songPage.setManaged(songs);
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
            songPlayback.resume();
            transportButton.setText("⏸ Пауза");
            status.setText(capture.isRunning() ? "Полотно движется · микрофон активен" :
                    "Полотно движется · выберите микрофон в настройках");
        } else {
            clock.pause(now);
            songPlayback.pause();
            samples.clear();
            piano.silence();
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
        stopSongPractice();
        if (!capture.isRunning()) startCapture();
        exerciseScoringEnabled = capture.isRunning();
        TonePlayer.stop();
        piano.silence();
        activeRoute = warmupLibrary.selectedRoute();
        activeOption = activeRoute.option();
        transitionPiano.setDisable(activeRoute.transitions().isEmpty());
        session = new ExerciseSession(activeOption.exercise(), clock.time(System.nanoTime()));
        exerciseChart = chartState(activeOption);
        exerciseChart.start(session.startNanos());
        pianoControls.setVisible(true);
        pianoControls.setManaged(true);
        warmupLibrary.startButton().setDisable(true);
        cancelExerciseButton.setDisable(false);
        exerciseMode.setDisable(true);
        exerciseProgress.setProgress(0);
        exerciseFeedback.setText(capture.isRunning()
                ? "Приготовьтесь. Через 2 секунды начнётся первая нота."
                : "Микрофон недоступен. Ноты видны, но оценка голоса не будет получена.");
    }

    private void cancelExercise(String message) {
        session = null;
        exerciseScoringEnabled = false;
        piano.silence();
        activeRoute = null;
        activeOption = null;
        exerciseChart = null;
        pianoControls.setVisible(false);
        pianoControls.setManaged(false);
        warmupLibrary.startButton().setDisable(false);
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
        if (exerciseScoringEnabled && !capture.isRunning()) {
            cancelExercise("Распевка прервана: микрофон перестал передавать звук.");
            return;
        }
        if (session.finished(now)) {
            ExerciseSession.Result result = session.result();
            session = null;
            piano.silence();
            activeRoute = null;
            activeOption = null;
            pianoControls.setVisible(false);
            pianoControls.setManaged(false);
            exerciseChart.finish();
            warmupLibrary.startButton().setDisable(false);
            cancelExerciseButton.setDisable(true);
            exerciseMode.setDisable(false);
            target.setText("Готово");
            exerciseProgress.setProgress(1);
            if (exerciseScoringEnabled) {
                exerciseFeedback.setText("Попадание: " + result.score() + "% · голос звучал: "
                        + result.coverage() + "% · среднее отклонение: " + result.averageErrorCents() + " центов");
                try { history.append(result); refreshHistory(); }
                catch (IOException e) { status.setText("Не удалось сохранить историю: " + e.getMessage()); }
            } else exerciseFeedback.setText("Маршрут завершён без оценки: микрофон не был подключён.");
            exerciseScoringEnabled = false;
            return;
        }
        int index = session.noteIndex(now);
        if (index < 0) {
            target.setText("Старт через " + session.countdown(now));
            return;
        }
        refreshPiano(now);
        int midi = session.exercise().notes().get(index);
        if (midi == Exercise.REST) {
            WarmupRoute.Transition transition = activeRoute.transitionAt(index);
            if (transition == null) {
                target.setText("Пауза · следующий паттерн");
                exerciseFeedback.setText("Спокойно вдохните и подготовьтесь к следующему рисунку");
                exerciseProgress.setProgress((now - session.startNanos()) /
                        (session.exercise().durationSeconds() * 1e9));
                return;
            }
            String direction = transition.toRoot() > transition.fromRoot()
                    ? "↑" : "↓";
            target.setText("Переход " + direction);
            exerciseFeedback.setText("Слушайте два аккорда и приготовьтесь к следующей высоте");
            exerciseProgress.setProgress((now - session.startNanos()) /
                    (session.exercise().durationSeconds() * 1e9));
            return;
        }
        double hz = 440 * Math.pow(2, (midi - 69) / 12.0);
        Note expected = Note.fromFrequency(hz);
        target.setText(expected.display());
        ExerciseCatalog.Option choice = activeOption;
        String part = choice.partCount() > 1
                ? (activeRoute.climbSemitones() > 0 ? "Высота " : "Паттерн ")
                  + choice.partNumber(index) + "/" + choice.partCount() + " · " : "";
        exerciseFeedback.setText(part + "нота " + choice.noteNumberInPart(index) + "/"
                + choice.notesInPart(index) + " · пойте до смены подсказки");
        exerciseProgress.setProgress((now - session.startNanos()) /
                (session.exercise().durationSeconds() * 1e9));
    }

    private void refreshPiano() {
        if (session != null && !clock.isPaused()) refreshPiano(clock.time(System.nanoTime()));
        else piano.silence();
    }

    private void refreshPiano(long now) {
        if (session == null || activeRoute == null) return;
        piano.update(activeRoute, now, session.startNanos(),
                notePiano.isSelected(), transitionPiano.isSelected());
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
            dialog.initOwner(warmupLibrary.startButton().getScene().getWindow());
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
        Map<String, String> titles = IntStream.rangeClosed(36, 72).boxed()
                .flatMap(root -> ExerciseCatalog.forRoot(root).stream())
                .collect(Collectors.toMap(choice -> choice.exercise().id(), ExerciseCatalog.Option::toString));
        String id = fields[1];
        int routeAt = id.lastIndexOf("_route");
        String title = routeAt < 0 ? titles.getOrDefault(id, id)
                : titles.getOrDefault(id.substring(0, routeAt), id.substring(0, routeAt))
                  + " · +" + id.substring(routeAt + 6) + " и обратно";
        String summary = date + " · " + title + " · " + fields[2] + "%";
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
        songLibrary.close();
        songPlayback.close();
        capture.stop();
        piano.close();
        TonePlayer.stop();
    }

    public static void main(String[] args) { launch(args); }
}
