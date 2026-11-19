package com.hyflip.server.engine;

import java.util.List;

/**
 * Fits the forge speed the known modifiers do not explain, from observed run times.
 *
 * <p>With samples of different base durations, a constant ratio {@code observed ÷ calculated}
 * means an extra percentage; a constant difference {@code calculated − observed} means a fixed
 * reduction. See {@code docs/forge.md}.
 */
public final class Calibration {

    /** Ratios within this relative spread count as one percentage. */
    static final double RATIO_TOLERANCE = 0.05;
    /** Differences within this many seconds (or 5% of the mean calculated time) count as fixed. */
    static final long FIXED_TOLERANCE_SECONDS = 120;

    private Calibration() {
    }

    /**
     * @param baseSeconds     NEU base duration of the recipe
     * @param knownReduction  Quick Forge + Molten Forge reduction in effect when it was measured
     * @param observedSeconds real duration the player saw
     */
    public record Sample(long baseSeconds, double knownReduction, long observedSeconds) {

        public double calculatedSeconds() {
            return baseSeconds * (1 - knownReduction);
        }
    }

    public enum Kind { NONE, PERCENTAGE, FIXED, INCONSISTENT }

    /**
     * @param kind         what the samples show
     * @param factor       PERCENTAGE: real = calculated × factor
     * @param fixedSeconds FIXED: real = calculated − fixedSeconds
     * @param samples      number of samples used
     * @param provisional  true while fewer than 3 samples or fewer than 2 base durations back the fit
     */
    public record Fit(Kind kind, double factor, long fixedSeconds, int samples, boolean provisional) {

        public static final Fit NONE = new Fit(Kind.NONE, 1, 0, 0, true);

        /** Real duration from the duration the known modifiers give. Unchanged unless the fit is usable. */
        public long apply(long calculatedSeconds) {
            return switch (kind) {
                case PERCENTAGE -> Math.round(calculatedSeconds * factor);
                case FIXED -> Math.max(0, calculatedSeconds - fixedSeconds);
                case NONE, INCONSISTENT -> calculatedSeconds;
            };
        }
    }

    public static Fit fit(List<Sample> samples) {
        List<Sample> usable = samples.stream()
                .filter(s -> s.baseSeconds() > 0 && s.observedSeconds() > 0 && s.calculatedSeconds() > 0)
                .toList();
        if (usable.isEmpty()) {
            return Fit.NONE;
        }
        int n = usable.size();
        double[] ratios = usable.stream().mapToDouble(s -> s.observedSeconds() / s.calculatedSeconds()).toArray();
        double[] diffs = usable.stream().mapToDouble(s -> s.calculatedSeconds() - s.observedSeconds()).toArray();
        double meanRatio = mean(ratios);
        long distinctBases = usable.stream().mapToLong(Sample::baseSeconds).distinct().count();
        boolean provisional = n < 3 || distinctBases < 2;

        if (distinctBases < 2) {
            // One duration cannot tell a percentage from a fixed amount; a percentage is the safer guess.
            return new Fit(Kind.PERCENTAGE, meanRatio, 0, n, true);
        }
        if ((max(ratios) - min(ratios)) / meanRatio <= RATIO_TOLERANCE) {
            return new Fit(Kind.PERCENTAGE, meanRatio, 0, n, provisional);
        }
        double meanCalculated = usable.stream().mapToDouble(Sample::calculatedSeconds).average().orElse(0);
        double tolerance = Math.max(FIXED_TOLERANCE_SECONDS, meanCalculated * 0.05);
        if (max(diffs) - min(diffs) <= tolerance) {
            return new Fit(Kind.FIXED, 1, Math.round(mean(diffs)), n, provisional);
        }
        return new Fit(Kind.INCONSISTENT, 1, 0, n, provisional);
    }

    private static double mean(double[] v) {
        double sum = 0;
        for (double x : v) {
            sum += x;
        }
        return sum / v.length;
    }

    private static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            m = Math.max(m, x);
        }
        return m;
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) {
            m = Math.min(m, x);
        }
        return m;
    }
}
