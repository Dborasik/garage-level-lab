package dev.radixen.garagelevel.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Format {
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private Format() {}

    public static String d(double v, int decimals) {
        if (!Double.isFinite(v)) return "—";
        return String.format(Locale.US, "% ." + decimals + "f", v).trim();
    }

    public static String f(float v, int decimals) {
        if (!Float.isFinite(v)) return "—";
        return String.format(Locale.US, "% ." + decimals + "f", v).trim();
    }

    public static String pct(double v) {
        if (!Double.isFinite(v)) return "—";
        return String.format(Locale.US, "%.0f%%", v * 100.0);
    }

    public static String clock(long millis) {
        if (millis <= 0) return "—";
        return CLOCK.format(new Date(millis));
    }
}
