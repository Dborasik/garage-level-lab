import dev.radixen.garagelevel.estimator.AltitudeMath;
import dev.radixen.garagelevel.estimator.FloorEstimator;
import dev.radixen.garagelevel.estimator.GarageDetector;
import dev.radixen.garagelevel.estimator.PressureFilter;
import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.util.Geo;

import java.util.Arrays;
import java.util.Collections;

public final class CoreSelfTest {
    public static void main(String[] args) {
        double p0 = 1013.25;
        double p3 = p0 * Math.pow(1.0 - 3.0 / 44330.0, 5.255);
        assertNear(3.0, AltitudeMath.relativeAltitudeMeters(p0, p3), 0.03, "3m pressure conversion");
        double lowBaseline = 850.0;
        double lowP3 = lowBaseline * Math.pow(1.0 - 3.0 / 44330.0, 5.255);
        assertNear(3.0, AltitudeMath.relativeAltitudeMeters(lowBaseline, lowP3), 0.03, "3m pressure ratio at low baseline");

        PressureFilter warmupMedian = new PressureFilter(9, 1.0);
        warmupMedian.add(1000.0, 1L);
        double twoSampleMedian = warmupMedian.add(1010.0, 2L);
        assertNear(1005.0, twoSampleMedian, 1e-9, "even warm-up median");

        PressureFilter pressure = new PressureFilter();
        long baseTime = 1_000_000L;
        for (int i = 0; i < 12; i++) {
            double p = 1013.25 + (i == 4 ? 3.0 : (i % 2 == 0 ? 0.01 : -0.01));
            pressure.add(p, baseTime + i * 500L);
        }
        require(Math.abs(pressure.filteredPressure() - 1013.25) < 0.08, "median/EWMA should reject pressure outlier");

        FloorEstimator floor = new FloorEstimator();
        floor.start(0, Arrays.asList(0, 1, 2, 3), Collections.emptyMap(), 3.0);
        floor.update(1.0, 0.2);
        FloorEstimator.Estimate estimate = floor.update(3.02, 0.03);
        require(estimate.logicalLevel == 1, "floor transition should reach logical level 1");

        FloorEstimator skipped = new FloorEstimator();
        skipped.start(0, Arrays.asList(0, 1, 2, 4), Collections.emptyMap(), 3.0);
        skipped.update(3.0, 0.0);
        skipped.update(6.0, 0.0);
        FloorEstimator.Estimate skippedEstimate = skipped.update(9.0, 0.0);
        require(skippedEstimate.logicalLevel == 4, "nonexistent level must not create a phantom physical floor");

        FloorEstimator normalizedAnchor = new FloorEstimator();
        normalizedAnchor.start(0, Arrays.asList(1, 2, 3), Collections.emptyMap(), 3.0);
        require(normalizedAnchor.getAnchorLevel() == 1, "estimator must not invent an entry level excluded by valid topology");

        FloorEstimator recovery = new FloorEstimator();
        recovery.start(0, Arrays.asList(0, 1, 2, 3, 4), Collections.emptyMap(), 3.0);
        FloorEstimator.Estimate recovered = recovery.update(9.02, 0.0);
        require(recovered.logicalLevel == 3, "decisive observation should recover after missed intermediate updates");

        Garage garage = new Garage("way", 1L, "Test Garage", "multi-storey", 34.0, -84.0,
                Arrays.asList(new double[]{33.9998, -84.0002}, new double[]{33.9998, -83.9998},
                        new double[]{34.0002, -83.9998}, new double[]{34.0002, -84.0002}),
                4, 0, null, null, Collections.emptySet(), null, Collections.emptyList());
        GarageDetector emptyDetector = new GarageDetector();
        GarageDetector.Observation emptyObs = null;
        for (int i = 0; i < 10; i++) emptyObs = emptyDetector.update(34.01, -84.01, 8f, 9, 34, 12f, 0.2, Collections.emptyList());
        require(emptyObs != null && !emptyObs.detected, "good outdoor GNSS without a map must not trigger fallback");
        require(garage.distanceMeters(34.00025, -84.0) < 10.0, "garage proximity should use polygon boundary rather than centroid");

        java.util.Set<Integer> missingGround = new java.util.HashSet<>();
        missingGround.add(0);
        Garage noGroundGarage = new Garage("way", 3L, "No Ground Garage", "multi-storey", 34.0, -84.0,
                garage.polygon, 4, 0, 0, 3, missingGround, null, Collections.emptyList());
        require(!noGroundGarage.validLevels(0).contains(0), "mapped non_existent_levels must not be reintroduced as an assumed entry floor");

        Garage unusableTopology = new Garage("way", 4L, "Bad Metadata", "multi-storey", 34.0, -84.0,
                garage.polygon, 0, 0, 5, 2, Collections.emptySet(), null, Collections.emptyList());
        require(!unusableTopology.topologyKnown(), "invalid/zero level metadata must not be advertised as known topology");

        double[] polarBox = Geo.boundingBox(89.999999, 0.0, 500.0);
        for (double v : polarBox) require(Double.isFinite(v), "polar bounding box must stay finite");

        java.util.List<double[]> relationRing = Arrays.asList(
                new double[]{33.9995, -84.0010}, new double[]{33.9995, -83.9990},
                new double[]{34.0005, -83.9990}, new double[]{34.0005, -84.0010},
                new double[]{33.9995, -84.0010});
        Garage relationGarage = new Garage("relation", 2L, "Relation Garage", "multi-storey", 34.0, -84.0,
                Collections.emptyList(), Collections.singletonList(relationRing),
                4, 0, null, null, Collections.emptySet(), null, Collections.emptyList());
        require(relationGarage.distanceMeters(34.0, -84.0008) == 0.0,
                "closed relation member geometry should provide interior proximity without fake flattened containment");

        GarageDetector resetDetector = new GarageDetector();
        for (int i = 0; i < 10; i++) resetDetector.update(34.01, -84.01, 6f, 10, 36, 10f, 0.1, Collections.emptyList());
        resetDetector.reset();
        GarageDetector.Observation afterReset = null;
        for (int i = 0; i < 4; i++) afterReset = resetDetector.update(34.01, -84.01, 65f, 1, 12, 10f, 5.0, Collections.emptyList());
        require(afterReset != null && !afterReset.detected, "detector reset must clear learned exterior GNSS baselines");

        GarageDetector passByDetector = new GarageDetector();
        GarageDetector.Observation passBy = null;
        for (int i = 0; i < 5; i++) passBy = passByDetector.update(34.00025, -84.0, 8f, 9, 34, 10f, 0.2, Collections.singletonList(garage));
        require(passBy != null && !passBy.detected, "passing close to a garage with good GNSS and no vertical evidence must not count as entry");

        GarageDetector promotionDetector = new GarageDetector();
        for (int i = 0; i < 10; i++) promotionDetector.update(34.01, -84.01, 6f, 10, 36, 10f, 0.1, Collections.emptyList());
        GarageDetector.Observation fallbackObs = null;
        for (int i = 0; i < 3; i++) fallbackObs = promotionDetector.update(34.0, -84.0, 65f, 1, 12, 8f, 5.0, Collections.emptyList());
        require(fallbackObs != null && fallbackObs.detected && fallbackObs.fallbackUnmapped,
                "strong unmapped evidence should enter a labelled fallback session");
        GarageDetector.Observation promoted = promotionDetector.update(34.0, -84.0, 40f, 3, 18, 5f, 5.0, Collections.singletonList(garage));
        require(promoted.detected && !promoted.fallbackUnmapped && promoted.garage != null,
                "active fallback should promote when a strong mapped candidate arrives");

        GarageDetector detector = new GarageDetector();
        GarageDetector.Observation obs = null;
        for (int i = 0; i < 3; i++) obs = detector.update(34.0, -84.0, 8f, 8, 32, 3f, 0.2, Collections.singletonList(garage));
        require(obs != null && obs.detected, "mapped garage should be detected after sustained evidence");

        FloorEstimator labelled = new FloorEstimator();
        java.util.Map<Integer, String> refs = new java.util.HashMap<>();
        refs.put(2, "P3");
        labelled.start(2, Arrays.asList(0, 1, 2, 3), refs, 3.0);
        require("P3 (OSM level 2)".equals(labelled.labelFor(2)), "signposted level reference should remain primary label");

        System.out.println("Core self-tests passed.");
    }

    private static void assertNear(double expected, double actual, double tolerance, String name) {
        if (Math.abs(expected - actual) > tolerance) throw new AssertionError(name + ": expected " + expected + " got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
