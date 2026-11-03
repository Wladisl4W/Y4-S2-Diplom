package ru.diplom.intonation;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import ru.diplom.intonation.audio.MicrophoneCapture;
import ru.diplom.intonation.audio.Note;
import ru.diplom.intonation.audio.PitchResult;

import javax.sound.sampled.LineUnavailableException;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Owns device selection, capture lifecycle and the handoff from audio to the UI thread. */
final class MicrophoneController implements AutoCloseable {
    record Sample(long timeNanos, Optional<PitchResult> pitch) {}
    private static final String DEVICE_KEY = "microphone";
    private final MicrophoneCapture capture;
    private final Preferences preferences;
    private final LongSupplier time;
    private final BooleanSupplier paused;
    private final BooleanSupplier preserveAllSamples;
    private final Consumer<String> status;
    private final Consumer<String> failure;
    private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
    private MicrophoneCapture.Device selectedDevice;

    MicrophoneController(MicrophoneCapture capture, Preferences preferences, LongSupplier time,
                         BooleanSupplier paused, BooleanSupplier preserveAllSamples,
                         Consumer<String> status, Consumer<String> failure) {
        this.capture = capture;
        this.preferences = preferences;
        this.time = time;
        this.paused = paused;
        this.preserveAllSamples = preserveAllSamples;
        this.status = status;
        this.failure = failure;
    }

    boolean isRunning() { return capture.isRunning(); }
    Sample poll() { return samples.poll(); }
    void clearSamples() { samples.clear(); }

    void initialize(Stage owner) {
        String saved = preferences.get(DEVICE_KEY, "");
        selectedDevice = MicrophoneCapture.devices().stream()
                .filter(device -> deviceKey(device).equals(saved)).findFirst().orElse(null);
        if (selectedDevice == null) showSettings(owner, true);
        else start();
    }

    void showSettings(Stage owner, boolean required) {
        boolean wasRunning = isRunning();
        capture.stop();
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
        Label testResult = new Label("Спойте ноту после нажатия «Проверить».");
        testResult.getStyleClass().add("muted");
        Button test = new Button("Проверить микрофон");
        Button refresh = new Button("Обновить список");
        refresh.setOnAction(e -> {
            capture.stop();
            MicrophoneCapture.Device previous = choices.getValue();
            choices.setItems(FXCollections.observableArrayList(MicrophoneCapture.devices()));
            choices.getItems().stream().filter(device -> previous != null
                    && deviceKey(device).equals(deviceKey(previous))).findFirst().ifPresent(choices::setValue);
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
        Label deviceLabel = new Label("Устройство ввода");
        deviceLabel.getStyleClass().add("muted");
        dialog.getDialogPane().setContent(new VBox(12, deviceLabel, choices, new HBox(8, test, refresh), testResult));
        ButtonType save = new ButtonType("Сохранить и начать", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(choices.valueProperty().isNull());
        dialog.setResultConverter(button -> button == save ? choices.getValue() : null);
        Optional<MicrophoneCapture.Device> chosen = dialog.showAndWait();
        capture.stop();
        if (chosen.isPresent()) {
            selectedDevice = chosen.get();
            preferences.put(DEVICE_KEY, deviceKey(selectedDevice));
            try { preferences.flush(); }
            catch (BackingStoreException error) { status.accept("Не удалось сохранить выбор микрофона: " + error.getMessage()); }
            start();
        } else if (wasRunning && selectedDevice != null) start();
        else status.accept("Микрофон не выбран · откройте настройки");
    }

    void start() {
        if (selectedDevice == null || capture.isRunning()) return;
        try {
            samples.clear();
            capture.start(selectedDevice, pitch -> {
                if (paused.getAsBoolean()) return;
                while (!preserveAllSamples.getAsBoolean() && samples.size() >= 8) samples.poll();
                samples.add(new Sample(time.getAsLong(), pitch));
            }, error -> Platform.runLater(() -> {
                failure.accept(error);
                status.accept("Ошибка микрофона: " + error + " · откройте настройки");
            }));
            status.accept("Микрофон: " + selectedDevice + " · звук остаётся на этом устройстве");
        } catch (LineUnavailableException | IllegalArgumentException error) {
            status.accept("Не удалось открыть микрофон: " + error.getMessage() + " · откройте настройки");
        }
    }

    static String deviceKey(MicrophoneCapture.Device device) {
        var info = device.info();
        return info.getName() + "|" + info.getVendor() + "|" + info.getDescription();
    }

    @Override public void close() { capture.stop(); samples.clear(); }
}
