package ru.diplom.intonation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version embedded from Gradle and shared by window chrome and package metadata. */
public final class AppVersion {
    private static final String VALUE = load();

    private AppVersion() {}

    public static String value() { return VALUE; }
    public static String displayName() { return "Тренировка интонации · v" + VALUE; }

    private static String load() {
        try (InputStream stream = AppVersion.class.getResourceAsStream("version.properties")) {
            if (stream == null) throw new IllegalStateException("Version resource is missing");
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version", "");
            if (!version.matches("\\d+\\.\\d+\\.\\d+"))
                throw new IllegalStateException("Invalid application version: " + version);
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read application version", e);
        }
    }
}
