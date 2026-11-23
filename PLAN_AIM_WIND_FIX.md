# DropTine — Unified Aim-Down Pitch & Milkweed Wind Override Plan

## Rules & Build Environment
- Follow `.agent/rules/01-android-architecture.md` and `.agent/rules/02-sensor-and-geo-math.md`.
- Keep `:core:domain` 100% pure Kotlin JVM (zero `android.*` or `androidx.*` imports).
- Use `$env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"` when running `.\gradlew.bat`.
- Check off each item (`- [x]`) only after its code compiles and unit tests pass.
- **Scope Boundary**: Execute implementation and unit/build verification ONLY. Do **not** run a post-build auditor pass in this session; stop once Section 3 is green so `@droptine-auditor` can verify in a fresh thread.

---

## 1. Unified 3-State Posture & Treestand Aim-Down Pitch (`:core:domain`, `:core:sensors`, `:feature:map`, `:app`)

### Hardware Field-Test Evidence
- Held flat in palm: `Flat +3°`
- Held upright like taking a photo: `Aim -2°`
- Held aiming down from a treestand: `Flat +50°` (failed to enter `Aim` and inverted the sign to positive `+50°` instead of negative `-50°`).

### Tasks
- [x] **Expand `CompassPosture` to Support Both Stand-Aiming Grips (`:core:domain`, `:core:sensors`)**:
  - Update `CompassPosture` enum to include:
    - `FLAT_MAP` — held roughly level in palm (`+/-25 deg` of horizontal).
    - `UPRIGHT_AIMING` — back-camera aiming from steep treestand depression (`-60 deg`) through level (`0 deg`) up to uphill (`+60 deg`).
    - `AIM_DOWN` — nose-down / top-edge aiming from a palm grip dipped `-25 deg` to `-80 deg` below horizontal.
  - Ensure all `when (posture)` expressions across `:core:sensors`, `:feature:map`, and `:app` handle all 3 states exhaustively.
- [x] **Fix Matrix Thresholds, Azimuth Axis & Depression Pitch Sign (`CompassFusionMath.kt`, `RotationVectorCompass.kt`)**:
  - Using the world-from-device 3x3 rotation matrix `R` (row-major, post display-rotation remap where `R[7]` is the Up component of device `+Y` top edge and `R[8]` is the Up component of device `+Z` screen normal):
    1. **State Transitions with Hysteresis**:
       - From `FLAT_MAP`:
         - Enter `UPRIGHT_AIMING` when `R[7] > 0.25` AND `R[8] < cos(30 deg)` (`0.866`) — covers back-camera aiming as steep as `-60 deg` down from a treestand through `0 deg` level to `+60 deg` uphill.
         - Enter `AIM_DOWN` when `R[7] < -sin(25 deg)` (`-0.423`) — covers dipping the phone's top edge downward from a flat palm grip.
       - From `UPRIGHT_AIMING`:
         - Return to `FLAT_MAP` when `R[8] > cos(22 deg)` (`0.927`) OR `R[7] < 0.15` (if rocked straight into `AIM_DOWN`, allow transitioning to `AIM_DOWN` once `R[7] < -0.423`).
       - From `AIM_DOWN`:
         - Return to `FLAT_MAP` when `R[7] > -sin(18 deg)` (`-0.309`).
    2. **True Azimuth Projection (Zero `180 deg` Flips)**:
       - `FLAT_MAP`: project device `+Y` top edge -> `atan2(R[1], R[4])`.
       - `UPRIGHT_AIMING`: project device `-Z` rear camera -> `atan2(-R[2], -R[5])`.
       - `AIM_DOWN`: project device `+Y` top edge -> `atan2(R[1], R[4])` (because `-Z` points backward toward the tree trunk in a nose-down palm grip; add a gimbal guard holding previous smoothed azimuth if `hypot(R[1], R[4]) < 0.08`).
    3. **Signed Pitch Angle (`pitchDeg`)**:
       - Convention: **negative** when aiming downward (`-50 deg`), **`0 deg`** when level, **positive** when tilted upward (`+20 deg`).
       - `FLAT_MAP`: `Math.toDegrees(asin(R[7].coerceIn(-1.0, 1.0)))` (`~0 deg` in palm, smoothly transitions to `-25 deg` entering `AIM_DOWN`).
       - `AIM_DOWN`: `Math.toDegrees(asin(R[7].coerceIn(-1.0, 1.0)))` (`-25 deg` to `-80 deg` nose-down).
       - `UPRIGHT_AIMING`: `Math.toDegrees(asin((-R[8]).coerceIn(-1.0, 1.0)))` (`-60 deg` steep back-camera stand aim, `0 deg` level photo aim, `+30 deg` uphill).
       - Verify `SensorManager.getOrientation` is NOT inverting the sign back to `+50 deg`.
- [x] **Update Map Compass HUD & Calibration Sheet Display (`MapScreen.kt`, `CompassCalibrationSheet.kt`)**:
  - Format the Map HUD posture chip cleanly:
    - `FLAT_MAP` -> `Flat · +3°` (or `Flat · 0°`)
    - `UPRIGHT_AIMING` -> `Aim · -2°` / `Aim · -50°`
    - `AIM_DOWN` -> `Aim · -50°` (highlighted in active aim accent color just like `UPRIGHT_AIMING`)
  - In `CompassCalibrationSheet`, display explicit posture names (`Flat Map`, `Upright Aim`, `Stand Aim-Down`) alongside signed live `Pitch` so both treestand grips are immediately distinguishable.
- [x] **Add Independent Quaternion Tests (`CompassAndBarometerMathTest.kt`)**:
  - Construct true 3D unit quaternions (never hand-crafted mock matrices) across headings `0`, `90`, `118`, `215`, and `330` degrees verifying:
    - Flat palm (`0 deg` to `15 deg` casual tilt) stays in `FLAT_MAP` with `|pitchDeg| <= 16 deg`.
    - Back-camera steep stand aim at `-55 deg`, `-50 deg`, `-30 deg`, `0 deg` level, and `+20 deg` uphill enters/holds `UPRIGHT_AIMING`, reports negative depression pitch within `+/-1.0 deg`, and holds azimuth within `< 0.5 deg`.
    - Nose-down top-edge stand aim at `-30 deg`, `-50 deg`, and `-70 deg` enters/holds `AIM_DOWN`, reports negative depression pitch (`-30 deg`, `-50 deg`, `-70 deg` within `+/-1.0 deg`), and holds azimuth within `< 0.5 deg` (zero `180 deg` flip).
    - Smooth sweep from flat (`0 deg`) -> nose-down (`-50 deg`) -> flat (`0 deg`) -> back-camera (`-50 deg` -> `0 deg`) produces zero heading spikes.

---

## 2. Milkweed / Live Wind Override (`:core:domain`, `:feature:map`)

### Purpose
Open-Meteo forecasts model unobstructed 10-meter (33 ft) wind. Ridges, creek hollows, leeward rotors, and evening thermal drops frequently steer real woods-floor wind `30-180 deg` off the regional forecast. Hunters need a 1-tap way to snap the tactical scent cone to their real milkweed float while keeping the forecast visible as reference.

### Tasks
- [x] **Pure-Kotlin Wind Override Model & Helpers (`:core:domain`)**:
  - Add `LiveWindOverride` model (`windFromDeg: Double`, `windSpeedMph: Double`, `setAtEpochMs: Long`, `expiresAtEpochMs: Long` defaulting to 4 hours after creation).
  - Add pure helper `windFromMilkweedFloatDeg(downwindFloatHeadingDeg: Double): Double` returning `((downwindFloatHeadingDeg + 180.0) % 360.0 + 360.0) % 360.0` (converting the direction milkweed floats *away* from the hunter into standard meteorological wind-FROM degrees).
  - Add `LiveWindOverride.isExpired(nowEpochMs: Long): Boolean`.
- [x] **Wire Override State Into `MapViewModel.kt`**:
  - Add `liveWindOverride: LiveWindOverride?` and `forecastWindFromDeg` / `forecastWindSpeedMph` to `MapUiState`.
  - Compute `effectiveWindFromDeg` and `effectiveWindSpeedMph` using `liveWindOverride` when present and non-expired, falling back automatically to live/cached Open-Meteo forecast wind otherwise.
  - Ensure `effectiveWindFromDeg` and `effectiveWindSpeedMph` drive ALL tactical map calculations reactively:
    1. 300-yd downwind scent cone & wind vector arrow (`TacticalGeoJsonBuilder`).
    2. Compass HUD wind alignment verdict (`IN YOUR FACE` / `CROSSWIND` / `AT YOUR BACK`).
    3. Scout Mode scent projection card & GeoJSON cone.
    4. Live stand pin color verdicts (`OPTIMAL` / `GOOD` / `CAUTION` / `UNSAFE`) on the map.
  - Expose ViewModel actions:
    - `setMilkweedFloatOverride(downwindHeadingDeg: Double, speedMph: Double? = null)` (snaps scent cone straight along the user's current compass heading where milkweed floated).
    - `setManualWindOverride(windFromDeg: Double, speedMph: Double)`
    - `clearWindOverride()` (1-tap reset back to Forecast).
- [x] **Build `MilkweedWindSheet` & Map HUD Entry Point (`MapScreen.kt`)**:
  - Add a compact, high-contrast **`Wind` / `Milkweed`** toggle chip on the Map HUD (or make the wind row inside the bottom-left Compass HUD card tappable with a subtle edit/wind icon, plus a highlighted `"MILKWEED"` pill when active) readable over bright USGS Topo tiles (`surfaceContainerHigh.copy(alpha = 0.94f)` + `1.dp` border).
  - Inside `MilkweedWindSheet` (`ModalBottomSheet`):
    - **Primary 1-Tap Action**: **"Snap to Milkweed Float"** — instructs *"Point phone where milkweed floats, then tap"* showing live compass heading (e.g., `Float: 135° SE -> Wind FROM NW`) and applying `setMilkweedFloatOverride(currentTrueHeadingDeg)` in one tap.
    - **Manual Override Controls**: 8-point quick wind-FROM chips (`N`, `NE`, `E`, `SE`, `S`, `SW`, `W`, `NW`) + fine `0-355 deg` slider + quick speed chips (`Calm 2`, `Light 5`, `Breeze 10`, `Steady 15`, or `Use Forecast Speed`).
    - **Reference & Reset**: Always display `"Forecast Reference: <dir> @ <mph> mph"` and a prominent **"Reset to Forecast"** button whenever an override is active.
  - When an override is active, show a clear `"MILKWEED"` badge on the bottom-left Compass HUD pill so the user never mistakes a manual override for Open-Meteo data.
- [x] **Add Wind Override Unit Tests (`:core:domain`, `:feature:map`)**:
  - Test `windFromMilkweedFloatDeg` across `0 deg -> 180 deg`, `135 deg -> 315 deg`, `270 deg -> 90 deg`, `359.5 deg -> 179.5 deg`.
  - Test `MapViewModel` applying `setMilkweedFloatOverride` and `setManualWindOverride` updates effective wind, stand pin verdicts, wind alignment badge, and Scout projection—and `clearWindOverride()` + 4-hour expiry cleanly restore forecast wind.

---

## 3. Build & Unit Test Gate
- [x] Run full Gradle test suite and assemble debug APK:

    $env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
    .\gradlew.bat :core:domain:test :core:data:testDebugUnitTest :core:sensors:testDebugUnitTest :feature:forecast:testDebugUnitTest :feature:map:testDebugUnitTest :feature:stands:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon

- [x] Confirm 0 failures, 0 errors, clean `app-debug.apk` build, and summarize modified files and test counts.
