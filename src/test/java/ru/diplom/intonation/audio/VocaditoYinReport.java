package ru.diplom.intonation.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Research-only evaluation on locally held Vocadito WAV and F0 files. */
public final class VocaditoYinReport {
    private static final int RATE = 44_100;
    private static final int SIZE = 4096;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Dataset directory and report path required");
        Path dataset = Path.of(args[0]);
        Totals totals = new Totals();
        for (int number = 1; number <= 40; number++) {
            Path audio = dataset.resolve("Audio/vocadito_" + number + ".wav");
            Path labels = dataset.resolve("Annotations/F0/vocadito_" + number + "_f0.csv");
            if (!Files.isRegularFile(audio) || !Files.isRegularFile(labels))
                throw new IllegalArgumentException("Missing Vocadito recording or labels: " + number);
            evaluate(audio, labels, totals);
        }
        Collections.sort(totals.errors);
        String report = String.format(Locale.ROOT, """
                # YIN on Vocadito solo singing

                40 local, annotated solo-vocal WAV files. Centered 4096-sample windows at about 100 ms intervals;
                matching thresholds: 50 cents and one semitone. The production detector is used without extra smoothing.

                | Metric | Result |
                | --- | ---: |
                | Voiced frames | %d |
                | Voice detected on voiced frames | %.1f%% |
                | Correct within 50 cents | %.1f%% |
                | Correct within one semitone | %.1f%% |
                | False voice on silent frames | %.1f%% |
                | Median absolute error on detected voice | %.1f cents |
                | Mean processing time | %.2f ms/frame |

                Centered windows have about %.1f ms of audio; this is not a measured live UI latency.
                Vocadito solo singing does not represent songs with accompaniment.
                """, totals.voiced, percent(totals.detected, totals.voiced),
                percent(totals.correct50, totals.voiced), percent(totals.correct100, totals.voiced),
                percent(totals.falseVoiced, totals.silence), median(totals.errors),
                totals.processingNanos / 1e6 / totals.frames, SIZE * 1000.0 / RATE);
        Path destination = Path.of(args[1]);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static void evaluate(Path audio, Path labels, Totals totals) throws Exception {
        byte[] bytes;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(audio.toFile())) {
            AudioFormat format = stream.getFormat();
            if (format.getSampleRate() != RATE || format.getChannels() != 1
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian())
                throw new IllegalArgumentException("Expected 44.1 kHz mono 16-bit PCM: " + audio);
            bytes = stream.readAllBytes();
        }
        float[] audioSamples = new float[bytes.length / 2];
        for (int i = 0; i < audioSamples.length; i++) {
            int pcm = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
            audioSamples[i] = pcm / 32768f;
        }
        YinPitchDetector detector = new YinPitchDetector(RATE, SIZE, 80, 1000);
        float[] frame = new float[SIZE];
        List<String> rows = Files.readAllLines(labels, StandardCharsets.UTF_8);
        for (int row = 0; row < rows.size(); row += 17) {
            String[] fields = rows.get(row).split(",");
            if (fields.length < 2) continue;
            double seconds = Double.parseDouble(fields[0]);
            double expectedHz = Double.parseDouble(fields[1]);
            int start = (int) Math.round(seconds * RATE) - SIZE / 2;
            if (start < 0 || start + SIZE > audioSamples.length) continue;
            System.arraycopy(audioSamples, start, frame, 0, SIZE);
            long before = System.nanoTime();
            var result = detector.detect(frame);
            totals.processingNanos += System.nanoTime() - before;
            totals.frames++;
            if (expectedHz > 0) {
                totals.voiced++;
                if (result.isPresent()) {
                    totals.detected++;
                    double cents = Math.abs(1200 * Math.log(result.get().frequencyHz() / expectedHz) / Math.log(2));
                    totals.errors.add(cents);
                    if (cents <= 50) totals.correct50++;
                    if (cents <= 100) totals.correct100++;
                }
            } else {
                totals.silence++;
                if (result.isPresent()) totals.falseVoiced++;
            }
        }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0 : 100.0 * numerator / denominator;
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return Double.NaN;
        int midpoint = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(midpoint - 1) + sorted.get(midpoint)) / 2
                : sorted.get(midpoint);
    }

    private static final class Totals {
        int voiced, detected, correct50, correct100, silence, falseVoiced, frames;
        long processingNanos;
        List<Double> errors = new ArrayList<>();
    }
}
