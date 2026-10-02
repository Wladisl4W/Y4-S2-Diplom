package ru.diplom.intonation.song;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Mixes the two aligned separated WAV stems into one output line. */
public final class SongPlayback implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 2, true, false);
    private static final class PlaybackRun {
        volatile boolean playing = true;
        volatile boolean paused;
        volatile SourceDataLine line;
        Thread worker;
    }

    private volatile boolean instrumentalEnabled = true;
    private volatile boolean vocalsEnabled;
    private final AtomicReference<PlaybackRun> current = new AtomicReference<>();

    public void setInstrumentalEnabled(boolean enabled) { instrumentalEnabled = enabled; }
    public void setVocalsEnabled(boolean enabled) { vocalsEnabled = enabled; }

    public synchronized void start(Path instrumental, Path vocals, long startNanos,
                                   LongSupplier logicalTime, Consumer<String> onError) {
        PlaybackRun previous = current.get();
        stop();
        if (previous != null && previous.worker != null) {
            try { previous.worker.join(1_000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
        PlaybackRun run = new PlaybackRun();
        current.set(run);
        run.worker = new Thread(() -> play(run, instrumental, vocals, startNanos, logicalTime, onError),
                "song-playback");
        run.worker.setDaemon(true);
        run.worker.start();
    }

    private void play(PlaybackRun run, Path instrumental, Path vocals, long startNanos,
                      LongSupplier logicalTime, Consumer<String> onError) {
        try (AudioInputStream instrumentalInput = open(instrumental);
             AudioInputStream vocalInput = open(vocals)) {
            SourceDataLine output = AudioSystem.getSourceDataLine(FORMAT);
            output.open(FORMAT, 4096 * 4);
            run.line = output;
            while (run.playing && logicalTime.getAsLong() < startNanos) Thread.sleep(10);
            if (!run.playing) return;
            output.start();
            byte[] backing = new byte[1024 * 4];
            byte[] voice = new byte[backing.length];
            byte[] mixed = new byte[backing.length];
            while (run.playing) {
                if (run.paused) { Thread.sleep(10); continue; }
                int b = instrumentalInput.readNBytes(backing, 0, backing.length);
                int v = vocalInput.readNBytes(voice, 0, voice.length);
                int bytes = Math.min(b, v) & ~1;
                if (bytes <= 0) break;
                mix(backing, voice, mixed, bytes, instrumentalEnabled, vocalsEnabled);
                output.write(mixed, 0, bytes);
            }
            if (run.playing) output.drain();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (run.playing) onError.accept("Воспроизведение недоступно: " + error.getMessage());
        } finally {
            SourceDataLine output = run.line;
            run.line = null;
            if (output != null) output.close();
            run.playing = false;
            current.compareAndSet(run, null);
        }
    }

    private static AudioInputStream open(Path path) throws IOException, UnsupportedAudioFileException {
        AudioInputStream source = AudioSystem.getAudioInputStream(path.toFile());
        if (source.getFormat().matches(FORMAT)) return source;
        try { return AudioSystem.getAudioInputStream(FORMAT, source); }
        catch (IllegalArgumentException error) {
            source.close();
            throw new IOException("Неподдерживаемый формат дорожки: " + path.getFileName(), error);
        }
    }

    static void mix(byte[] backing, byte[] voice, byte[] output, int bytes,
                    boolean playBacking, boolean playVoice) {
        for (int i = 0; i < bytes; i += 2) {
            int a = (short) ((backing[i] & 0xff) | (backing[i + 1] << 8));
            int b = (short) ((voice[i] & 0xff) | (voice[i + 1] << 8));
            int sum = (playBacking ? a : 0) + (playVoice ? b : 0);
            int clipped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
            output[i] = (byte) clipped;
            output[i + 1] = (byte) (clipped >> 8);
        }
    }

    public void pause() {
        PlaybackRun run = current.get();
        if (run == null) return;
        run.paused = true;
        SourceDataLine output = run.line;
        if (output != null) output.stop();
    }

    public void resume() {
        PlaybackRun run = current.get();
        if (run == null) return;
        run.paused = false;
        SourceDataLine output = run.line;
        if (output != null) output.start();
    }

    public synchronized void stop() {
        PlaybackRun run = current.getAndSet(null);
        if (run == null) return;
        run.playing = false;
        SourceDataLine output = run.line;
        if (output != null) { output.stop(); output.flush(); output.close(); }
        if (run.worker != null) run.worker.interrupt();
    }

    @Override public void close() { stop(); }
}
