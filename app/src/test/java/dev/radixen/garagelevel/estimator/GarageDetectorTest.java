package dev.radixen.garagelevel.estimator;

import dev.radixen.garagelevel.model.Garage;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class GarageDetectorTest {
    @Test
    public void entersMappedGarageAfterSustainedEvidence() {
        Garage garage = new Garage("way", 1L, "Test Garage", "multi-storey", 34.0, -84.0,
                Arrays.asList(
                        new double[]{33.9998, -84.0002},
                        new double[]{33.9998, -83.9998},
                        new double[]{34.0002, -83.9998},
                        new double[]{34.0002, -84.0002}),
                4, 0, null, null, Collections.emptySet(), null, Collections.emptyList());
        GarageDetector detector = new GarageDetector();
        GarageDetector.Observation o = null;
        for (int i = 0; i < 3; i++) {
            o = detector.update(34.0, -84.0, 8f, 8, 32, 3f, 0.2, Collections.singletonList(garage));
        }
        assertNotNull(o);
        assertTrue(o.detected);
        assertTrue(o.justEntered);
        assertEquals("Test Garage", o.garage.name);
    }

    @Test
    public void passingNearGarageDoesNotEnterWithoutIndoorOrVerticalEvidence() {
        Garage garage = new Garage("way", 1L, "Test Garage", "multi-storey", 34.0, -84.0,
                Arrays.asList(
                        new double[]{33.9998, -84.0002},
                        new double[]{33.9998, -83.9998},
                        new double[]{34.0002, -83.9998},
                        new double[]{34.0002, -84.0002}),
                4, 0, null, null, Collections.emptySet(), null, Collections.emptyList());
        GarageDetector detector = new GarageDetector();
        GarageDetector.Observation o = null;
        for (int i = 0; i < 5; i++) {
            o = detector.update(34.00025, -84.0, 8f, 9, 34, 10f, 0.2, Collections.singletonList(garage));
        }
        assertNotNull(o);
        assertFalse(o.detected);
    }

    @Test
    public void resetClearsExteriorGnssBaseline() {
        GarageDetector detector = new GarageDetector();
        for (int i = 0; i < 10; i++) {
            detector.update(34.01, -84.01, 6f, 10, 36, 10f, 0.1, Collections.emptyList());
        }
        detector.reset();
        GarageDetector.Observation o = null;
        for (int i = 0; i < 4; i++) {
            o = detector.update(34.01, -84.01, 65f, 1, 12, 10f, 5.0, Collections.emptyList());
        }
        assertNotNull(o);
        assertFalse(o.detected);
    }
}
