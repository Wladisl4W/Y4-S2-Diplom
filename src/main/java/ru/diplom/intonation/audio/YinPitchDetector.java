package ru.diplom.intonation.audio;

import java.util.Optional;

/** Estimates a single fundamental from a mono PCM frame. */
public final class YinPitchDetector {
    private final int sampleRate;
    private final int minLag;
    private final int maxLag;
    private final double[] difference;

    public YinPitchDetector(int sampleRate, int frameSize, double minHz, double maxHz) {
        this.sampleRate = sampleRate;
        this.minLag = Math.max(2, (int) (sampleRate / maxHz));
        this.maxLag = Math.min(frameSize / 2 - 1, (int) (sampleRate / minHz));
        if (maxLag <= minLag) throw new IllegalArgumentException("Frame too short for frequency range");
        this.difference = new double[maxLag + 1];
    }

    public Optional<PitchResult> detect(float[] frame) {
        if (frame.length < 2 * (maxLag + 1)) throw new IllegalArgumentException("Frame too short");
        double power = 0;
        for (float sample : frame) power += sample * sample;
        if (Math.sqrt(power / frame.length) < 0.012) return Optional.empty();

        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i < frame.length - maxLag; i++) {
                double delta = frame[i] - frame[i + lag];
                sum += delta * delta;
            }
            difference[lag] = sum;
        }
        double running = 0;
        for (int lag = 1; lag <= maxLag; lag++) {
            running += difference[lag];
            difference[lag] = difference[lag] * lag / Math.max(running, 1e-20);
        }
        int candidate = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (difference[lag] < 0.16) {
                while (lag + 1 <= maxLag && difference[lag + 1] < difference[lag]) lag++;
                candidate = lag;
                break;
            }
        }
        if (candidate < 0) return Optional.empty();
        double refined = candidate;
        if (candidate > 1 && candidate < maxLag) {
            double left = difference[candidate - 1];
            double mid = difference[candidate];
            double right = difference[candidate + 1];
            double denominator = left - 2 * mid + right;
            if (Math.abs(denominator) > 1e-10) refined += 0.5 * (left - right) / denominator;
        }
        double confidence = Math.max(0, 1 - difference[candidate]);
        double hz = sampleRate / refined;
        if (confidence < 0.84 || !Double.isFinite(hz)) return Optional.empty();
        return Optional.of(new PitchResult(hz, confidence));
    }

}
