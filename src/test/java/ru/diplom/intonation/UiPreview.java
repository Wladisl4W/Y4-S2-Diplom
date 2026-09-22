package ru.diplom.intonation;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Local visual QA helper: gradle uiPreview -PpreviewPath=/tmp/preview.png */
public final class UiPreview {
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Output PNG path required");
        Platform.startup(() -> {
            try {
                IntonationApp app = new IntonationApp();
                Stage stage = new Stage();
                app.start(stage);
                if (args.length > 1) {
                    TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
                    tabs.getSelectionModel().select(Integer.parseInt(args[1]));
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
