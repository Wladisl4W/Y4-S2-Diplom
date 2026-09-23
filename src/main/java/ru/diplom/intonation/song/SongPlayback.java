package ru.diplom.intonation.song;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Mixes the two aligned separated WAV stems into one output line. */
public final class SongPlayback implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 2, true, false);
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile boolean instrumentalEnabled = true;
    private volatile boolean vocalsEnabled;
    private volatile SourceDataLine line;
    private Thread worker;

    public void setInstrumentalEnabled(boolean enabled) { instrumentalEnabled = enabled; }
    public void setVocalsEnabled(boolean enabled) { vocalsEnabled = enabled; }

    public synchronized void start(Path instrumental, Path vocals, long startNanos,
                                   LongSupplier logicalTime, Consumer<String> onError) {
        stop();
        playing = true;
        paused = false;
        worker = new Thread(() -> play(instrumental, vocals, startNanos, logicalTime, onError),
                "song-playback");
        worker.setDaemon(true);
        worker.start();
    }

    private void play(Path instrumental, Path vocals, long startNanos,
                      LongSupplier logicalTime, Consumer<String> onError) {
        try (AudioInputStream instrumentalInput = open(instrumental);
             AudioInputStream vocalInput = open(vocals)) {
            SourceDataLine output = AudioSystem.getSourceDataLine(FORMAT);
            output.open(FORMAT, 4096 * 4);
            line = output;
            while (playing && logicalTime.getAsLong() < startNanos) Thread.sleep(10);
            if (!playing) return;
            output.start();
            byte[] backing = new byte[1024 * 4];
            byte[] voice = new byte[backing.length];
            byte[] mixed = new byte[backing.length];
            while (playing) {
                if (paused) { Thread.sleep(10); continue; }
                int b = instrumentalInput.readNBytes(backing, 0, backing.length);
                int v = vocalInput.readNBytes(voice, 0, voice.length);
                int bytes = Math.min(b, v) & ~1;
                if (bytes <= 0) break;
                mix(backing, voice, mixed, bytes, instrumentalEnabled, vocalsEnabled);
                output.write(mixed, 0, bytes);
            }
            if (playing) output.drain();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (playing) onError.accept("Воспроизведение недоступно: " + error.getMessage());
        } finally {
            SourceDataLine output = line;
            line = null;
            if (output != null) output.close();
            playing = false;
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
        paused = true;
        SourceDataLine output = line;
        if (output != null) output.stop();
    }

    public void resume() {
        paused = false;
        SourceDataLine output = line;
        if (output != null) output.start();
    }

    public synchronized void stop() {
        playing = false;
        SourceDataLine output = line;
        if (output != null) { output.stop(); output.flush(); output.close(); }
        if (worker != null) worker.interrupt();
        worker = null;
    }

    @Override public void close() { stop(); }
}
