package ru.diplom.intonation.song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Separates two local stems before extracting candidate notes from vocals only. */
public final class SongPipeline implements AutoCloseable {
    private static final String MODEL = "UVR_MDXNET_KARA_2.onnx";
    private volatile Process process;
    private volatile SongAnalyzer analyzer;
    private Path output;

    public record Result(Path vocals, Path instrumental, SongAnalyzer.Result transcription) {}

    public Result analyze(Path input) throws IOException, InterruptedException {
        return analyze(input, ignored -> { });
    }

    public Result analyze(Path input, Consumer<String> progress) throws IOException, InterruptedException {
        Path home = Path.of(System.getProperty("user.home"));
        Path modelDir = home.resolve(".local/share/intonation-trainer/models");
        if (!Files.isRegularFile(modelDir.resolve(MODEL)))
            throw new IOException("Локальная модель разделения не найдена: " + modelDir.resolve(MODEL));
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
        progress.accept("Отделение вокала от инструментала…");
        process = new ProcessBuilder(command, decoded.toString(),
                "-m", MODEL, "--output_format", "WAV", "--output_dir", output.toString(),
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
        Path instrumental = stem(stems, "(Instrumental)");
        progress.accept("Определение нот по вокалу…");
        analyzer = new SongAnalyzer();
        SongAnalyzer.Result transcription = analyzer.analyze(vocals);
        return new Result(vocals, instrumental, transcription);
    }

    private static Path stem(List<Path> paths, String marker) throws IOException {
        return paths.stream().filter(path -> path.getFileName().toString().contains(marker))
                .findFirst().orElseThrow(() -> new IOException("Не найдена дорожка " + marker));
    }

    public void cancel() {
        Process running = process;
        if (running != null) running.destroyForcibly();
        SongAnalyzer runningAnalyzer = analyzer;
        if (runningAnalyzer != null) runningAnalyzer.cancel();
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
