package ru.diplom.intonation;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import ru.diplom.intonation.exercise.*;

import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.IntStream;

/** Owns the warmup catalog, selection and preview; the main app runs the selected route. */
final class WarmupLibrary {
    private static final String COMFORT_LOW_KEY = "warmup_comfort_low";
    private static final String COMFORT_HIGH_KEY = "warmup_comfort_high";
    private final Preferences preferences;
    private final Runnable onLaunch;
    private final VBox page;
    private ExerciseCatalog.Option selectedExercise;
    private ComboBox<Integer> exercisePitch;
    private ComboBox<Integer> climbChoice;
    private ComboBox<Double> paceChoice;
    private FlowPane patternCards;
    private Label selectionTitle;
    private Label comfortNotice;
    private Button exerciseButton;

    WarmupLibrary(Preferences preferences, Runnable onLaunch) {
        this.preferences = preferences;
        this.onLaunch = onLaunch;
        this.page = createPage();
    }

    VBox page() { return page; }
    Button startButton() { return exerciseButton; }
    WarmupRoute selectedRoute() {
        return routeFor(selectedExercise);
    }

    private WarmupRoute routeFor(ExerciseCatalog.Option option) {
        return WarmupRoute.create(option, exercisePitch.getValue(),
                option.kind() == ExerciseCatalog.Kind.SET ? 0 : climbChoice.getValue(), paceChoice.getValue());
    }

    private VBox createPage() {
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
        listen.setOnAction(e -> TonePlayer.playAsync(selectedRoute()));
        exerciseButton = primaryButton("Начать выбранное →");
        exerciseButton.setOnAction(e -> {
            onLaunch.run();
        });
        HBox selectionSummary = new HBox(10, label("Опора", "muted"), exercisePitch,
                label("Маршрут", "muted"), climbChoice,
                label("Темп", "muted"), paceChoice, selectionTitle);
        selectionSummary.setAlignment(Pos.CENTER_LEFT);
        HBox selectionButtons = new HBox(10, listen, exerciseButton);
        Button comfortSettings = new Button("Мой комфортный диапазон…");
        comfortSettings.setOnAction(e -> showComfortRangeDialog());
        comfortNotice = label("", "muted");
        HBox comfortRow = new HBox(10, comfortSettings, comfortNotice);
        comfortRow.setAlignment(Pos.CENTER_LEFT);
        VBox selectionActions = new VBox(8, selectionSummary, selectionButtons, comfortRow);
        selectionActions.getStyleClass().add("selection-bar");
        VBox libraryPage = new VBox(12, libraryTitle, description, filters, scroll, selectionActions);
        VBox.setVgrow(libraryPage, Priority.ALWAYS);
        populateCards(ExerciseCatalog.Kind.PATTERN);

        return libraryPage;
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
            WarmupRoute route = routeFor(option);
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
                onLaunch.run();
            });
            patternCards.getChildren().add(card);
        }
        selectionTitle.setText(selectedExercise.title().replace(" · " + root, ""));
        if (comfortNotice != null) {
            int comfortableLow = preferences.getInt(COMFORT_LOW_KEY, -1);
            int comfortableHigh = preferences.getInt(COMFORT_HIGH_KEY, -1);
            if (comfortableLow < 0 || comfortableHigh < 0) {
                comfortNotice.setText("Диапазон не задан · выбирайте удобную высоту");
            } else {
                WarmupRoute route = selectedRoute();
                int low = route.option().exercise().notes().stream().mapToInt(Integer::intValue)
                        .filter(midi -> midi != Exercise.REST).min().orElse(60);
                int high = route.option().exercise().notes().stream().mapToInt(Integer::intValue)
                        .filter(midi -> midi != Exercise.REST).max().orElse(60);
                comfortNotice.setText(low < comfortableLow || high > comfortableHigh
                        ? "⚠ Маршрут выходит за ваш диапазон " + ExerciseCatalog.noteName(comfortableLow)
                          + "–" + ExerciseCatalog.noteName(comfortableHigh)
                        : "Ваш диапазон: " + ExerciseCatalog.noteName(comfortableLow)
                          + "–" + ExerciseCatalog.noteName(comfortableHigh));
            }
        }
    }

    private void showComfortRangeDialog() {
        ComboBox<Integer> low = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(36, 83).boxed().toList()));
        ComboBox<Integer> high = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(36, 83).boxed().toList()));
        StringConverter<Integer> converter = new StringConverter<>() {
            @Override public String toString(Integer midi) {
                return midi == null ? "" : ExerciseCatalog.noteName(midi);
            }
            @Override public Integer fromString(String text) { throw new UnsupportedOperationException(); }
        };
        low.setConverter(converter);
        high.setConverter(converter);
        low.setValue(preferences.getInt(COMFORT_LOW_KEY, 48));
        high.setValue(preferences.getInt(COMFORT_HIGH_KEY, 72));
        Dialog<int[]> dialog = new Dialog<>();
        dialog.initOwner(exerciseButton.getScene().getWindow());
        dialog.setTitle("Комфортный диапазон");
        dialog.setHeaderText("Укажите ноты, которые вам удобно петь без напряжения");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("theme.css").toExternalForm());
        dialog.getDialogPane().setContent(new HBox(10, label("От", "muted"), low,
                label("до", "muted"), high));
        ButtonType save = new ButtonType("Сохранить", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        var saveButton = dialog.getDialogPane().lookupButton(save);
        saveButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> low.getValue() > high.getValue(), low.valueProperty(), high.valueProperty()));
        dialog.setResultConverter(button -> button == save ? new int[]{low.getValue(), high.getValue()} : null);
        dialog.showAndWait().ifPresent(range -> {
            preferences.putInt(COMFORT_LOW_KEY, range[0]);
            preferences.putInt(COMFORT_HIGH_KEY, range[1]);
            populateCards(selectedExercise.kind());
        });
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
        int min = notes.stream().mapToInt(Integer::intValue)
                .filter(midi -> midi != Exercise.REST).min().orElse(60);
        int max = notes.stream().mapToInt(Integer::intValue)
                .filter(midi -> midi != Exercise.REST).max().orElse(67);
        double width = canvas.getWidth() / notes.size();
        String clipColor = switch (option.exercise().id().split("_")[0]) {
            case "ascending" -> "#66dcd3";
            case "descending" -> "#a8b9ff";
            case "three" -> "#e8d978";
            case "triad" -> "#f9a6ae";
            default -> "#ffad57";
        };
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i) == Exercise.REST) continue;
            double y = 66 - (notes.get(i) - min) * 46.0 / Math.max(5, max - min);
            g.setFill(javafx.scene.paint.Color.web(clipColor));
            g.fillRoundRect(i * width + 2, y, Math.max(3, width - 4), 8, 3, 3);
        }
        g.setStroke(javafx.scene.paint.Color.web("#555555"));
        g.strokeLine(0, 76, canvas.getWidth(), 76);
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
}
