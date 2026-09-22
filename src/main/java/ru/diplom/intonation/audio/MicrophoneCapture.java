package ru.diplom.intonation.audio;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class MicrophoneCapture {
    public static final int SAMPLE_RATE = 44100;
    public static final int FRAME_SIZE = 4096;
    private static final int HOP_SIZE = 1024;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private volatile boolean running;
    private volatile TargetDataLine line;
    private Thread worker;

    public record Device(Mixer.Info info) {
        @Override public String toString() { return info.getName(); }
    }

    public static List<Device> devices() {
        List<Device> result = new ArrayList<>();
        DataLine.Info target = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(target)) result.add(new Device(info));
        }
        return result;
    }

    public synchronized void start(Device device, Consumer<Optional<PitchResult>> onPitch,
                                   Consumer<String> onError) throws LineUnavailableException {
        if (running) return;
        DataLine.Info target = new DataLine.Info(TargetDataLine.class, FORMAT);
        TargetDataLine opened = (TargetDataLine) AudioSystem.getMixer(device.info()).getLine(target);
        opened.open(FORMAT, FRAME_SIZE * 4);
        opened.start();
        line = opened;
        running = true;
        worker = new Thread(() -> captureLoop(opened, onPitch, onError), "microphone-capture");
        worker.setDaemon(true);
        worker.start();
    }

    private void captureLoop(TargetDataLine source, Consumer<Optional<PitchResult>> onPitch,
                             Consumer<String> onError) {
        YinPitchDetector detector = new YinPitchDetector(SAMPLE_RATE, FRAME_SIZE, 80, 1000);
        float[] frame = new float[FRAME_SIZE];
        byte[] bytes = new byte[HOP_SIZE * 2];
        int filled = 0;
        try {
            while (running) {
                int read = source.read(bytes, 0, bytes.length);
                if (read <= 0) throw new IllegalStateException("Микрофон перестал передавать звук");
                int samples = read / 2;
                if (filled + samples > FRAME_SIZE) {
                    int discard = filled + samples - FRAME_SIZE;
                    System.arraycopy(frame, discard, frame, 0, filled - discard);
                    filled -= discard;
                }
                for (int i = 0; i < samples; i++) {
                    int value = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
                    frame[filled++] = value / 32768f;
                }
                if (filled == FRAME_SIZE) onPitch.accept(detector.detect(frame));
            }
        } catch (Exception e) {
            if (running) onError.accept(e.getMessage() == null ? "Ошибка чтения микрофона" : e.getMessage());
        } finally {
            running = false;
            source.stop();
            source.close();
        }
    }

    public synchronized void stop() {
        running = false;
        TargetDataLine current = line;
        line = null;
        if (current != null) current.close();
    }

    public boolean isRunning() { return running; }
}
