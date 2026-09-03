package dev.radixen.garagelevel.estimator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class PressureFilter {
    public static final class TimedSample {
        public final long timeMillis;
        public final double pressureHpa;

        TimedSample(long timeMillis, double pressureHpa) {
            this.timeMillis = timeMillis;
            this.pressureHpa = pressureHpa;
        }
    }

    private final int medianWindowSize;
    private final double ewmaAlpha;
    private final Deque<Double> rawWindow = new ArrayDeque<>();
    private final Deque<TimedSample> history = new ArrayDeque<>();
    private Double filteredPressure;

    public PressureFilter() {
        this(9, 0.18);
    }

    public PressureFilter(int medianWindowSize, double ewmaAlpha) {
        if (medianWindowSize < 3 || medianWindowSize % 2 == 0) throw new IllegalArgumentException("median window must be odd and >=3");
        if (!(ewmaAlpha > 0 && ewmaAlpha <= 1)) throw new IllegalArgumentException("alpha must be (0,1]");
        this.medianWindowSize = medianWindowSize;
        this.ewmaAlpha = ewmaAlpha;
    }

    public synchronized double add(double pressureHpa, long timeMillis) {
        if (!(pressureHpa > 100.0 && pressureHpa < 1200.0)) return filteredPressure == null ? Double.NaN : filteredPressure;
        rawWindow.addLast(pressureHpa);
        while (rawWindow.size() > medianWindowSize) rawWindow.removeFirst();

        List<Double> sorted = new ArrayList<>(rawWindow);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        double median = sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
        filteredPressure = filteredPressure == null ? median : ewmaAlpha * median + (1.0 - ewmaAlpha) * filteredPressure;

        history.addLast(new TimedSample(timeMillis, filteredPressure));
        long cutoff = timeMillis - 120_000L;
        while (!history.isEmpty() && history.peekFirst().timeMillis < cutoff) history.removeFirst();
        return filteredPressure;
    }

    public synchronized double filteredPressure() {
        return filteredPressure == null ? Double.NaN : filteredPressure;
    }

    public synchronized boolean hasRecentSample(long nowMillis, long maxAgeMillis) {
        if (history.isEmpty()) return false;
        long age = nowMillis - history.peekLast().timeMillis;
        return age >= 0L && age <= maxAgeMillis;
    }

    public synchronized double standardDeviationHpa(long nowMillis, long windowMillis) {
        List<Double> values = new ArrayList<>();
        long cutoff = nowMillis - windowMillis;
        for (TimedSample s : history) if (s.timeMillis >= cutoff) values.add(s.pressureHpa);
        if (values.size() < 2) return Double.NaN;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.size();
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    public synchronized double verticalSpeedMps(long nowMillis, long windowMillis) {
        long cutoff = nowMillis - windowMillis;
        List<TimedSample> samples = recentSamples(cutoff);
        if (samples.size() < 3) return Double.NaN;

        double referencePressure = samples.get(0).pressureHpa;
        double t0 = samples.get(0).timeMillis / 1000.0;
        double meanT = 0;
        double meanA = 0;
        for (TimedSample s : samples) {
            meanT += s.timeMillis / 1000.0 - t0;
            meanA += AltitudeMath.relativeAltitudeMeters(referencePressure, s.pressureHpa);
        }
        meanT /= samples.size();
        meanA /= samples.size();
        double num = 0;
        double den = 0;
        for (TimedSample s : samples) {
            double t = s.timeMillis / 1000.0 - t0;
            double altitude = AltitudeMath.relativeAltitudeMeters(referencePressure, s.pressureHpa);
            double dt = t - meanT;
            num += dt * (altitude - meanA);
            den += dt * dt;
        }
        return den <= 1e-9 ? Double.NaN : num / den;
    }

    public synchronized double historicalPressure(long nowMillis, long secondsAgo) {
        if (history.isEmpty()) return Double.NaN;
        long target = nowMillis - secondsAgo * 1000L;
        TimedSample best = history.peekFirst();
        long bestDelta = Math.abs(best.timeMillis - target);
        for (TimedSample sample : history) {
            long delta = Math.abs(sample.timeMillis - target);
            if (delta < bestDelta) {
                best = sample;
                bestDelta = delta;
            }
        }
        return best.pressureHpa;
    }

    public synchronized double altitudeSpanMeters(long nowMillis, long windowMillis) {
        long cutoff = nowMillis - windowMillis;
        List<TimedSample> samples = recentSamples(cutoff);
        if (samples.size() < 2) return 0.0;
        double referencePressure = samples.get(0).pressureHpa;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (TimedSample s : samples) {
            double altitude = AltitudeMath.relativeAltitudeMeters(referencePressure, s.pressureHpa);
            min = Math.min(min, altitude);
            max = Math.max(max, altitude);
        }
        return max - min;
    }

    private List<TimedSample> recentSamples(long cutoff) {
        List<TimedSample> samples = new ArrayList<>();
        for (TimedSample s : history) if (s.timeMillis >= cutoff) samples.add(s);
        return samples;
    }

    public synchronized void reset() {
        rawWindow.clear();
        history.clear();
        filteredPressure = null;
    }
}
