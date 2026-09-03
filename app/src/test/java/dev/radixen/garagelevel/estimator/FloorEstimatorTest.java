package dev.radixen.garagelevel.estimator;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FloorEstimatorTest {
    @Test
    public void movesToAdjacentFloorNearExpectedAltitude() {
        FloorEstimator estimator = new FloorEstimator();
        estimator.start(0, Arrays.asList(0, 1, 2, 3), Collections.emptyMap(), 3.0);
        estimator.update(1.0, 0.3);
        FloorEstimator.Estimate e = estimator.update(3.02, 0.05);
        assertEquals(1, e.logicalLevel);
        assertTrue(e.confidence > 0.5);
    }

    @Test
    public void recoversFromMissedIntermediateSamples() {
        FloorEstimator estimator = new FloorEstimator();
        estimator.start(0, Arrays.asList(0, 1, 2, 3, 4), Collections.emptyMap(), 3.0);
        FloorEstimator.Estimate e = estimator.update(9.02, 0.0);
        assertEquals(3, e.logicalLevel);
    }

    @Test
    public void nonExistentNumericLevelDoesNotCreatePhysicalGap() {
        FloorEstimator estimator = new FloorEstimator();
        estimator.start(0, Arrays.asList(0, 1, 2, 4), Collections.emptyMap(), 3.0);
        estimator.update(3.0, 0.0);
        estimator.update(6.0, 0.0);
        FloorEstimator.Estimate e = estimator.update(9.0, 0.0);
        assertEquals(4, e.logicalLevel);
    }

    @Test
    public void signpostedReferenceIsPrimaryDisplayLabel() {
        FloorEstimator estimator = new FloorEstimator();
        java.util.Map<Integer, String> refs = new java.util.HashMap<>();
        refs.put(2, "P3");
        estimator.start(2, Arrays.asList(0, 1, 2, 3), refs, 3.0);
        assertEquals("P3 (OSM level 2)", estimator.labelFor(2));
    }

    @Test
    public void normalizesAnchorToNearestValidMappedLevel() {
        FloorEstimator estimator = new FloorEstimator();
        estimator.start(0, Arrays.asList(1, 2, 3), Collections.emptyMap(), 3.0);
        assertEquals(1, estimator.getAnchorLevel());
    }
}
