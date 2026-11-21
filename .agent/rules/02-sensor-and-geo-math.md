---
trigger: always_on
---

# BuckCastAndroid Sensor Fusion & Geo-Math Rules

## 1. True-North Hardware Compass Fusion (`:core:sensors`)
- **Required Sensors**: MUST fuse `Sensor.TYPE_ROTATION_VECTOR` via `SensorManager.getRotationMatrixFromVector(...)`, display-rotation-aware `SensorManager.remapCoordinateSystem(...)`, and `SensorManager.getOrientation(...)`.
- **Forbidden APIs**: NEVER use deprecated `Sensor.TYPE_ORIENTATION` or uncompensated raw magnetometer (`TYPE_MAGNETIC_FIELD`) heading math.
- **True-North Declination**: MUST convert magnetic azimuth to **True North** using `android.hardware.GeomagneticField(latitudeDeg, longitudeDeg, altitudeMeters, timeMillis).declination`:
  `trueHeadingDeg = normalizeDegrees(magneticAzimuthDeg + declinationDeg)`.

### Dual-Posture Tilt Handling
Prevent gimbal lock between flat map reading and upright tree-stand aiming:
1. **Flat / Angled Posture (`|pitch| <= 55°`)**: Project device `+Y` axis (top edge), remapped for current `Display.getRotation()`.
2. **Upright Tree-Stand Aiming Posture (`|pitch| > 55°`)**: Remap axes via `SensorManager.remapCoordinateSystem(rotationMatrix, AXIS_X, AXIS_Z, remappedMatrix)` (adjusted for display rotation) so azimuth projects along the rear camera `-Z` axis.

### Circular Smoothing & Badge Hysteresis
- **Circular EMA**: Smooth raw compass updates strictly via shortest-arc circular EMA so headings never whip across the `0°/360°` seam:
  `smoothed = normalizeDegrees(previous + alpha * signedDeltaDegrees(previous, raw))`.
- **Wind-vs-Facing Hysteresis**: Wind alignment badges (`WIND_IN_FACE`, `CROSSWIND`, `WIND_AT_BACK`) MUST apply **5° hysteresis** at sector boundaries to prevent rapid flickering when holding the phone near a threshold angle.

## 2. Strict `[0.0, 360.0)` Normalization & Circular Math
- Every bearing, azimuth, heading, wind-FROM direction, scent-TO vector, and slope aspect MUST be normalized into the half-open interval `[0.0, 360.0)` (mapping `-0.0` and `360.0` to `+0.0`).
- Reject `NaN` and `±Infinity` immediately with `require(value.isFinite())` — never silently propagate invalid IEEE-754 values into geo/wind state.
- Shortest signed angular deltas MUST lie in `(-180.0, 180.0]`.
- Vector sums (ambient wind + thermal slope drift) and angular averaging MUST decompose into East/North components (`E = magnitude * sin(rad)`, `N = magnitude * cos(rad)`), never naive arithmetic averaging of degree numbers.

## 3. Meteorological & Topographic Direction Conventions
- **`windFromDeg`**: Standard meteorological wind direction (compass bearing the wind is blowing **FROM**).
- **`scentToDeg`**: Downwind scent travel vector = `normalizeDegrees(windFromDeg + 180.0)`.
- **`slopeAspectDeg`**: Compass direction the terrain slope faces **DOWNHILL** (fall-line).
  - **Katabatic cooling** (night/early morning): drains **downhill** toward `slopeAspectDeg`.
  - **Anabatic warming** (midday solar heating): rises **upslope** toward `normalizeDegrees(slopeAspectDeg + 180.0)`.

## 4. Coordinate & Unit Safety
- Coordinates use **WGS84** decimal degrees (`latitude` in `[-90.0, 90.0]`, `longitude` in `[-180.0, 180.0]`).
- `:core:domain` properties use explicit US imperial suffixes (`Mph`, `DegF`, `InHg`, `InchesPerHr`, `Yards`, `Feet`); angles always end in `Deg` (`Degrees`).
