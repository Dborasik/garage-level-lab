# Algorithm and math

## State

At runtime the app maintains:

- `outside` or `inside garage` state;
- selected mapped garage (or an explicitly labeled unmapped fallback);
- pressure baseline at garage entry;
- valid ordered logical levels;
- current logical level;
- filtered pressure and recent pressure history;
- exterior GNSS-quality baselines.

## 1. Relative pressure altitude

The implementation uses a short-session **pressure ratio** rather than treating the phone's pressure as an absolute sea-level altitude:

```text
Δh = 44330 * (1 - (p_current / p_entry)^(1/5.255))
```

`Δh > 0` means pressure fell and the phone moved upward. Using the entry pressure as the local reference avoids requiring a true sea-level pressure and avoids turning an absolute pressure-altitude error into the floor estimate. This remains a standard-atmosphere approximation: local air temperature, cabin/HVAC pressure and weather can still perturb it.

## 2. Pressure filter

Input pressure `p_t` passes through. Samples are timestamped with Android's sensor-event monotonic timestamp rather than callback arrival time so OS delivery delay does not distort the vertical-speed regression:

```text
m_t = median(last 9 raw samples)
y_t = α*m_t + (1-α)*y_(t-1), α=0.18
```

The median suppresses impulsive outliers; the EWMA suppresses jitter.

### Noise diagnostic

The UI reports sample standard deviation over the most recent 3.5 seconds:

```text
s = sqrt( Σ(y_i - mean(y))² / (n-1) )
```

### Vertical velocity

Every recent filtered pressure sample is converted to altitude relative to the first sample in the window using the same pressure-ratio conversion. A least-squares line is fitted across the last four seconds:

```text
v_z = Σ((t_i-t̄)(h_i-h̄)) / Σ((t_i-t̄)²)
```

This is more stable than differentiating two noisy samples.

## 3. OSM candidate extraction

The Overpass query requests, inside a small bounding box:

```text
nwr["amenity"="parking"]["parking"~"multi-storey|underground"]
nwr["building"="parking"]
node["amenity"="parking_entrance"]
```

For a single OSM way, point-in-polygon may provide strong containment evidence. Multipolygon relations are not flattened into a fake polygon; each member geometry is retained separately for boundary proximity while relation containment is left unknown. This avoids false edges between independent outer/inner rings while still handling large relation-based garages better than centroid distance alone. `building=parking` records are accepted only when level/parking tags provide evidence that the building is actually multi-level or underground.

## 4. Exterior GNSS baseline

While not inside a garage and not very near a candidate structure, the app slowly learns EWMA baselines:

```text
b_new = 0.08*x + 0.92*b_old
```

for horizontal accuracy, average C/N0 and satellites used in fix.

The baseline is intentionally learned only when horizontal accuracy is reasonably good so an already-degraded state does not become the normal reference.

## 5. GNSS degradation

The GNSS degradation term combines:

```text
G = 0.45*accuracy_loss
  + 0.35*cn0_loss
  + 0.20*satellite_loss
```

Each component is clamped to `[0,1]` and normalized relative to the learned exterior baseline.

## 6. Map score

Approximate map evidence:

```text
inside polygon     1.00
<= 20 m            0.90
<= 40 m            0.75
<= 75 m            0.50
<= 120 m           0.25
otherwise          0
```

The score intentionally dominates GNSS degradation because urban canyons/tunnels can also degrade GNSS.

## 7. Garage-entry state machine

Mapped confidence:

```text
C = clamp(0.76*M + 0.14*G + 0.10*V, 0, 1)
```

`V` is a coarse speed-derived vehicle score.

Entry requires three consecutive qualifying **accepted location fixes**. For a mapped structure, proximity alone is not enough unless the fix is inside a trustworthy mapped boundary; a merely nearby fix also needs GNSS degradation or recent vertical-pressure evidence. This prevents a vehicle driving alongside a garage from entering the garage state on map distance + speed alone. Lower-quality provider callbacks that are rejected by the location selector are not counted again. Exit requires seven consecutive weak observations. These temporal requirements provide hysteresis and prevent a single noisy fix from toggling the state.

### Unmapped fallback

Only when map evidence is essentially absent:

```text
vertical = clamp((altitudeSpan20s - 1.8) / 3.2, 0, 1)
C_fallback = 0.58*G + 0.22*V + 0.20*vertical
```

Fallback entry additionally requires roughly `>2.4 m` recent altitude span. This remains ambiguous with tunnels/hills and is labeled accordingly. If map data arrives after a fallback session has already begun, a strong mapped candidate can promote the active session; the original pressure anchor is retained while the newly available valid-level topology is applied.

## 8. Entry pressure anchor

When entry is confirmed at time `t`, the app tries to anchor pressure near `t-3s` rather than exactly at confirmation time. The entry detector intentionally waits for sustained evidence; using the historical pressure sample reduces the vertical offset introduced during those few seconds of latency.

Mapped `amenity=parking_entrance` objects with numeric `level` are used to select an entry logical level. Otherwise the prototype initially prefers logical level `0`. If mapped topology explicitly excludes that level (including via `non_existent_levels`), the anchor is normalized to the nearest physically valid mapped level and its confidence is reduced rather than inventing an impossible floor. Because pressure cannot resolve an unknown absolute starting-floor offset, this entry anchor has its own confidence/source. The displayed floor confidence is conservatively multiplied by that anchor confidence. A manual re-anchor sets the absolute anchor confidence to 1.0, but the UI/service rejects manual anchors that contradict known mapped topology.

## 9. Valid-level topology

Preference order:

1. `min_level..max_level`, excluding `non_existent_levels`;
2. `building:levels:underground` below zero plus `building:levels` at/above zero;
3. if topology is absent, a broad relative range around the entry level, marked unknown.

The internal ordered array represents **physical floor order**. Example:

```text
OSM valid logical levels = [10, 11, 12, 14, 15]
physical ordinal index   = [ 0,  1,  2,  3,  4]
```

The physical offset from 12 to 14 is one floor height because level 13 is explicitly nonexistent.

## 10. Floor height

Default is 3.0 m, a conventional approximate floor height used when no structure-specific value exists.

If OSM contains both `height` and `building:levels`, the prototype calculates:

```text
H = height / buildingLevels
```

and accepts it only if `2.4 <= H <= 4.5 m`. Otherwise it keeps the fallback/user setting.

This is deliberately conservative because OSM `height` can describe roof geometry or otherwise fail to equal the sum of uniform floor-to-floor distances.

## 11. Floor observation likelihood

For physical level index `i`:

```text
μ_i = (i - i_entry) * H
r_i = Δh - μ_i
L_i = exp(-0.5 * (r_i / σ)^2)
```

Base `σ = 0.72 m` and grows with vertical speed:

```text
σ = 0.72 + min(0.55, |v_z|*0.25)
```

This widens the model while the vehicle is on a ramp.

## 12. Transition prior

Relative to the current physical index:

```text
same floor       1.35
adjacent         1.00
2 floors away    0.12
other            0.015
```

If the measured vertical velocity is clearly positive, candidates below the current level are multiplied by `0.2`; the reverse happens while clearly descending.

Posterior-like normalized weight:

```text
w_i = L_i * prior_i
P_i = w_i / Σw
```

These are model weights, not field-calibrated probabilities.

## 13. Hysteresis / transition acceptance

A normal transition is accepted only when:

- candidate is adjacent in physical order;
- candidate weight exceeds `max(0.54, 1.35*currentWeight)`;
- altitude is within `0.72 m` of the candidate floor plane.

A non-adjacent recovery jump is permitted only with `P_best > 0.80` and residual `<0.60 m`. This recovers from missed sensor updates without making ordinary multi-floor jumps easy.

The UI marks `transitioning=true` while vertical speed is notable or the relative altitude lies materially between floor planes.

## 14. Why not simply `round(Δh/H)`?

Naive rounding fails because:

- pressure can move enough to resemble a significant fraction of a floor;
- a car spends time on ramps between floor planes;
- floors can be skipped in numbering;
- floor heights can vary;
- transition direction/history is useful information;
- entry itself is uncertain.

Constrained recursive estimation converts those facts into priors and legal states.
