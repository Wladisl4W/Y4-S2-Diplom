package ru.diplom.intonation;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ComboBox;
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
                if (args.length > 1) {
                    if (Integer.parseInt(args[1]) >= 1) {
                        ToggleButton exercise = (ToggleButton) stage.getScene().getRoot().lookupAll(".mode-button")
                                .stream().filter(node -> ((ToggleButton) node).getText().equals("Распевки"))
                                .findFirst().orElseThrow();
                        exercise.setSelected(true);
                        if (Integer.parseInt(args[1]) == 2) {
                            ComboBox<?> choices = (ComboBox<?>) stage.getScene().getRoot().lookup(".exercise-toolbar .combo-box");
                            choices.getSelectionModel().selectLast();
                        }
                    }
                }
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
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
