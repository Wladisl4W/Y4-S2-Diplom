package ru.diplom.intonation.song;

import ru.diplom.intonation.audio.YinPitchDetector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Experimental mixed-audio baseline; does not claim to isolate the lead vocal. */
public final class SongAnalyzer {
    private static final int RATE = 11025;
    private static final int FRAME = 2048;
    private static final int HOP = 512;
    private static final int MAX_SECONDS = 600;
    private volatile Process activeProcess;

    public record NoteEvent(double startSeconds, double durationSeconds, int midi) {}
    public record Result(List<NoteEvent> notes, double durationSeconds) {
        public Result { notes = List.copyOf(notes); }
    }

    public Result analyze(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", file.toAbsolutePath().toString(), "-vn", "-ac", "1", "-ar", String.valueOf(RATE),
                "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        activeProcess = process;
        List<Integer> frames = new ArrayList<>();
        int samplesRead = 0;
        boolean capped = false;
        try (InputStream pcm = process.getInputStream()) {
            YinPitchDetector detector = new YinPitchDetector(RATE, FRAME, 60, 1000);
            float[] frame = new float[FRAME];
            byte[] block = new byte[HOP * 2];
            int filled = 0;
            while (samplesRead < RATE * MAX_SECONDS) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int count = pcm.readNBytes(block, 0, block.length);
                if (count < block.length) break;
                if (filled == FRAME) {
                    System.arraycopy(frame, HOP, frame, 0, FRAME - HOP);
                    filled -= HOP;
                }
                for (int i = 0; i < HOP; i++) {
                    int value = (short) ((block[2 * i] & 0xff) | (block[2 * i + 1] << 8));
                    frame[filled++] = value / 32768f;
                }
                samplesRead += HOP;
                if (filled == FRAME) {
                    Optional<Double> midi = detector.detect(frame).map(pitch ->
                            69 + 12 * Math.log(pitch.frequencyHz() / 440) / Math.log(2));
                    frames.add(midi.map(value -> (int) Math.round(value)).orElse(-1));
                }
            }
            capped = samplesRead >= RATE * MAX_SECONDS;
            if (capped) process.destroyForcibly();
        } catch (IOException | InterruptedException | RuntimeException error) {
            process.destroyForcibly();
            throw error;
        } finally {
            activeProcess = null;
        }
        int exit = process.waitFor();
        if (exit != 0 && !capped) throw new IOException("FFmpeg не смог прочитать аудиофайл");
        return new Result(groupFrames(frames), samplesRead / (double) RATE);
    }

    public void cancel() {
        Process process = activeProcess;
        if (process != null) process.destroyForcibly();
    }

    /** Group stable pitch frames into candidate notes; isolated detections are ignored. */
    static List<NoteEvent> groupFrames(List<Integer> frames) {
        if (frames.isEmpty()) return List.of();
        int[] stable = frames.stream().mapToInt(Integer::intValue).toArray();
        // A single uncertain frame must not split one sustained note.
        for (int i = 1; i < stable.length - 1; i++) {
            if (frames.get(i - 1).equals(frames.get(i + 1)) && frames.get(i - 1) >= 0)
                stable[i] = frames.get(i - 1);
        }
        List<NoteEvent> notes = new ArrayList<>();
        int runStart = 0;
        for (int i = 1; i <= stable.length; i++) {
            if (i < stable.length && stable[i] == stable[runStart]) continue;
            int midi = stable[runStart];
            if (midi >= 0 && i - runStart >= 3) {
                double start = Math.max(0, (runStart * HOP + FRAME / 2.0) / RATE);
                double duration = (i - runStart) * HOP / (double) RATE;
                notes.add(new NoteEvent(start, duration, midi));
            }
            runStart = i;
        }
        return List.copyOf(notes);
    }
}
