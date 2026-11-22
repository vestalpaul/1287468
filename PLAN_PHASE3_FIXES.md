# DropTine — Phase 3 Field-Test Bug Fixes & Visual Polish Plan

## Rules & Build Environment
- Follow `.agent/rules/01-android-architecture.md` and `.agent/rules/02-sensor-and-geo-math.md`.
- Keep `:core:domain` 100% pure Kotlin JVM.
- Use `$env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"` for `.\gradlew.bat`.
- Check off each item (`- [x]`) only after compiling and passing unit tests.

---

## 1. MapLibre Base Styles, Resolution, Hillshade & Offline Downloads (`:feature:map`)
- [x] **Fix Style JSON Persistence for MapLibre & OfflineManager**:
  - Write generated MapLibre style JSONs (`topo.json`, `satellite.json`, `hybrid.json`) into `context.filesDir/map_styles/` and load BOTH `map.setStyle(Style.Builder().fromUri(Uri.fromFile(file).toString()))` AND `OfflineTilePyramidRegionDefinition(Uri.fromFile(file).toString(), ...)` from the local `file://` URI so `OfflineManager` never stalls at `0/1 res · 0.0 MB · Partial`.
  - Attach full `OfflineRegionObserver` callbacks showing live downloaded tile count / MB progress and surfacing real `onError` messages if any tile server fails.
  - Recalculate `OfflineTileEstimator` accurately across zooms `10..16` for the visible `LatLngBounds`.
- [x] **Fix Satellite Resolution & Hybrid Map Loading**:
  - Explicitly set `"tileSize": 256` on all raster tile sources so MapLibre does not downsample tiles by 4x.
  - **Topo**: USGS National Map Topo (`https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}`, `tileSize: 256`, `maxzoom: 16`).
  - **Satellite**: Esri World Imagery (`https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`, `tileSize: 256`, `maxzoom: 19`) for crisp house/treeline resolution (with MapTiler config hook preserved for commercial release).
  - **Hybrid**: Dual-layer style combining Esri World Imagery (`maxzoom: 19`, `tileSize: 256`) + transparent Esri Transportation & Boundaries/Places label layers (`https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}` and `.../Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}`, `tileSize: 256`, `maxzoom: 19`)—with USGS `USGSImageryTopo` as fallback—so Hybrid loads reliably with crisp satellite + road/town labels instead of a black screen.
- [x] **Fix Hillshade (Real 3D Terrain Relief, Not Grey Wash)**:
  - Replace the translucent grey raster overlay with MapLibre's native **`hillshade` layer** backed by AWS Terrarium RGB DEM tiles (`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`, `"type": "raster-dem"`, `"encoding": "terrarium"`, `"tileSize": 256`, `"maxzoom": 15`).
  - Configure `hillshade-exaggeration = 0.7`, `hillshade-illumination-direction = 315`, dark shadow color (`#0A1014`), warm highlight (`#FFF8E7`), and `hillshade-accent-color` so ridges, benches, and creek bottoms pop cleanly without turning the base map grey.

---

## 2. Tactical Overlays: Range Rings, Stand Pins, Long-Press Tagging & Live FOV Cone (`:feature:map`, `:feature:stands`)
- [x] **Fix Range Rings (`50 / 100 / 200 / 300 yd`)**:
  - Ensure `range-rings-source`, `range-rings-line-layer` (high-contrast dashed rings visible on both light Topo and dark Satellite), and `range-rings-label-layer` (`"50 yd"`, `"100 yd"`, `"200 yd"`, `"300 yd"`) are registered above all base raster/hillshade layers inside `onStyleLoaded` AND reactively updated via `LaunchedEffect` whenever `showRangeRings`, GPS fix, or active Scout pin changes.
- [x] **Fix Stand Pin Rendering, Long-Press Tagging & Stand Jump**:
  - Render saved stands as top-most MapLibre layers (`stands-circle-layer` with color-coded `OPTIMAL`/`GOOD`/`CAUTION`/`UNSAFE` fill + thick white/black halo stroke, plus `stands-label-layer` displaying stand `name` offset above the pin) placed **above** the GPS puck layer so stands tagged at your current location are immediately visible.
  - Wire map **long-press** (`addOnMapLongClickListener`) directly to open `StandEditorSheet` pre-populated with the exact long-pressed `LatLng` (distinct from the `+ Stand` FAB, which uses current GPS).
  - Show a confirmation snackbar (`"Stand '<name>' saved"`) after saving, ensure tapping a stand pin opens `StandDetailSheet` with generous hit-box padding (`24dp`), and make tapping any stand row in Home's `My Stands Wind Match` jump directly to that stand on the Map (or open its detail sheet).
- [x] **Fix Live FOV Wedge Rotation on the Map**:
  - Verify `MapScreen.kt` pushes live `CompassReading.trueHeadingDeg` updates into the MapLibre `fov-source` GeoJSON smoothly (throttled cleanly to ~15–20 Hz, never frozen on initial load) so the blue FOV cone visibly rotates in real time as you turn.
- [x] **Fix Stuck Scout Mode Banner**:
  - Clear the `"Scout Mode active"` prompt immediately when Scout Mode is toggled OFF, when `OK` is tapped, or when navigating away from the `Map` tab.

---

## 3. Fix `UPRIGHT_AIMING` 180° Inversion (`:core:sensors`)
- [x] **Diagnose & Fix 180° Heading Inversion in `CompassFusionMath.kt` / `RotationVectorCompass.kt`**:
  - Field test proof (`video_1.mp4` 01:28–01:36): Holding the phone facing `~118° ESE` flat and tilting the top edge skyward (`UPRIGHT_AIMING`) flipped the reported heading by ~180° to `~288°–296° WNW`, then snapped back to `~105° ESE` when lowered.
  - Inspect how `R` from `SensorManager.getRotationMatrixFromVector` + display-rotation remapping maps device `-Z` (rear camera) into `(East, North, Up)`. Verify both mathematically and with a JUnit test built directly from real Android rotation-vector quaternions (`q = q_yaw * q_pitch`) that facing `0°`, `90°`, `118°`, `215°`, and `330°` produces the **exact same heading (`±0.5°`)** at `0°` flat pitch, `50°` pitch, `65°` skyward pitch (`UPRIGHT_AIMING`), and `85°` skyward pitch—with zero 90° spike or 180° flip during transition.

---

## 4. High-Craft Material 3 Theme Overhaul & UI Polish (`:app`, `:feature:forecast`, `:feature:map`, `:feature:settings`)
- [x] **Make All 5 Themes Unmistakable, High-Contrast & Tactile (`DropTineTheme.kt`)**:
  - Give each theme a genuinely distinct palette across `background`, `surfaceContainerLowest..Highest`, `primary`, `secondary`, `tertiary`, `outlineVariant`, and `onSurface`—plus subtle card border strokes (`1.dp`) and accented hero headers so switching themes transforms the entire app immediately:
    1. **Timberline Dark**: Deep spruce-black `#08110C` canvas, dark evergreen `#111E16` cards, crisp sage `#7CC97E` primary, warm brass `#E5B567` secondary/accents, subtle `#23382B` borders.
    2. **Topo Daylight**: Warm parchment `#F4F1E8` canvas, crisp `#FFFFFF` elevated cards, deep forest `#1B4332` primary, burnt terracotta `#C85A32` accents, strong `#2B302C` ink text.
    3. **Stealth Crimson**: True `#000000` OLED black canvas, `#0C0202` cards, deep crimson `#D32F2F` primary, soft red `#FF6B6B` / `#E57373` text & icons (NO harsh white text anywhere so night vision is genuinely preserved), `#3A0B0B` card borders.
    4. **Blaze Slate**: Cool tactical gunmetal `#0D131A` canvas, `#16202B` steel-blue cards, unmistakable hunter blaze orange `#FF5722` primary, ice-blue `#64B5F6` secondary, `#2A3B4C` borders.
    5. **Material You**: True Android 12+ `dynamicDarkColorScheme` / `dynamicLightColorScheme` respecting system dark/light mode with full tonal surface hierarchy.
- [x] **Fix Map Control Contrast Over Bright Topo Maps (`MapScreen.kt`)**:
  - Give the top `Topo | Satellite | Hybrid` segmented control and the `Hillshade / Rings / Scout / Offline` chips solid high-contrast themed pill containers (`surfaceContainerHigh.copy(alpha = 0.94f)` + `1.dp` border + shadow elevation) so text and icons are crisp and readable over bright white USGS Topo tiles in EVERY theme.
- [x] **Fix Home Dashboard Text & Layout Glitches (`ForecastScreen.kt`)**:
  - Prevent `Timeline` from wrapping onto two lines (`Timeli / ne`) by giving the `Cards | Timeline` segmented toggle `maxLines = 1, softWrap = false` and slightly tighter horizontal padding (or placing the toggle cleanly on its own compact row below the section title).
  - Separate `Legal Light: 6:18 AM – 5:16 PM` and `Moon: ↑12:34 PM · ↓3:21 AM` onto cleanly spaced rows/columns so `PMMoon:` never concatenates.
- [x] **Fix Settings `Debug Entitlement Switcher` (`SettingsScreen.kt`, `SettingsViewModel.kt`, `RevenueCatAndEntitlementRepo.kt`)**:
  - Verify tapping `AUTO`, `FORCE_ALPHA`, `FORCE_SCOUT`, `EXPIRE_36H_PREVIEW`, and `RESET_36H_PREVIEW` immediately updates persisted state, refreshes `EntitlementState` across all tabs, and visibly highlights the active chip (fixing the bug where `FORCE_ALPHA` stayed stuck on tap).

---

## 5. Verification Gate
- [x] Update/add unit tests covering:
  - Quaternion-driven `0° → 55° → 85°` skyward tilt heading continuity (no 180° flip).
  - Local `file://` style URI generation for `Topo`, `Satellite` (`tileSize = 256`, `maxzoom = 19`), `Hybrid` (multi-layer imagery + labels), and Terrarium `raster-dem` `hillshade` layer.
  - `Rings` GeoJSON toggle visibility & stand pin GeoJSON layer output.
  - `SettingsViewModel` debug entitlement switcher transitions (`FORCE_ALPHA -> FORCE_SCOUT -> EXPIRE_36H_PREVIEW -> AUTO`).
- [x] Run `.\gradlew.bat :core:domain:test :core:data:testDebugUnitTest :core:sensors:testDebugUnitTest :feature:forecast:testDebugUnitTest :feature:map:testDebugUnitTest :feature:stands:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug --no-daemon` and verify 0 failures and clean `app-debug.apk`.