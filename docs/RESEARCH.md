# Research: smartphone garage detection and vertical positioning

Research reviewed through September 3, 2026.

## 1. Problem definition

The target is not generic indoor localization. The narrow task is:

1. decide whether a moving phone/vehicle has entered a parking garage; and
2. infer the garage's semantic parking level.

This is substantially easier than solving full 3-D `(x,y,z)` positioning because a parking structure supplies strong constraints: only a small number of levels exist, transitions are topologically constrained, and the phone generally enters from a known exterior trajectory.

The estimator should therefore maintain a **semantic discrete state** (garage + level) and use continuous sensors only as evidence. Treating GNSS altitude as the answer is the wrong abstraction.

## 2. What already exists

### 2.1 Barometric floor localization

Smartphone barometers have been used for floor localization for more than a decade. Representative work includes:

- **B-Loc: Scalable Floor Localization Using Barometer on Smartphone** (Ye, Gu, Tao & Lu, IEEE MASS 2014; later journal version 2016). B-Loc uses phone pressure observations and crowdsourced/reference information to infer floor. DOI: https://doi.org/10.1109/MASS.2014.49 ; journal DOI: https://doi.org/10.1002/wcm.2706
- **Floor Identification with Smartphone Barometer** and related pressure-pair approaches show that relative/differential pressure is much more useful than raw absolute pressure because weather and device offsets contaminate absolute altitude.
- **Falcon & Schulzrinne, “Predicting Floor-Level for 911 Calls with Neural Networks and Smartphone Sensor Data”** uses GPS-signal changes to infer building entry/exit and barometric pressure change from the entrance for floor inference. The authors report 63 experiments across five NYC buildings in their study. Preprint: https://arxiv.org/abs/1710.11122
- **A comprehensive algorithm for vertical positioning in multi-building environments using smartphones** (Scientific Reports, 2024) combines building entry/exit detection, pressure filtering, and floor-change logic. It specifically discusses pressure noise from environmental/HVAC effects and uses temporal statistics rather than individual samples. https://www.nature.com/articles/s41598-024-64824-9

The exact accuracy percentages in published experiments are not portable to arbitrary garages. They establish feasibility, not a universal performance guarantee. Device model, enclosure, HVAC, reference availability and building topology matter.

### 2.2 Parking-garage vehicle localization

Research on underground/multi-storey parking localization commonly uses combinations of:

- inertial dead reckoning;
- turn/trajectory signatures;
- graph or road-network matching;
- particle filters / Bayesian state estimation;
- map constraints that reject trajectories crossing walls or impossible links;
- occasional anchors (GNSS near entrances, Wi-Fi, BLE, visual landmarks, magnetic fingerprints).

A useful lesson from this body of work is that **the map should be part of the estimator, not just a display layer**. A noisy trajectory becomes much more useful when impossible states/transitions have zero or near-zero probability.

For this prototype, full 2-D dead reckoning was deliberately not implemented. Phone mounting/orientation, inertial drift, and vehicle vibration add complexity, while the vertical problem already has a strong barometric signal. The accelerometer/gyroscope are collected and displayed for diagnosis and controlled follow-up experiments.

## 3. Android sensor/platform facts

### Galaxy S8 compatibility

Samsung's Galaxy S8 launched with Android 7.0 and includes a barometer, accelerometer and gyroscope. Therefore API 24 is a practical minimum for the requested device target.

Samsung specification/support page:
https://www.samsung.com/us/support/owners/product/galaxy-s8-unlocked

### Pressure sensor

Android exposes `Sensor.TYPE_PRESSURE`. It is a hardware sensor and is not guaranteed to exist on every Android device, so applications must detect it at runtime.

Android environment sensor documentation:
https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment

Android also supplies `SensorManager.getAltitude(p0, p)` and explicitly notes that absolute altitude is inaccurate when the sea-level reference pressure is wrong; relative altitude differences are the appropriate use without live reference pressure:
https://developer.android.com/reference/android/hardware/SensorManager#getAltitude(float,%20float)

### Foreground location

Long-running navigation-like location use belongs in a foreground service. Modern Android requires `foregroundServiceType="location"`; Android 14+ also requires `FOREGROUND_SERVICE_LOCATION`. A service requiring while-in-use location permission must be started while the activity is visible unless an exemption/background permission applies.

Official docs:
https://developer.android.com/about/versions/14/changes/fgs-types-required
https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

This prototype starts tracking only from a visible activity and intentionally does **not** ask for background-location permission.

## 4. Why barometric relative altitude works

Pressure falls as elevation rises. Android documents the standard-atmosphere pressure/altitude relationship, but absolute barometric altitude depends on knowing an appropriate reference pressure. For this application absolute altitude is unnecessary. The implementation uses the entry pressure as the short-session reference:

`Δh = 44330 * (1 - (p_current / p_entry)^(1 / 5.255))`

where pressure is hPa. Over a short garage visit this pressure-ratio form removes the need for a sea-level pressure reference and largely cancels fixed sensor offset. It is still an approximation because local temperature, weather, HVAC and vehicle-cabin pressure affect the pressure-height relationship.

Near sea level, a rough intuition is that 1 hPa corresponds to about 8 meters of altitude, so a 3 m floor is only on the order of 0.35–0.4 hPa. That is why raw samples and naive rounding are inadequate.

NASA standard-atmosphere background:
https://www.grc.nasa.gov/www/k-12/airplane/atmosmet.html

## 5. Pressure error sources

The implementation assumes these errors exist:

- **Weather drift**: sea-level pressure changes with weather. Relative sessions reduce but do not eliminate it.
- **Device bias**: two phones can report different absolute pressure. Relative differencing avoids most fixed offset.
- **Temperature/device compensation**: sensor-specific behavior can add drift.
- **HVAC/cabin pressure**: opening/closing doors, fans, windows and pressure differentials can perturb readings.
- **Fast transients**: bumps, wind and enclosure changes can produce outliers.
- **True sloped geometry**: a car can be between levels for tens of seconds.

The 2024 Scientific Reports algorithm reports ordinary environmental disturbances in experiments on a scale that is non-trivial relative to a floor transition, reinforcing the need for moving statistics and hysteresis rather than a single pressure threshold.

## 6. Filtering conventions used here

The prototype uses:

1. **9-sample median**: rejects short outliers without being strongly affected by their magnitude.
2. **EWMA** with alpha `0.18`: smooths the median while retaining enough response for a vehicle ramp.
3. **3.5-second sample standard deviation**: exposed as a quality diagnostic.
4. **4-second least-squares slope** of pressure-altitude vs time: estimates vertical speed and therefore transition direction.
5. **~120-second in-memory pressure history**: enough for entry anchoring and short fallback windows without accumulating an unnecessary long-term trace.

These parameters should be tuned only from labeled driving data across multiple devices and garages, not from one garage.

## 7. Garage entry detection

There is no universally reliable single “garage entered” signal.

Useful evidence:

- mapped garage containment/proximity;
- a recent exterior trajectory approaching the structure;
- degradation in GNSS horizontal accuracy;
- lower GNSS carrier-to-noise density (C/N0);
- fewer satellites used in fix;
- vehicle-like speed;
- vertical barometric movement shortly after entry;
- map knowledge of entrances/ramps.

GNSS deterioration on building entry is used in published indoor-transition work, including the Falcon/Schulzrinne and 2024 Scientific Reports approaches. It should not be used alone: tunnels, urban canyons, tree cover and phone placement can produce similar degradation.

### Prototype score

For mapped candidates:

`C_enter = clamp(0.76*M + 0.14*G + 0.10*V, 0, 1)`

where:

- `M`: mapped containment/proximity score;
- `G`: GNSS degradation score relative to an outdoor EWMA baseline;
- `V`: coarse vehicle movement score.

Three consecutive qualifying observations are required before declaring entry.

If there is no mapped garage, an intentionally conservative fallback requires **strong GNSS degradation + vehicle motion + substantial recent barometric vertical span**. The UI labels this `Possible unmapped garage` because tunnels/hills can still fool it.

These values are heuristic scores, not statistically calibrated posterior probabilities. The UI calls them confidence for usability but the field-test plan explicitly requires calibration before making probabilistic claims.

## 8. Floor estimation as constrained Bayesian state estimation

Let the discrete state be the valid physical floor `F_t` and the observation be relative altitude `z_t`.

For each candidate physical level index `i`, expected height is:

`μ_i = (i - i_entry) * H`

where `H` is floor-to-floor height. Importantly, `i` is the **ordinal index among valid floors**, not the numeric OSM level. If OSM says level 13 does not exist, moving from logical 12 to logical 14 is one physical floor, not two.

The observation likelihood is modeled as Gaussian:

`L_i = exp(-0.5 * ((z_t - μ_i) / σ)^2)`

The prototype increases `σ` while the measured vertical speed is high because ramp motion means the car may legitimately be between floor planes.

A transition prior then favors:

- current floor;
- adjacent physical floors;
- weak probability for two-floor motion;
- very weak probability for larger instantaneous jumps.

Vertical-speed direction downweights floor transitions opposite the current vertical direction.

Weights are normalized:

`P_i = L_i * T_i / Σ_j(L_j * T_j)`

The state changes only when the best floor passes hysteresis and residual-to-floor thresholds. In this repository, this remains a deliberately small recursive Bayesian/HMM-style floor filter.

## 9. Garage/floor map data

### OpenStreetMap

OSM is useful because it is global, open, and has conventions directly relevant to the problem:

- `amenity=parking` + `parking=multi-storey` / `parking=underground`;
- `building=parking` **only when other tags establish multi-level/underground structure** (the tag alone can describe a single-storey parking building);
- `building:levels`;
- `building:levels:underground`;
- `level` where `0` normally means ground and negatives are basement levels;
- `level:ref` for the signposted name such as `B1`, `G`, `P3`; fractional or multi-valued `level=*` values can be valid OSM, so this integer-floor prototype rejects ambiguous entrance anchors instead of rounding or silently taking the first value;
- `min_level`, `max_level`;
- `non_existent_levels` for skipped numbers;
- `amenity=parking_entrance`, potentially carrying level metadata.

References:
https://wiki.openstreetmap.org/wiki/Tag:parking=multi-storey
https://wiki.openstreetmap.org/wiki/Simple_Indoor_Tagging
https://wiki.openstreetmap.org/wiki/Key:level

OSM's main weakness is **coverage/completeness**, not the data model. Many garages have only a footprint or parking tag and no accurate floor/entrance/ramp topology.

### Overpass

The prototype queries a roughly 500 m bounding box and throttles automatic refreshes to once per two minutes unless the device has moved significantly. This is intentionally small-scale public-instance usage. The public Overpass service should not be treated as general-purpose production infrastructure.

Overpass documentation:
https://wiki.openstreetmap.org/wiki/Overpass_API

## 10. Satellite imagery / LiDAR

Satellite/airborne imagery and LiDAR can help infer:

- footprint;
- roof elevation/building height;
- entrances in some circumstances;
- approximate number of stories.

They generally cannot reliably provide semantic parking labels, internal split levels, ramp connectivity, skipped floor numbering or which entrance lands on which deck. They are best treated as enrichment or a data-quality cross-check, not the primary floor topology source.

## 11. Why no calibration is required by default

The prototype anchors pressure **at the inferred entrance**, so it needs the change in pressure, not correct sea-level pressure. A fixed barometer offset cancels from the difference.

Calibration becomes useful when:

- the entry level is not known;
- map floor height is unusual;
- long sessions accumulate weather/HVAC drift;
- a reference station/device is available.

Differential barometry using a nearby reference pressure can suppress common-mode weather changes; the literature shows this can improve relative altitude estimation. It is not used by this prototype.

## 12. Privacy/security conventions

- No account or device identifier is needed for this prototype.
- No raw location history is persisted.
- HTTPS only; cleartext traffic disabled in the manifest.
- The Overpass request necessarily reveals an approximate current bounding box to the Overpass service.
- OSM network failures retain only the previous in-memory map set.
- The app uses a visible foreground service for continuous tracking.
- This prototype has no diagnostics upload or project telemetry endpoint.

## 13. Main conclusion

The concept is technically credible. The barometer is not accurate enough to say “absolute altitude = floor 3” by itself, but it is often accurate enough to detect **relative vertical transitions** over a short session. The garage map supplies the missing semantic/topological constraints. The engineering problem is therefore a sensor-fusion/state-estimation problem plus a map-coverage problem, not a raw-GPS problem.
