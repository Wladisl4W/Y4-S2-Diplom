package ru.diplom.intonation;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import ru.diplom.intonation.exercise.ExerciseCatalog;
import ru.diplom.intonation.song.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Owns song import, transcription review and corrections; practice stays on the shared canvas. */
final class SongLibrary implements AutoCloseable {
    private static final String SONG_MODEL_KEY = "song_separator_model";
    private final Preferences preferences;
    private final Runnable onBeforeEdit;
    private final Runnable onPractice;
    private final SongEditStore songEditStore = new SongEditStore(Path.of(System.getProperty("user.home"),
            ".local/share/intonation-trainer/song-edits"));
    private final VBox page;
    private Task<SongPipeline.Result> songTask;
    private SongPipeline songPipeline;
    private SongPipeline.Result readySong;
    private Button practiceSong;
    private Label songStatus;
    private ListView<String> songNotes;
    private Canvas songOverview;
    private SongAnalyzer.Result songResult;
    private Path currentSongFile;

    SongLibrary(Stage owner, Preferences preferences, Runnable onBeforeEdit, Runnable onPractice) {
        this.preferences = preferences;
        this.onBeforeEdit = onBeforeEdit;
        this.onPractice = onPractice;
        this.page = createPage(owner);
    }

    VBox page() { return page; }
    SongPipeline.Result readySong() { return readySong; }

    private VBox createPage(Stage owner) {
        Label heading = label("Разбор песни · исследовательский режим", "library-title");
        Label explanation = label("MP3 локально делится на вокал и инструментал. Модель предлагает ведущую линию и варианты при наложении голосов.\nПроверьте ноты перед занятием: автоматический разбор может ошибаться.", "muted");
        explanation.setWrapText(true);
        explanation.setMinHeight(36);
        songStatus = label("Выберите MP3 для просмотра черновой нотной линии.", "muted");
        songStatus.setWrapText(true);
        Button open = primaryButton("Открыть MP3…");
        Button cancel = new Button("Отменить анализ");
        cancel.setDisable(true);
        ComboBox<String> songModel = new ComboBox<>(FXCollections.observableArrayList(
                "vocals_mel_band_roformer.ckpt", "htdemucs_ft.yaml", "UVR-MDX-NET_Main_406.onnx"));
        songModel.setPrefWidth(210);
        songModel.setTooltip(new Tooltip("Тщательные модели лучше сохраняют вокал, "
                + "но 30 с аудио могут обрабатываться несколько минут."));
        songModel.setConverter(new StringConverter<>() {
            @Override public String toString(String model) {
                if (model == null) return "";
                return switch (model) {
                    case "vocals_mel_band_roformer.ckpt" -> "Максимум · MelBand";
                    case "htdemucs_ft.yaml" -> "Точно · Demucs";
                    default -> "Быстро · UVR-MDX";
                };
            }
            @Override public String fromString(String text) { throw new UnsupportedOperationException(); }
        });
        String savedModel = preferences.get(SONG_MODEL_KEY, "vocals_mel_band_roformer.ckpt");
        songModel.setValue(songModel.getItems().contains(savedModel)
                ? savedModel : "vocals_mel_band_roformer.ckpt");
        songModel.valueProperty().addListener((obs, old, value) -> preferences.put(SONG_MODEL_KEY, value));
        practiceSong = primaryButton("Петь на главном полотне →");
        practiceSong.setDisable(true);
        practiceSong.setOnAction(e -> onPractice.run());
        open.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Выбрать песню");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Аудиофайлы", "*.mp3", "*.wav"));
            File file = chooser.showOpenDialog(owner);
            if (file == null) return;
            currentSongFile = file.toPath();
            onBeforeEdit.run();
            if (songPipeline != null) songPipeline.close();
            readySong = null;
            practiceSong.setDisable(true);
            songNotes.getItems().clear();
            songResult = null;
            drawSongOverview(null);
            songStatus.setText("Анализ: " + file.getName() + "…");
            open.setDisable(true);
            cancel.setDisable(false);
            songPipeline = new SongPipeline(songModel.getValue());
            SongPipeline pipeline = songPipeline;
            Task<SongPipeline.Result> task = new Task<>() {
                @Override protected SongPipeline.Result call() throws Exception {
                    return pipeline.analyze(file.toPath(), this::updateMessage);
                }
            };
            songTask = task;
            task.messageProperty().addListener((obs, old, message) -> {
                if (task.isRunning() && message != null) songStatus.setText(message);
            });
            task.setOnSucceeded(event -> {
                readySong = task.getValue();
                songResult = readySong.transcription();
                boolean restoredEdits = false;
                String editWarning = "";
                try {
                    SongAnalyzer.Result generated = songResult;
                    songResult = songEditStore.load(file.toPath(), songResult);
                    readySong = new SongPipeline.Result(readySong.vocals(), readySong.instrumental(), songResult);
                    restoredEdits = songResult != generated;
                } catch (IOException error) {
                    editWarning = " · исправления не прочитаны: " + error.getMessage();
                }
                songStatus.setText(file.getName() + " · вокал отделён · "
                        + songResult.notes().size() + " кандидатов в ноты"
                        + (restoredEdits ? " · восстановлены ручные исправления" : "")
                        + (songResult.durationSeconds() >= 599 ? " · первые 10 минут" : "")
                        + editWarning);
                refreshSongNotes();
                practiceSong.setDisable(songResult.notes().isEmpty());
                open.setDisable(false);
                cancel.setDisable(true);
            });
            task.setOnFailed(event -> {
                Throwable error = task.getException();
                pipeline.close();
                songStatus.setText("Не удалось обработать файл: " + error.getMessage()
                        + ". Нужны локальная модель, Audio Separator и FFmpeg.");
                open.setDisable(false);
                cancel.setDisable(true);
            });
            task.setOnCancelled(event -> {
                pipeline.close();
                songStatus.setText("Анализ отменён");
                open.setDisable(false);
                cancel.setDisable(true);
            });
            Thread worker = new Thread(task, "song-analysis");
            worker.setDaemon(true);
            worker.start();
        });
        cancel.setOnAction(e -> cancelSongAnalysis());
        HBox modelSelector = new HBox(8, label("Разделение", "muted"), songModel);
        modelSelector.setAlignment(Pos.CENTER_LEFT);
        FlowPane actions = new FlowPane(10, 8, open, cancel, practiceSong, modelSelector);
        actions.setAlignment(Pos.CENTER_LEFT);
        songOverview = new Canvas(800, 120);
        songNotes = new ListView<>();
        songNotes.setPrefHeight(250);
        songNotes.setMinHeight(100);
        VBox.setVgrow(songNotes, Priority.ALWAYS);
        Label columns = label("Время       Длительность       Предполагаемая нота       Оценка модели", "muted");
        Button noteDown = new Button("− полутон");
        Button noteUp = new Button("+ полутон");
        Button noteDelete = new Button("Удалить ноту");
        Button noteAlternative = new Button("Выбрать другой голос");
        noteDown.setOnAction(e -> editSongNote(-1, false));
        noteUp.setOnAction(e -> editSongNote(1, false));
        noteDelete.setOnAction(e -> editSongNote(0, true));
        noteAlternative.setOnAction(e -> useSongAlternative());
        HBox corrections = new HBox(8, noteDown, noteUp, noteDelete, noteAlternative);
        VBox songPage = new VBox(9, heading, explanation, actions, songStatus, songOverview,
                columns, songNotes, corrections);
        VBox.setVgrow(songPage, Priority.ALWAYS);
        explanation.prefWidthProperty().bind(songPage.widthProperty().subtract(4));
        songStatus.prefWidthProperty().bind(songPage.widthProperty().subtract(4));
        songOverview.widthProperty().bind(songPage.widthProperty().subtract(2));
        songOverview.widthProperty().addListener((obs, old, value) -> drawSongOverview(songResult));
        drawSongOverview(null);
        return songPage;
    }

    private void cancelSongAnalysis() {
        if (songPipeline != null) songPipeline.cancel();
        if (songTask != null) songTask.cancel(true);
        if (songPipeline != null) songPipeline.close();
    }

    private void editSongNote(int semitones, boolean delete) {
        if (readySong == null) return;
        int index = songNotes.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= readySong.transcription().notes().size()) return;
        onBeforeEdit.run();
        List<SongAnalyzer.NoteEvent> changed = new java.util.ArrayList<>(readySong.transcription().notes());
        SongAnalyzer.NoteEvent note = changed.get(index);
        if (delete) changed.remove(index);
        else changed.set(index, new SongAnalyzer.NoteEvent(note.startSeconds(),
                note.durationSeconds(), Math.max(0, Math.min(127, note.midi() + semitones))));
        songResult = new SongAnalyzer.Result(changed, readySong.transcription().durationSeconds(),
                readySong.transcription().alternatives());
        readySong = new SongPipeline.Result(readySong.vocals(), readySong.instrumental(), songResult);
        refreshSongNotes();
        if (!changed.isEmpty()) songNotes.getSelectionModel().select(Math.min(index, changed.size() - 1));
        practiceSong.setDisable(changed.isEmpty());
        songStatus.setText("Нотная линия исправлена вручную · " + changed.size() + " нот");
        saveSongEdits();
    }

    private void useSongAlternative() {
        if (readySong == null) return;
        int index = songNotes.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= songResult.notes().size()) return;
        SongAnalyzer.NoteEvent selected = songResult.notes().get(index);
        List<SongAnalyzer.Alternative> choices = songResult.alternatives().stream()
                .filter(candidate -> Math.abs(candidate.startSeconds() - selected.startSeconds()) < 0.06
                        && Math.abs(candidate.durationSeconds() - selected.durationSeconds()) < 0.06)
                .filter(candidate -> candidate.midi() != selected.midi())
                .collect(Collectors.toMap(SongAnalyzer.Alternative::midi, candidate -> candidate,
                        (a, b) -> a.confidence() >= b.confidence() ? a : b))
                .values().stream().sorted(java.util.Comparator.comparingDouble(
                        SongAnalyzer.Alternative::confidence).reversed()).limit(5).toList();
        if (choices.isEmpty()) {
            songStatus.setText("Для этой ноты альтернативная вокальная линия не обнаружена");
            return;
        }
        List<String> labels = IntStream.range(0, choices.size()).mapToObj(i ->
                ExerciseCatalog.noteName(choices.get(i).midi())
                        + String.format(" · оценка модели %.0f%%", choices.get(i).confidence() * 100)
                        + " · вариант " + (i + 1)).toList();
        ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.getFirst(), labels);
        dialog.setTitle("Ведущая вокальная линия");
        dialog.setHeaderText("Выберите голос для участка с "
                + String.format("%.1f с", selected.startSeconds()));
        dialog.setContentText("Нота:");
        dialog.showAndWait().ifPresent(label -> {
            SongAnalyzer.Alternative candidate = choices.get(labels.indexOf(label));
            onBeforeEdit.run();
            List<SongAnalyzer.NoteEvent> changed = new java.util.ArrayList<>(songResult.notes());
            changed.set(index, new SongAnalyzer.NoteEvent(selected.startSeconds(),
                    selected.durationSeconds(), candidate.midi()));
            songResult = new SongAnalyzer.Result(changed, songResult.durationSeconds(),
                    songResult.alternatives());
            readySong = new SongPipeline.Result(readySong.vocals(), readySong.instrumental(), songResult);
            refreshSongNotes();
            songNotes.getSelectionModel().select(index);
            songStatus.setText("Выбрана другая вокальная линия для ноты");
            saveSongEdits();
        });
    }

    private void saveSongEdits() {
        if (currentSongFile == null || songResult == null) return;
        try {
            songEditStore.save(currentSongFile, songResult);
        } catch (IOException error) {
            songStatus.setText("Ноты изменены, но не удалось сохранить исправления: " + error.getMessage());
        }
    }

    private void refreshSongNotes() {
        if (songResult == null) return;
        songNotes.getItems().setAll(songResult.notes().stream().map(note -> {
            boolean alternatives = songResult.alternatives().stream().anyMatch(candidate ->
                    Math.abs(candidate.startSeconds() - note.startSeconds()) < 0.06
                            && Math.abs(candidate.durationSeconds() - note.durationSeconds()) < 0.06
                            && candidate.midi() != note.midi());
            return String.format("%6.1f с   %4.1f с   %s%s", note.startSeconds(),
                    note.durationSeconds(), ExerciseCatalog.noteName(note.midi())
                            + (Double.isFinite(note.confidence())
                            ? String.format("   %.0f%%", note.confidence() * 100) : ""),
                    alternatives ? "   ◇ другой голос" : "");
        }).toList());
        drawSongOverview(songResult);
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

    @Override public void close() {
        cancelSongAnalysis();
        if (songPipeline != null) songPipeline.close();
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
