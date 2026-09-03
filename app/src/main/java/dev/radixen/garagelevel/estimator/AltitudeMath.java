package dev.radixen.garagelevel.estimator;

public final class AltitudeMath {
    public static final double STANDARD_PRESSURE_HPA = 1013.25;
    private static final double EXPONENT = 1.0 / 5.255;

    private AltitudeMath() {}

    public static double pressureAltitudeMeters(double pressureHpa) {
        if (!(pressureHpa > 0.0)) return Double.NaN;
        return 44330.0 * (1.0 - Math.pow(pressureHpa / STANDARD_PRESSURE_HPA, EXPONENT));
    }

    /**
     * Estimates vertical displacement from a short-term pressure ratio.
     *
     * Treating the baseline pressure as the local reference avoids the scale bias introduced
     * by subtracting two "standard atmosphere" absolute pressure altitudes when the device is
     * far from sea level or local sea-level pressure is far from 1013.25 hPa.
     */
    public static double relativeAltitudeMeters(double baselinePressureHpa, double currentPressureHpa) {
        if (!(baselinePressureHpa > 0.0) || !(currentPressureHpa > 0.0)) return Double.NaN;
        return 44330.0 * (1.0 - Math.pow(currentPressureHpa / baselinePressureHpa, EXPONENT));
    }
}
