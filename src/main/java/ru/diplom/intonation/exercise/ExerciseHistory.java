package ru.diplom.intonation.exercise;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

public final class ExerciseHistory {
    private final Path path;

    public ExerciseHistory() {
        this(Path.of(System.getProperty("user.home"), ".intonation-trainer", "progress.csv"));
    }

    public ExerciseHistory(Path path) { this.path = path; }

    public void append(ExerciseSession.Result result) throws IOException {
        Files.createDirectories(path.getParent());
        String row = Instant.now() + "," + result.exerciseId() + "," + result.score() + ","
                + result.coverage() + "," + result.averageErrorCents() + System.lineSeparator();
        Files.writeString(path, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public List<String> recent(int limit) throws IOException {
        if (!Files.exists(path)) return List.of();
        List<String> rows = Files.readAllLines(path, StandardCharsets.UTF_8);
        return rows.subList(Math.max(0, rows.size() - limit), rows.size()).reversed();
    }
}
