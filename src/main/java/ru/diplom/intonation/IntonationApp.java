package ru.diplom.intonation;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import ru.diplom.intonation.audio.*;
import ru.diplom.intonation.exercise.*;
import ru.diplom.intonation.song.SongAnalyzer;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
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
import javafx.util.StringConverter;

public final class IntonationApp extends Application {
    private final MicrophoneCapture capture = new MicrophoneCapture();
    private final ConcurrentLinkedQueue<PitchSample> samples = new ConcurrentLinkedQueue<>();
    private final PitchTimeline timeline = new PitchTimeline();
    private final ExerciseHistory history = new ExerciseHistory();
    private final PlaybackClock clock = new PlaybackClock();
    private final Preferences preferences = Preferences.userNodeForPackage(IntonationApp.class);
    private static final String MICROPHONE_KEY = "microphone";
    private ExerciseCatalog.Option selectedExercise;
    private ComboBox<Integer> exercisePitch;
    private ComboBox<Integer> climbChoice;
    private ComboBox<Double> paceChoice;
    private CheckBox notePiano;
    private CheckBox transitionPiano;
    private HBox pianoControls;
    private FlowPane patternCards;
    private Label selectionTitle;
    private VBox livePage;
    private VBox libraryPage;
    private VBox songPage;
    private ToggleButton songMode;
    private Task<SongAnalyzer.Result> songTask;
    private SongAnalyzer songAnalyzer;
    private Label songStatus;
    private ListView<String> songNotes;
    private Canvas songOverview;
    private SongAnalyzer.Result songResult;
    private Button transportButton;
    private MicrophoneCapture.Device selectedDevice;
    private Button exerciseButton;
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

        exerciseView();
        songView(stage);
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
        livePage = new VBox(7, pitchHeader, graphBox, exerciseDetails);
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
        stage.setOnCloseRequest(e -> { cancelSongAnalysis();
            capture.stop(); piano.close(); TonePlayer.stop(); });

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
        List<ExerciseCatalog.Option> presets = ExerciseCatalog.forRoot(60);
        selectedExercise = presets.getFirst();

        Label libraryTitle = label("Библиотека распевок", "library-title");
        Label description = label("Начните с комфортной опорной ноты. Нажмите карточку, чтобы петь на главном полотне.", "muted");
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
        patternCards.setAlignment(Pos.TOP_LEFT);
        patternCards.setRowValignment(VPos.TOP);
        ScrollPane scroll = new ScrollPane(patternCards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        exercisePitch = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(36, 72).boxed().toList()));
        exercisePitch.setConverter(new StringConverter<>() {
            @Override public String toString(Integer midi) {
                return midi == null ? "" : ExerciseCatalog.noteName(midi);
            }
            @Override public Integer fromString(String text) { throw new UnsupportedOperationException(); }
        });
        exercisePitch.setId("exercise-pitch");
        exercisePitch.setValue(60);
        climbChoice = new ComboBox<>(FXCollections.observableArrayList(0, 2, 4));
        climbChoice.setId("climb-choice");
        climbChoice.setValue(2);
        climbChoice.setConverter(new StringConverter<>() {
            @Override public String toString(Integer semitones) {
                return semitones == null ? "" : semitones == 0 ? "Один раз" :
                        "+" + semitones + " и обратно";
            }
            @Override public Integer fromString(String text) { throw new UnsupportedOperationException(); }
        });
        climbChoice.valueProperty().addListener((obs, old, value) -> {
            if (value != null) populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        paceChoice = new ComboBox<>(FXCollections.observableArrayList(2.5, 2.0, 1.5, 1.0));
        paceChoice.setId("pace-choice");
        paceChoice.setValue(1.5);
        paceChoice.setConverter(new StringConverter<>() {
            @Override public String toString(Double seconds) {
                return seconds == null ? "" : switch (seconds.toString()) {
                    case "2.5" -> "Медленно";
                    case "2.0" -> "Спокойно";
                    case "1.5" -> "Обычно";
                    default -> "Быстро";
                };
            }
            @Override public Double fromString(String text) { throw new UnsupportedOperationException(); }
        });
        paceChoice.valueProperty().addListener((obs, old, value) -> {
            if (value != null) populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        exercisePitch.valueProperty().addListener((obs, old, value) -> {
            if (value != null) populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        kinds.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null) { old.setSelected(true); return; }
            climbChoice.setDisable(sets.isSelected());
            populateCards(sets.isSelected() ? ExerciseCatalog.Kind.SET : ExerciseCatalog.Kind.PATTERN);
        });
        selectionTitle = label("", "selection-title");
        Button listen = new Button("▶ Прослушать пример");
        listen.setOnAction(e -> {
            Exercise base = selectedExercise.exercise();
            TonePlayer.playAsync(new Exercise(base.id(), base.title(), base.notes(), paceChoice.getValue()));
        });
        exerciseButton = primaryButton("Начать выбранное →");
        exerciseButton.setOnAction(e -> {
            launchExercise();
        });
        HBox selectionSummary = new HBox(10, label("Опора", "muted"), exercisePitch,
                label("Маршрут", "muted"), climbChoice,
                label("Темп", "muted"), paceChoice, selectionTitle);
        selectionSummary.setAlignment(Pos.CENTER_LEFT);
        HBox selectionButtons = new HBox(10, listen, exerciseButton);
        VBox selectionActions = new VBox(8, selectionSummary, selectionButtons);
        selectionActions.getStyleClass().add("selection-bar");
        libraryPage = new VBox(12, libraryTitle, description, filters, scroll, selectionActions);
        VBox.setVgrow(libraryPage, Priority.ALWAYS);
        populateCards(ExerciseCatalog.Kind.PATTERN);

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

    private void populateCards(ExerciseCatalog.Kind kind) {
        patternCards.getChildren().clear();
        String root = ExerciseCatalog.noteName(exercisePitch.getValue());
        List<ExerciseCatalog.Option> options = ExerciseCatalog.forRoot(exercisePitch.getValue()).stream()
                .filter(option -> option.kind() == kind).toList();
        if (options.isEmpty()) return;
        String priorId = basePatternId(selectedExercise.exercise().id());
        selectedExercise = options.stream().filter(option ->
                basePatternId(option.exercise().id()).equals(priorId)).findFirst().orElse(options.getFirst());
        for (ExerciseCatalog.Option option : options) {
            Button card = new Button();
            card.getStyleClass().add("pattern-card");
            card.setPrefSize(204, 224);
            card.setMinSize(204, 224);
            card.setMaxSize(204, 224);
            if (option.equals(selectedExercise)) card.getStyleClass().add("selected-card");
            Canvas preview = new Canvas(184, 84);
            drawPatternPreview(preview, option);
            String title = option.title().replace(" · " + root, "");
            PatternInfo info = patternInfo(option);
            Label category = label(info.category(), "pattern-kicker");
            Label name = label(title, "pattern-name");
            Label formula = label(info.formula(), "pattern-formula");
            WarmupRoute route = WarmupRoute.create(option, exercisePitch.getValue(),
                    kind == ExerciseCatalog.Kind.SET ? 0 : climbChoice.getValue(), paceChoice.getValue());
            int low = route.option().exercise().notes().stream().mapToInt(Integer::intValue)
                    .filter(value -> value != Exercise.REST).min().orElse(60);
            int high = route.option().exercise().notes().stream().mapToInt(Integer::intValue)
                    .filter(value -> value != Exercise.REST).max().orElse(60);
            Label meta = label(ExerciseCatalog.noteName(low) + "–" + ExerciseCatalog.noteName(high)
                    + " · " + Math.round(route.option().exercise().durationSeconds()) + " с", "pattern-meta");
            Label goal = label(info.goal(), "pattern-goal");
            goal.setWrapText(true);
            goal.setPrefWidth(184);
            card.setGraphic(new VBox(6, category, preview, name, formula, meta, goal));
            card.setTooltip(new Tooltip("Нажмите, чтобы начать после двухсекундного отсчёта"));
            card.setOnAction(e -> {
                selectedExercise = option;
                populateCards(kind);
                launchExercise();
            });
            patternCards.getChildren().add(card);
        }
        selectionTitle.setText(selectedExercise.title().replace(" · " + root, ""));
    }

    private record PatternInfo(String category, String formula, String goal) {}

    private static String basePatternId(String id) {
        return id.replaceFirst("(_c[35]|_m\\d+)$", "");
    }

    private static PatternInfo patternInfo(ExerciseCatalog.Option option) {
        return switch (basePatternId(option.exercise().id())) {
            case "ascending" -> new PatternInfo("01 · СТУПЕНИ", "1–2–3–4–5", "Плавный подъём");
            case "descending" -> new PatternInfo("02 · СТУПЕНИ", "5–4–3–2–1", "Плавный спуск");
            case "three_notes" -> new PatternInfo("03 · ФРАЗА", "1–2–3–2–1", "Возврат к опоре");
            case "triad" -> new PatternInfo("04 · ИНТЕРВАЛЫ", "1–3–5–3–1", "Скачки по трезвучию");
            default -> new PatternInfo("НАБОР · 4 ФОРМЫ", "Ступени → фраза → аккорд", "Полный цикл распевки");
        };
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

    private void songView(Stage owner) {
        Label heading = label("Разбор песни · исследовательский режим", "library-title");
        Label explanation = label("MP3 анализируется локально через FFmpeg. Ноты извлекаются из всей смеси.\nИнструменты могут попадать в результат; оценка пения пока отключена.", "muted");
        explanation.setWrapText(true);
        explanation.setMinHeight(36);
        songStatus = label("Выберите MP3 для просмотра черновой нотной линии.", "muted");
        songStatus.setWrapText(true);
        Button open = primaryButton("Открыть MP3…");
        Button cancel = new Button("Отменить анализ");
        cancel.setDisable(true);
        open.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Выбрать песню");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Аудиофайлы", "*.mp3", "*.wav"));
            File file = chooser.showOpenDialog(owner);
            if (file == null) return;
            cancelSongAnalysis();
            songNotes.getItems().clear();
            songResult = null;
            drawSongOverview(null);
            songStatus.setText("Анализ: " + file.getName() + "…");
            open.setDisable(true);
            cancel.setDisable(false);
            songAnalyzer = new SongAnalyzer();
            SongAnalyzer analyzer = songAnalyzer;
            songTask = new Task<>() {
                @Override protected SongAnalyzer.Result call() throws Exception {
                    return analyzer.analyze(file.toPath());
                }
            };
            songTask.setOnSucceeded(event -> {
                SongAnalyzer.Result result = songTask.getValue();
                songResult = result;
                songStatus.setText(file.getName() + " · " + Math.round(result.durationSeconds())
                        + " с · найдено " + result.notes().size() + " кандидатов в ноты"
                        + (result.durationSeconds() >= 599 ? " · показаны первые 10 минут" : ""));
                songNotes.getItems().setAll(result.notes().stream().limit(500).map(note ->
                        String.format("%6.1f с   %4.1f с   %s", note.startSeconds(),
                                note.durationSeconds(), ExerciseCatalog.noteName(note.midi()))).toList());
                drawSongOverview(result);
                open.setDisable(false);
                cancel.setDisable(true);
            });
            songTask.setOnFailed(event -> {
                Throwable error = songTask.getException();
                songStatus.setText("Не удалось обработать файл: " + error.getMessage()
                        + ". Для этого режима нужен локальный FFmpeg.");
                open.setDisable(false);
                cancel.setDisable(true);
            });
            songTask.setOnCancelled(event -> {
                songStatus.setText("Анализ отменён");
                open.setDisable(false);
                cancel.setDisable(true);
            });
            Thread worker = new Thread(songTask, "song-analysis");
            worker.setDaemon(true);
            worker.start();
        });
        cancel.setOnAction(e -> cancelSongAnalysis());
        HBox actions = new HBox(10, open, cancel);
        songOverview = new Canvas(800, 170);
        songNotes = new ListView<>();
        songNotes.setPrefHeight(250);
        songNotes.setMinHeight(100);
        VBox.setVgrow(songNotes, Priority.ALWAYS);
        Label columns = label("Время       Длительность       Предполагаемая нота", "muted");
        songPage = new VBox(12, heading, explanation, actions, songStatus, songOverview, columns, songNotes);
        VBox.setVgrow(songPage, Priority.ALWAYS);
        explanation.prefWidthProperty().bind(songPage.widthProperty().subtract(4));
        songStatus.prefWidthProperty().bind(songPage.widthProperty().subtract(4));
        songOverview.widthProperty().bind(songPage.widthProperty().subtract(2));
        songOverview.widthProperty().addListener((obs, old, value) -> drawSongOverview(songResult));
        drawSongOverview(null);
    }

    private void cancelSongAnalysis() {
        if (songAnalyzer != null) songAnalyzer.cancel();
        if (songTask != null) songTask.cancel(true);
    }

    private void drawSongOverview(SongAnalyzer.Result result) {
        if (songOverview == null) return;
        var g = songOverview.getGraphicsContext2D();
        double width = songOverview.getWidth(), height = songOverview.getHeight();
        g.setFill(javafx.scene.paint.Color.web("#282828"));
        g.fillRect(0, 0, width, height);
        g.setFill(javafx.scene.paint.Color.web("#b8b8b5"));
        if (result == null || result.notes().isEmpty()) {
            g.fillText("Нотная линия появится после анализа", 16, 25);
            return;
        }
        int min = result.notes().stream().mapToInt(SongAnalyzer.NoteEvent::midi).min().orElse(60);
        int max = result.notes().stream().mapToInt(SongAnalyzer.NoteEvent::midi).max().orElse(72);
        double seconds = Math.max(1, result.durationSeconds());
        for (SongAnalyzer.NoteEvent note : result.notes()) {
            double x = 12 + (width - 24) * note.startSeconds() / seconds;
            double w = Math.max(2, (width - 24) * note.durationSeconds() / seconds);
            double y = 28 + (height - 52) * (max - note.midi()) / Math.max(1, max - min);
            g.setFill(javafx.scene.paint.Color.web("#ffad57"));
            g.fillRect(x, y, w, 5);
        }
        g.setFill(javafx.scene.paint.Color.web("#b8b8b5"));
        g.fillText(ExerciseCatalog.noteName(max), 8, 17);
        g.fillText(ExerciseCatalog.noteName(min), 8, height - 8);
        g.fillText(Math.round(seconds) + " с", width - 46, height - 8);
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
            transportButton.setText("⏸ Пауза");
            status.setText(capture.isRunning() ? "Полотно движется · микрофон активен" :
                    "Полотно движется · выберите микрофон в настройках");
        } else {
            clock.pause(now);
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
        if (!capture.isRunning()) startCapture();
        TonePlayer.stop();
        piano.silence();
        activeRoute = WarmupRoute.create(selectedExercise, exercisePitch.getValue(),
                selectedExercise.kind() == ExerciseCatalog.Kind.SET ? 0 : climbChoice.getValue(),
                paceChoice.getValue());
        activeOption = activeRoute.option();
        transitionPiano.setDisable(activeRoute.transitions().isEmpty());
        session = new ExerciseSession(activeOption.exercise(), clock.time(System.nanoTime()));
        exerciseChart = chartState(activeOption);
        exerciseChart.start(session.startNanos());
        pianoControls.setVisible(true);
        pianoControls.setManaged(true);
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
        piano.silence();
        activeRoute = null;
        activeOption = null;
        exerciseChart = null;
        pianoControls.setVisible(false);
        pianoControls.setManaged(false);
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
            piano.silence();
            activeRoute = null;
            activeOption = null;
            pianoControls.setVisible(false);
            pianoControls.setManaged(false);
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
        refreshPiano(now);
        int midi = session.exercise().notes().get(index);
        if (midi == Exercise.REST) {
            WarmupRoute.Transition transition = activeRoute.transitionAt(index);
            String direction = transition != null && transition.toRoot() > transition.fromRoot()
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
        capture.stop();
        piano.close();
        TonePlayer.stop();
    }

    public static void main(String[] args) { launch(args); }
}
