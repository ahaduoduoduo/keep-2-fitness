package dev.c1bridge.app;

import java.util.Random;

/** Produces deterministic HIIT signals with small independent human variations. */
final class HumanRideSimulator {
    static final int CYCLE_SECONDS = 72;

    static final class Signal {
        final int powerWatts;
        final int cadenceRpm;

        Signal(int powerWatts, int cadenceRpm) {
            this.powerWatts = powerWatts;
            this.cadenceRpm = cadenceRpm;
        }
    }

    private final Random random;
    private final int hardPower;
    private final int hardCadence;
    private final double warmCadence;
    private final double recoveryCadence;

    private int step;
    private double currentPower;
    private double currentCadence;
    private double powerNoise;
    private double cadenceNoise;

    HumanRideSimulator(int hardPower, int hardCadence, long seed) {
        this.hardPower = bounded(hardPower, 0, 999);
        this.hardCadence = bounded(hardCadence, 0, 220);
        warmCadence = this.hardCadence * 0.50d;
        recoveryCadence = this.hardCadence * 0.30d;
        currentCadence = warmCadence;
        currentPower = this.hardPower * 0.46d;
        random = new Random(seed);
    }

    Signal next() {
        if (hardPower == 0 && hardCadence == 0) {
            step++;
            return new Signal(0, 0);
        }

        int cycleSecond = (step + 6) % CYCLE_SECONDS;
        double cadenceTarget = cadenceTarget(cycleSecond);
        double effort = effortTarget(cycleSecond);

        cadenceNoise = cadenceNoise * 0.72d + random.nextGaussian() * 0.95d;
        powerNoise = powerNoise * 0.82d
                + random.nextGaussian() * Math.max(1.4d, hardPower * 0.018d);

        double cadencePulse = Math.sin(step * 0.47d) * 0.9d
                + Math.sin(step * 0.13d + 1.4d) * 0.7d;
        double desiredCadence = cadenceTarget + cadenceNoise + cadencePulse;
        currentCadence += bounded(desiredCadence - currentCadence, -25d, 11d);

        double cadenceShare = hardCadence == 0
                ? 0d
                : bounded(currentCadence / hardCadence, 0d, 1.12d);
        double independentEffort = Math.sin(step * 0.21d + 0.8d) * 0.035d;
        double desiredPower = hardPower
                * (0.78d * (effort + independentEffort) + 0.22d * cadenceShare)
                + powerNoise;
        currentPower += bounded(desiredPower - currentPower, -58d, 36d);

        step++;
        return new Signal(
                bounded((int) Math.round(currentPower), 0, 999),
                bounded((int) Math.round(currentCadence), 0, 220)
        );
    }

    private double cadenceTarget(int second) {
        if (second < 6) {
            return warmCadence;
        }
        if (second < 14) {
            return interpolate(warmCadence, hardCadence, progress(second, 6, 13));
        }
        if (second < 28) {
            return hardCadence;
        }
        if (second < 32) {
            return interpolate(hardCadence, recoveryCadence, progress(second, 28, 31));
        }
        if (second < 43) {
            return recoveryCadence;
        }
        if (second < 62) {
            return interpolate(recoveryCadence, warmCadence, progress(second, 43, 61));
        }
        return warmCadence;
    }

    private double effortTarget(int second) {
        if (second < 6) {
            return 0.45d;
        }
        if (second < 14) {
            return interpolate(0.42d, 1d, progress(second, 6, 13));
        }
        if (second < 28) {
            return 1d + Math.sin((second - 14) * 0.76d) * 0.045d;
        }
        if (second < 32) {
            return interpolate(0.92d, 0.22d, progress(second, 28, 31));
        }
        if (second < 43) {
            return 0.20d;
        }
        if (second < 62) {
            return interpolate(0.20d, 0.45d, progress(second, 43, 61));
        }
        return 0.45d;
    }

    private static double progress(int value, int start, int end) {
        return (value - start) / (double) (end - start);
    }

    private static double interpolate(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static double bounded(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
