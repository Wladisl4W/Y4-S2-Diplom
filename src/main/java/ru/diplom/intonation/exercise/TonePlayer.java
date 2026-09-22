package ru.diplom.intonation.exercise;

import javax.sound.sampled.*;

public final class TonePlayer {
    private TonePlayer() {}

    public static void playAsync(Exercise exercise) {
        Thread worker = new Thread(() -> play(exercise), "reference-tones");
        worker.setDaemon(true);
        worker.start();
    }

    private static void play(Exercise exercise) {
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            int rate = (int) format.getSampleRate();
            int samplesPerNote = (int) (Math.min(1.0, exercise.secondsPerNote() * 0.6) * rate);
            byte[] bytes = new byte[samplesPerNote * 2];
            for (int midi : exercise.notes()) {
                double hz = 440 * Math.pow(2, (midi - 69) / 12.0);
                for (int i = 0; i < samplesPerNote; i++) {
                    double envelope = Math.min(1, Math.min(i / 900.0, (samplesPerNote - i) / 900.0));
                    short value = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 7000 * envelope);
                    bytes[2 * i] = (byte) value;
                    bytes[2 * i + 1] = (byte) (value >>> 8);
                }
                line.write(bytes, 0, bytes.length);
            }
            line.drain();
        } catch (LineUnavailableException ignored) {
            // The UI remains usable when an output device is unavailable.
        }
    }
}
