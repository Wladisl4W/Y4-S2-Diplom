package ru.diplom.intonation.song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Separates two local stems before extracting candidate notes from vocals only. */
public final class SongPipeline implements AutoCloseable {
    private static final String DEFAULT_MODEL = "vocals_mel_band_roformer.ckpt";
    private final String selectedModel;
    private volatile Process process;
    private volatile SongAnalyzer analyzer;
    private volatile LeadVocalTranscriber leadTranscriber;
    private Path output;

    public record Result(Path vocals, Path instrumental, SongAnalyzer.Result transcription) {}

    public SongPipeline() { this(DEFAULT_MODEL); }
    public SongPipeline(String selectedModel) { this.selectedModel = selectedModel; }

    public Result analyze(Path input) throws IOException, InterruptedException {
        return analyze(input, ignored -> { });
    }

    public Result analyze(Path input, Consumer<String> progress) throws IOException, InterruptedException {
        Path home = Path.of(System.getProperty("user.home"));
        Path modelDir = home.resolve(".local/share/intonation-trainer/models");
        String model = System.getenv("INTONATION_SEPARATOR_MODEL");
        if (model == null || model.isBlank()) model = selectedModel;
        if (!model.matches("[A-Za-z0-9_.-]+")) throw new IOException("Неверное имя модели разделения");
        if (model.equals("UVR_MDXNET_KARA_2.onnx"))
            throw new IOException("Karaoke 2 предназначена для разделения ведущего и фоновых голосов, "
                    + "а не для первого отделения вокала от инструментов");
        if (!Files.isRegularFile(modelDir.resolve(model)))
            throw new IOException("Локальная модель разделения не найдена: " + modelDir.resolve(model));
        if (model.equals("htdemucs_ft.yaml")) {
            for (String weight : List.of("f7e0c4bc-ba3fe64a.th", "d12395a8-e57c48e6.th",
                    "92cfc3b6-ef3bcb9c.th", "04573f0d-f3cf25b2.th"))
                if (!Files.isRegularFile(modelDir.resolve(weight)))
                    throw new IOException("Не все веса Demucs установлены локально. Запустите setup_song_analysis.sh");
        }
        String command = System.getenv("INTONATION_SEPARATOR");
        if (command == null || command.isBlank()) {
            Path local = home.resolve(".local/share/intonation-trainer/python/bin/audio-separator");
            command = Files.isExecutable(local) ? local.toString() : "audio-separator";
        }
        output = Files.createTempDirectory("intonation-stems-");
        progress.accept("Подготовка аудио…");
        Path decoded = output.resolve("source.wav");
        process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-y", "-i", input.toAbsolutePath().toString(), "-t", "600",
                "-ac", "2", "-ar", "44100", decoded.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        int decodeExit = process.waitFor();
        process = null;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (decodeExit != 0) throw new IOException("FFmpeg не смог прочитать аудиофайл");
        progress.accept(model.equals("htdemucs_ft.yaml") || model.endsWith(".ckpt")
                ? "Тщательное отделение вокала… на CPU это может занять много минут"
                : "Отделение вокала от инструментала…");
        process = new ProcessBuilder(command, decoded.toString(),
                "-m", model, "--output_format", "WAV", "--output_dir", output.toString(),
                "--model_file_dir", modelDir.toString(), "--log_level", "ERROR")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        int exit = process.waitFor();
        process = null;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (exit != 0) throw new IOException("Модель не смогла разделить файл на вокал и инструментал");
        List<Path> stems;
        try (var paths = Files.list(output)) {
            stems = paths.filter(path -> path.getFileName().toString().endsWith(".wav")).toList();
        }
        Path vocals = stem(stems, "(Vocals)");
        Path instrumental;
        try {
            instrumental = stem(stems, "(Instrumental)");
        } catch (IOException missing) {
            Path other = stem(stems, "(Other)");
            Path bass = optionalStem(stems, "(Bass)");
            Path drums = optionalStem(stems, "(Drums)");
            if (bass != null && drums != null) {
                instrumental = output.resolve("instrumental-combined.wav");
                process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-y", "-i", other.toString(), "-i", bass.toString(), "-i", drums.toString(),
                        "-filter_complex", "amix=inputs=3:duration=longest:normalize=0",
                        instrumental.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                int combineExit = process.waitFor();
                process = null;
                if (combineExit != 0) throw new IOException("Не удалось собрать инструментальную дорожку");
            } else instrumental = other;
        }
        progress.accept("Поиск ведущей вокальной линии и альтернатив…");
        SongAnalyzer.Result transcription;
        try {
            leadTranscriber = new LeadVocalTranscriber();
            transcription = leadTranscriber.analyze(vocals);
            if (transcription.notes().isEmpty()) throw new IOException("Модель не нашла нот");
        } catch (IOException error) {
            progress.accept("Многоголосный анализ недоступен; применяю резервный YIN…");
            analyzer = new SongAnalyzer();
            transcription = analyzer.analyze(vocals);
        }
        return new Result(vocals, instrumental, transcription);
    }

    private static Path stem(List<Path> paths, String marker) throws IOException {
        return paths.stream().filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .contains(marker.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElseThrow(() -> new IOException("Не найдена дорожка " + marker));
    }

    private static Path optionalStem(List<Path> paths, String marker) {
        return paths.stream().filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .contains(marker.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null);
    }

    public void cancel() {
        Process running = process;
        if (running != null) running.destroyForcibly();
        SongAnalyzer runningAnalyzer = analyzer;
        if (runningAnalyzer != null) runningAnalyzer.cancel();
        LeadVocalTranscriber runningLead = leadTranscriber;
        if (runningLead != null) runningLead.cancel();
    }

    @Override public void close() {
        cancel();
        Path directory = output;
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }
}
