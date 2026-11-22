---
name: droptine-auditor
description: Cold-eyes adversarial post-build audit for DropTine Android milestones. Use in a fresh session after the orchestrator finishes a milestone and builds app-debug.apk.
---

# DropTine Milestone Auditor

Run an adversarial verification pass against the current uncommitted `git diff` (or latest milestone commit) and `app-debug.apk`. Never assume passing unit tests prove runtime correctness on Android hardware.

## Audit Workflow
1. Inspect `git status` and `git diff --stat` to identify every new or modified file across modules.
2. Execute all 6 Mandatory Checks below, reading the actual Kotlin/Gradle/XML/JSON code rather than relying on summaries.
3. Patch every real defect discovered (do not defer broken wiring, failing HTTP endpoints, or tautological tests).
4. Re-run the full Gradle verification suite and `:app:assembleDebug --no-daemon` using:
   `$env:JAVA_HOME = "C:\Users\vesta\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"`
5. Present a concise Pass/Fail Audit Report listing every check, defects caught & patched, test counts, APK byte size, and ADB smoke-test result.

## Mandatory Checks

### Check 1 — Cold-Start & SDK Initialization Order
- Verify `BuckCastApplication.onCreate()` initializes SDK singletons in strict dependency order:
  `MapLibre.getInstance(this)` -> `HttpRequestUtil.setOkHttpClient(...)` -> RevenueCat `BillingGateway.configureIfReady()` -> WorkManager sync scheduling.
- Confirm `:app` includes a passing Robolectric startup smoke test (`BuckCastApplicationStartupTest`) asserting init order, custom `User-Agent`, and Hilt/WorkManager wiring.

### Check 2 — End-to-End Reactive UI & MapLibre Wiring
- Trace every new or modified interactive control, bottom sheet, and sensor stream end-to-end:
  `Composable` -> `ViewModel` -> `Repository / Sensor` -> `StateFlow` -> `MapLibre Style / Camera / Layer`.
- Reject:
  - Dead or no-op click handlers and stuck toggle chips.
  - Cold `SharingStarted.WhileSubscribed` cross-tab race conditions where actions triggered from Home fail before Map subscribes.
  - GeoJSON sources or layers missing re-registration inside `onStyleLoaded`.
  - Stacked `animateCamera()` calls on high-frequency sensor ticks (live Heading-Up bearing must use `moveCamera()` with shortest-arc smoothing and deadband; `animateCamera()` reserved for one-shot North-Up snap).
  - Double-rotated GeoJSON overlays (all tactical overlays must stay strictly True-North geographic).

### Check 3 — Map Styles, External Endpoints & Offline Download Engine
- Probe every external tile, DEM, contour, hydrography, and glyph fontstack URL referenced in `MapTileConfig` / `MapScreen` using real HTTP requests and confirm `200 OK` (zero `404`/`403` errors).
- Confirm all raster/DEM sources declare explicit `tileSize` (`128` for HiDPI Clarity satellite, `256` for USGS/labels/DEM) and valid `maxzoom`.
- Confirm BOTH `MapView` and `OfflineManager` (`OfflineTilePyramidRegionDefinition`) load styles through the virtual HTTPS OkHttp interceptor (`https://styles.droptine.app/*.json`)—never raw inline JSON strings or `file://` URIs for `OfflineManager`.
- Confirm offline downloads cleanly handle Cancel/Error/Timeout (resetting `isDownloading`) and that dynamic `{bbox-epsg-3857}` layers do not break `OfflineManager` region completion.

### Check 4 — Anti-Tautology Math, Sensor & Migration Review
- Confirm sensor/math tests derive inputs from independent ground truth (e.g., 3D unit quaternions for portrait + landscape + treestand aim-down `-45 deg`, `-30 deg`, `0 deg`, `+20 deg`, and published ICAO Standard Atmosphere tables for barometer tests) rather than mirroring production formulas.
- Confirm Room schema migrations increment version cleanly, export schema JSON (`2.json`, `3.json`, etc.), pass `MigrationTestHelper` / Robolectric tests, and never wipe `SitLogEntity` or `StandRouteEntity` data during stand updates.

### Check 5 — UI Contrast, Insets & Text Wrapping
- Confirm all screens apply `WindowInsets.safeDrawing` (including horizontal layout-direction insets).
- Confirm all floating map controls and right-rail FABs use opaque or high-alpha (`>= 0.90f`) themed containers with border strokes readable over bright white USGS Topo tiles across all 5 Material 3 themes.
- Confirm rating badges use `maxLines = 1, softWrap = false` (no mid-word splits like `GO / OD`), and Scout Mode banners do not stack redundantly.

### Check 6 — ADB Device Smoke Test (When Handset Attached)
- Check `adb.exe devices`. If an authorized handset is connected:
  1. Install `app\build\outputs\apk\debug\app-debug.apk` (`adb install -r`).
  2. Clear crash logs (`adb logcat -c`).
  3. Launch `com.droptine.app` via `monkey`.
  4. Wait 3 seconds and assert `adb logcat -d -b crash` returns zero fatal exceptions.
- If no device is connected, explicitly mark Check 6 as `SKIPPED (no device attached)` in the report.