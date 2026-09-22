package ru.diplom.intonation.audio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Deterministic synthetic detector measurement; not a substitute for real singing recordings. */
public final class PitchQualityReport {
    private static final int RATE = 44_100;
    private static final int SIZE = 4096;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Report path required");
        YinPitchDetector detector = new YinPitchDetector(RATE, SIZE, 80, 1000);
        List<Double> errors = new ArrayList<>();
        long processingNanos = 0;
        int missed = 0;
        int total = 0;
        Random random = new Random(2026);
        for (int midi = 45; midi <= 81; midi += 3) {
            for (int cents : new int[]{-25, 0, 25}) {
                double expected = 440 * Math.pow(2, (midi - 69 + cents / 100.0) / 12);
                for (int phase = 0; phase < 4; phase++) {
                    float[] frame = new float[SIZE];
                    for (int i = 0; i < SIZE; i++)
                        frame[i] = (float) (0.25 * Math.sin(2 * Math.PI * expected * i / RATE + phase)
                                + (random.nextDouble() - 0.5) * 0.01);
                    long started = System.nanoTime();
                    var result = detector.detect(frame);
                    processingNanos += System.nanoTime() - started;
                    total++;
                    if (result.isEmpty()) missed++;
                    else errors.add(Math.abs(1200 * Math.log(result.get().frequencyHz() / expected) / Math.log(2)));
                }
            }
        }
        int falseVoiced = 0;
        for (int sample = 0; sample < 100; sample++) {
            float[] frame = new float[SIZE];
            if (sample >= 50) for (int i = 0; i < SIZE; i++)
                frame[i] = (random.nextFloat() - 0.5f) * 0.3f;
            if (detector.detect(frame).isPresent()) falseVoiced++;
        }
        Collections.sort(errors);
        double median = percentile(errors, 0.5);
        double p95 = percentile(errors, 0.95);
        double meanMs = processingNanos / 1e6 / total;
        String report = String.format(Locale.ROOT, """
                # Synthetic pitch detector report

                Deterministic sine tones with light white noise, MIDI 45–81, offsets −25/0/+25 cents, four phases each.
                Sample rate: %d Hz; frame: %d samples (%.1f ms). Results are synthetic and do not predict accuracy on real singing.

                | Metric | Result |
                | --- | ---: |
                | Tone frames | %d |
                | Missing tone detections | %d |
                | Median absolute pitch error | %.2f cents |
                | 95th percentile absolute error | %.2f cents |
                | False voiced, 50 silent + 50 noise frames | %d |
                | Mean detector processing time | %.2f ms/frame |

                Processing time depends on the machine and JVM. The frame duration is an input window, not measured UI latency.
                """, RATE, SIZE, SIZE * 1000.0 / RATE, total, missed, median, p95, falseVoiced, meanMs);
        Path path = Path.of(args[0]);
        Files.createDirectories(path.getParent());
        Files.writeString(path, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static double percentile(List<Double> sorted, double probability) {
        if (sorted.isEmpty()) return Double.NaN;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(probability * sorted.size()) - 1));
    }
}
