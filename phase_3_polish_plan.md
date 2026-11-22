# DropTine — Phase 3 Polish & Field-Test Remediation Plan

## Rules & Build Environment
- Follow `.agent/rules/01-android-architecture.md` and `.agent/rules/02-sensor-and-geo-math.md`.
- Keep `:core:domain` 100% pure Kotlin JVM (zero `android.*` or `androidx.*` imports).
- Use `$env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"` when running `.\gradlew.bat`.
- Check off each item (`- [x]`) only after its code compiles and unit tests pass.
- **Scope Boundary**: Execute implementation and unit/build verification ONLY. Do **not** run a post-build auditor pass in this session; stop once Section 8 is green.

---

## 1. Fix Offline Map Downloads via Virtual HTTPS Style Interceptor (`:app`, `:feature:map`)
- [x] **Implement Synthetic HTTPS Style Interceptor (`BuckCastApplication.kt`, `MapTileConfig.kt`)**:
  - Root cause: MapLibre Native's `OfflineDownload` uses `onlineFileSource` (`http`/`https` only) and stalls permanently at `0/1 res` (`Partial`) when passed a local `file://` style URI.
  - Register a synthetic style interceptor inside the custom `OkHttpClient` passed to `HttpRequestUtil.setOkHttpClient(...)` in `BuckCastApplication.kt`.
  - Intercept requests to `https://styles.droptine.app/{styleKey}.json` (e.g., `topo.json`, `topo_hillshade.json`, `satellite.json`, `satellite_hillshade.json`, `hybrid.json`, `hybrid_hillshade.json`, plus any offline-safe variant) and return HTTP `200 OK` (`application/json`, `Cache-Control: public, max-age=31536000`) with the exact generated style JSON from `MapTileConfig`.
  - Load BOTH `MapView` (`Style.Builder().fromUri("https://styles.droptine.app/...")`) AND `OfflineTilePyramidRegionDefinition("https://styles.droptine.app/...", ...)` from this virtual HTTPS URL so `OfflineManager` parses all sources and downloads tiles to `Complete`.
- [x] **Harden `OfflinePackSheet` & `OfflineMapPackManager`**:
  - Add a **Cancel** action in `OfflinePackSheet` during active downloads.
  - Ensure `isDownloading` resets cleanly on cancel, error, or timeout so `Download Current Area` never gets permanently disabled.
- [x] **Add Interceptor Unit/Robolectric Tests**:
  - Verify the OkHttp interceptor returns valid `200 OK` MapLibre style JSON for every `(MapBaseLayer x showHillshade)` combination (including offline-safe style variants).

---

## 2. Upgrade Satellite Resolution & Add Topographic Detail to Hybrid (`:feature:map`)
- [x] **Higher-Resolution Satellite (`MapTileConfig.kt`)**:
  - Switch the primary Satellite source to **Esri World Imagery Clarity**:
    `https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` (`maxzoom: 19`).
  - Configure `"tileSize": 128` for crisp `@2x` HiDPI rendering on high-density Android screens while keeping `OfflineTileEstimator` accurate and capping offline region downloads at safe zoom bounds `10..16`.
- [x] **Topo-Rich Hybrid Stack (`MapTileConfig.kt`)**:
  - Build `Hybrid` as a layered hunting stack (bottom -> top):
    1. Esri World Imagery Clarity (`tileSize: 128`, `maxzoom: 19`).
    2. Built-in Terrarium `raster-dem` `hillshade` (`exaggeration = 0.45` default on Hybrid, boosted to `0.75` when Hillshade chip is ON).
    3. **USGS Hydrography / Streams**:
       `https://basemap.nationalmap.gov/arcgis/rest/services/USGSHydroCached/MapServer/tile/{z}/{y}/{x}` (`tileSize: 256`, `maxzoom: 16`, `raster-opacity: 0.72`).
    4. **USGS 3DEP Elevation Contours**:
       `https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&dpi=96&format=png32&transparent=true&f=image` (`tileSize: 256`, `minzoom: 11`, `maxzoom: 16`, `raster-opacity: 0.80`).
       - If `{bbox-epsg-3857}` is incompatible with MapLibre `OfflineManager`, exclude that dynamic contour layer from the offline-only Hybrid style variant so offline Hybrid packs still complete 100%.
    5. Esri `World_Transportation` + `World_Boundaries_and_Places` labels on top.

---

## 3. Fix Treestand Aim-Down Pitch Tracking & Live Pitch HUD (`:core:sensors`, `:feature:map`)
- [x] **Diagnose & Update Dual-Posture Pitch Detection (`RotationVectorCompass.kt`, `CompassFusionMath.kt`)**:
  - Inspect why the Map Compass HUD stayed locked on `Flat` during hardware testing.
  - Update posture switching to support **both level aiming AND aiming steep down from a treestand** using world-from-device rotation matrix components `R[7]` (device `+Y` Up) and `R[8]` (device `+Z` screen normal Up):
    - Enter `UPRIGHT_AIMING` when `R[8] < cos(45 deg)` (`0.707`) AND `R[7] > 0.20` (covers aiming `45 deg` down out of a treestand through `0 deg` level to `+45 deg` uphill).
    - Return to `FLAT_MAP` when `R[8] > cos(37 deg)` (`0.799`) to maintain a clean hysteresis band.
  - Compute live camera elevation/depression angle:
    `aimPitchDeg = Math.toDegrees(asin((-R[8]).coerceIn(-1.0, 1.0)))` (`-30 deg` aiming down from stand, `0 deg` level, `-90 deg` flat) and expose `pitchDeg` on `CompassReading`.
- [x] **Display Live Pitch in Map Compass HUD (`MapScreen.kt`)**:
  - Render live pitch in the bottom-left Compass HUD pill (e.g., `Flat · 12°` vs `Aim · -24°`) so pitch tracking is immediately verifiable in the field.
- [x] **Add Quaternion Aim-Down Unit Tests (`CompassAndBarometerMathTest.kt`)**:
  - Verify `UPRIGHT_AIMING` activates and holds `< 0.5 deg` azimuth continuity at `-45 deg` down, `-30 deg` down, `0 deg` level, and `+20 deg` uphill across headings `0`, `90`, `118`, `215`, and `330` degrees.

---

## 4. Add Map Orientation Toggle — True North vs. Live Azimuth (`:feature:map`)
- [x] **State & Bearing Smoothing (`MapViewModel.kt`)**:
  - Add `enum class MapOrientationMode { NORTH_UP, HEADING_UP }` (default `NORTH_UP`, available on Scout Free & Alpha) to `MapUiState`.
  - Expose `toggleOrientationMode()` and `onUserManualRotate()` (releases `HEADING_UP` back to `NORTH_UP` without snapping bearing until the user taps the toggle).
  - Add a pure helper `computeNextMapBearing(currentDeg: Double, targetDeg: Double, alpha: Double = 0.25, deadbandDeg: Double = 0.5): Double?` using shortest-arc signed angular delta across the `359 <-> 0` North seam, returning `null` when `abs(delta) < deadbandDeg`.
- [x] **MapLibre Camera Wiring & Control FAB (`MapScreen.kt`)**:
  - Place a high-contrast **Orientation Toggle FAB** on the map's right control rail directly above the Recenter GPS FAB:
    - `NORTH_UP`: Neutral `surfaceContainerHigh` container (`>= 0.94f` alpha + `1.dp` border) showing a North arrow pointing straight up + `"N"` label.
    - `HEADING_UP`: Highlighted `primaryContainer` container showing `"AZM"` and a live-rotating North indicator (`graphicsLayer { rotationZ = -trueHeadingDeg.toFloat() }`).
  - On `HEADING_UP -> NORTH_UP`, smoothly snap with `map.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)`.
  - While `orientationMode == HEADING_UP`, drive `map.moveCamera(CameraUpdateFactory.bearingTo(nextBearing))` from live `CompassReading.trueHeadingDeg` (throttled cleanly at ~40-60 ms, never stacking `animateCamera` calls).
  - Register `map.addOnRotateListener` (`onRotateBegin`) to call `viewModel.onUserManualRotate()`.
  - Keep all GeoJSON overlays (FOV wedge, 300-yd scent cone, thermal arrow, range rings, stand pins) strictly True-North geographic; do NOT subtract camera bearing from GeoJSON features.
- [x] **Add Orientation Unit Tests (`MapViewModelTest.kt`)**:
  - Test `NORTH_UP <-> HEADING_UP` toggling, `onUserManualRotate()` release, shortest-arc North-seam math (`359 -> 2`, `1 -> 358`), and `< 0.5 deg` deadband suppression.

---

## 5. Add Interactive Compass Recalibration Sheet (`:core:sensors`, `:core:data`, `:feature:map`, `:feature:settings`)
- [x] **Persist Compass Trim Offset via Room v2 -> v3 Migration (`:core:domain`, `:core:data`, `:core:sensors`)**:
  - Add `compassOffsetDeg: Double = 0.0` (clamped `-15.0..+15.0`) to `AppSettingsEntity`.
  - Implement Room `MIGRATION_2_3`, export `3.json`, add a migration test in `BuckCastMigrationTest.kt`, and apply `compassOffsetDeg` inside `RotationVectorCompass` / heading output.
- [x] **Build `CompassCalibrationSheet` (`ModalBottomSheet`)**:
  - Open by **tapping the Compass HUD pill on the Map** or from **Settings -> Calibrate Compass**.
  - Display live True Heading, Magnetic Heading, Declination, live Pitch/Tilt, Posture (`Flat` / `Aim`), Sensor Type, and Accuracy status (`HIGH` / `MEDIUM` / `LOW` / `UNRELIABLE`).
  - Include guided Figure-8 calibration instructions with a visual status indicator turning green at `HIGH` accuracy.
  - Add a **"Reset Sensor Fusion"** button calling `recalibrate()` on `RotationVectorCompass` (unregisters/re-registers sensor listener and clears EMA smoothing state).
  - Add a Manual Trim slider (`-15 deg` to `+15 deg`) with a 1-tap `Reset 0°` button.

---

## 6. Add Full Stand Editing & Pin Repositioning (`:feature:stands`, `:feature:map`)
- [x] **Wire Edit Flow (`StandDetailSheet.kt`, `StandEditorSheet.kt`, `StandsViewModel.kt`)**:
  - Add an **"Edit Stand"** button in `StandDetailSheet` opening `StandEditorSheet` pre-populated with all existing stand fields (`id`, name, coordinates, stand type, height, terrain position, 8-point wind rose, slope aspect/grade, notes).
  - Wire `StandsViewModel.updateStand(...)` to update the existing `StandEntity` in Room **without deleting or resetting its `SitLogEntity` history, `StandFreshness`, or `StandRouteEntity` routes**.
- [x] **Add Pin Repositioning (`StandEditorSheet.kt`, `MapScreen.kt`)**:
  - Add a **"Reposition on Map"** button inside `StandEditorSheet` letting the user tap a new map location to update the stand's `LatLng` cleanly.
- [x] **Add Stand Editing Unit Tests (`:feature:stands`)**:
  - Verify editing a stand preserves sit logs and entry/exit routes while recalculating wind match immediately.

---

## 7. Home & Map UI Polish (`:feature:forecast`, `:feature:map`)
- [x] **Fix Wrapped Rating Pills (`ForecastScreen.kt`)**:
  - Prevent `GOOD` / `GREAT` / `OKAY` / `SLOW` badges on Home's Hero Today Card (`AM GOOD 60` / `PM GOOD 68`) from splitting mid-word by enforcing `maxLines = 1, softWrap = false` and `widthIn(min = 68.dp)`.
- [x] **Auto-Dismiss Stacked Scout Hint Banner (`MapScreen.kt`)**:
  - Dismiss the `"Scout Mode active — tap anywhere on map..."` hint banner as soon as the user taps a target and the `Scout Scent Projection` card appears.

---

## 8. Build & Unit Test Gate
- [x] Run full Gradle unit test suite and assemble debug APK:

    $env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
    .\gradlew.bat :core:domain:test :core:data:testDebugUnitTest :core:sensors:testDebugUnitTest :feature:forecast:testDebugUnitTest :feature:map:testDebugUnitTest :feature:stands:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon

- [x] Verify 0 test failures, clean `app-debug.apk` output, and summarize modified files and test counts.