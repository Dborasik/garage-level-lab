package dev.radixen.garagelevel.estimator;

import org.junit.Test;
import static org.junit.Assert.*;

public class PressureFilterTest {
    @Test
    public void medianAndEwmaRejectSingleLargeOutlier() {
        PressureFilter filter = new PressureFilter();
        long t = 1_000_000L;
        for (int i = 0; i < 12; i++) {
            double p = 1013.25 + (i == 4 ? 3.0 : (i % 2 == 0 ? 0.01 : -0.01));
            filter.add(p, t + i * 500L);
        }
        assertEquals(1013.25, filter.filteredPressure(), 0.08);
    }

    @Test
    public void usesTrueMedianDuringEvenSizedWarmup() {
        PressureFilter filter = new PressureFilter(9, 1.0);
        filter.add(1000.0, 1L);
        assertEquals(1005.0, filter.add(1010.0, 2L), 1e-9);
    }
}
