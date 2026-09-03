package dev.radixen.garagelevel.estimator;

import org.junit.Test;
import static org.junit.Assert.*;

public class AltitudeMathTest {
    @Test
    public void relativeAltitudeTracksThreeMeters() {
        double p0 = 1013.25;
        double p3 = p0 * Math.pow(1.0 - 3.0 / 44330.0, 5.255);
        assertEquals(3.0, AltitudeMath.relativeAltitudeMeters(p0, p3), 0.03);
        assertEquals(-3.0, AltitudeMath.relativeAltitudeMeters(p3, p0), 0.03);
    }

    @Test
    public void relativeAltitudeUsesLocalPressureRatio() {
        double baseline = 850.0;
        double p3 = baseline * Math.pow(1.0 - 3.0 / 44330.0, 5.255);
        assertEquals(3.0, AltitudeMath.relativeAltitudeMeters(baseline, p3), 0.03);
    }
}
