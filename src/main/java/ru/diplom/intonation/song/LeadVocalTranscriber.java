package ru.diplom.intonation.song;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the optional local multi-pitch model; callers can fall back to YIN. */
public final class LeadVocalTranscriber {
    private volatile Process process;

    public SongAnalyzer.Result analyze(Path vocals) throws IOException, InterruptedException {
        Path home = Path.of(System.getProperty("user.home"));
        Path python = home.resolve(".local/share/intonation-trainer/ml-venv/bin/python");
        if (!Files.isExecutable(python)) throw new IOException("Модуль многоголосного анализа не установлен");
        Path script = Files.createTempFile("intonation-lead-", ".py");
        try {
            try (var resource = LeadVocalTranscriber.class.getResourceAsStream("/lead_transcribe.py")) {
                if (resource == null) throw new IOException("Скрипт анализа не найден в приложении");
                Files.copy(resource, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            process = new ProcessBuilder(python.toString(), script.toString(), vocals.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            process = null;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (exit != 0) throw new IOException("Многоголосный анализ завершился с ошибкой: "
                    + output.substring(Math.max(0, output.length() - 400)).trim());
            return parse(output);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    static SongAnalyzer.Result parse(String output) throws IOException {
        double duration = -1;
        List<SongAnalyzer.NoteEvent> notes = new ArrayList<>();
        List<SongAnalyzer.Alternative> alternatives = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.split("\\t");
            try {
                if (fields.length == 2 && fields[0].equals("D")) {
                    duration = Double.parseDouble(fields[1]);
                } else if (fields.length == 5 && (fields[0].equals("N") || fields[0].equals("A"))) {
                    double start = Double.parseDouble(fields[1]);
                    double length = Double.parseDouble(fields[2]);
                    int midi = Integer.parseInt(fields[3]);
                    double confidence = Double.parseDouble(fields[4]);
                    if (!Double.isFinite(start) || !Double.isFinite(length) || !Double.isFinite(confidence)
                            || start < 0 || length <= 0 || midi < 0 || midi > 127) continue;
                    if (fields[0].equals("N")) notes.add(new SongAnalyzer.NoteEvent(start, length, midi, confidence));
                    else alternatives.add(new SongAnalyzer.Alternative(start, length, midi, confidence));
                }
            } catch (NumberFormatException ignored) { }
        }
        if (duration < 0 || !Double.isFinite(duration)) throw new IOException("Модель не вернула длительность аудио");
        return new SongAnalyzer.Result(notes, duration, alternatives);
    }

    public void cancel() {
        Process running = process;
        if (running != null) running.destroyForcibly();
    }
}
