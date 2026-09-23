package ru.diplom.intonation;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;

import ru.diplom.intonation.exercise.ExerciseCatalog;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Local visual QA helper: gradle uiPreview -PpreviewPath=/tmp/preview.png */
public final class UiPreview {
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 4) throw new IllegalArgumentException("Output PNG path required");
        System.setProperty("intonation.skipMicrophoneSetup", "true");
        Platform.startup(() -> {
            try {
                IntonationApp app = new IntonationApp();
                Stage stage = new Stage();
                app.start(stage);
                if (!stage.getTitle().equals(AppVersion.displayName()))
                    throw new IllegalStateException("Window title does not show current version");
                if (args.length > 3) {
                    stage.setWidth(Double.parseDouble(args[2]));
                    stage.setHeight(Double.parseDouble(args[3]));
                }
                StackPane sharedGraph = (StackPane) stage.getScene().getRoot().lookup(".graph-card");
                if (args.length > 1 && Integer.parseInt(args[1]) >= 1) {
                    ToggleButton exercise = (ToggleButton) stage.getScene().getRoot().lookupAll(".mode-button")
                            .stream().filter(node -> ((ToggleButton) node).getText().equals("Распевки"))
                            .findFirst().orElseThrow();
                    exercise.setSelected(true);
                    if (Integer.parseInt(args[1]) == 4) {
                        @SuppressWarnings("unchecked")
                        ComboBox<Integer> root = (ComboBox<Integer>) stage.getScene().getRoot()
                                .lookup("#exercise-pitch");
                        root.setValue(36);
                    }
                    if (Integer.parseInt(args[1]) >= 3) {
                        Button card = (Button) stage.getScene().getRoot().lookup(".pattern-card");
                        card.fire();
                        if (!((ToggleButton) stage.getScene().getRoot().lookupAll(".mode-button").stream()
                                .filter(node -> ((ToggleButton) node).getText().equals("Живой голос"))
                                .findFirst().orElseThrow()).isSelected())
                            throw new IllegalStateException("Card did not return to the live canvas");
                    }
                    if (Integer.parseInt(args[1]) == 2) {
                        ToggleButton sets = (ToggleButton) stage.getScene().getRoot().lookupAll(".library-filter")
                                .stream().filter(node -> ((ToggleButton) node).getText().equals("Наборы"))
                                .findFirst().orElseThrow();
                        sets.setSelected(true);
                    }
                }
                if (sharedGraph != stage.getScene().getRoot().lookup(".graph-card"))
                    throw new IllegalStateException("Main graph instance changed");
                PauseTransition delay = new PauseTransition(Duration.seconds(
                        args.length > 1 && Integer.parseInt(args[1]) == 5 ? 10 : 1));
                delay.setOnFinished(event -> {
                    try {
                        WritableImage image = stage.getScene().snapshot(null);
                        int width = (int) image.getWidth(), height = (int) image.getHeight();
                        BufferedImage bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < height; y++)
                            for (int x = 0; x < width; x++)
                                bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                        ImageIO.write(bitmap, "png", new File(args[0]));
                        app.stop();
                        Platform.exit();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                delay.play();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
