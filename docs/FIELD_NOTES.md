# Field notes

These notes record observed behavior without presenting it as validated performance.

## 2026-09-03 — first walking test

### Setup

- Android phone with a barometer.
- Multi-level parking garage.
- Entered on the street-access deck on foot.
- No special infrastructure or manual pressure calibration.
- Garage identity/location intentionally omitted from this public note.

### Observed

1. The app detected entry into the garage.
2. The first upward floor transition was detected.
3. A second upward floor transition was detected.
4. The descent back to the original entry deck was tracked through both transitions.
5. A subsequent move below the entry deck was not detected correctly.

### Semantic-level mismatch

The garage signs identify the street-entry deck as **Level 3**, with additional levels below it and upper decks above it. The prototype did not have reliable public map metadata establishing that semantic entrance label.

This demonstrates an important distinction:

- **relative physical floor movement** can be measured from pressure change;
- **the human-facing floor label** depends on structure metadata or another trustworthy anchor.

A correct relative estimate can therefore still display the wrong absolute garage label when the entrance level is unknown.

### Interpretation

This was a promising feasibility check, not an accuracy benchmark. It involved one device, one structure and pedestrian movement. The next useful evidence is repeated vehicle testing across different garage geometries and pressure environments.
