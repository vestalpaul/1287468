# Verification & Post-Build Auditor Gate

Execute this audit pass ONCE at the end of every milestone (after all implementation checkboxes pass Gradle tests and `:app:assembleDebug`), before reporting the build ready for device testing.

## Mandatory Audit Checks
1. **Cold-Start & Lifecycle Order**: Verify `BuckCastApplication.onCreate()` initializes SDK singletons (MapLibre, RevenueCat, WorkManager) in strict dependency order and has a passing Robolectric startup smoke test.
2. **End-to-End UI & MapLibre Wiring**: For every new or modified toggle, chip, button, or sensor stream, trace the full chain from `Composable` -> `ViewModel` -> `Repository/Sensor` -> `StateFlow` -> `MapLibre Style/Layer` (including re-application inside `onStyleLoaded`). Reject any "dead" click handler or unsubscribed state.
3. **External Asset & Offline Sanity**: Verify all MapLibre raster/DEM sources declare explicit `tileSize` and valid `maxzoom`, and that `OfflineManager` exclusively consumes persisted `file://` or `https://` style URIs—never raw inline JSON strings.
4. **Anti-Tautology Test Review**: Confirm math/sensor unit tests derive inputs from independent ground truth (e.g., 3D quaternions, real pressure-altitude equations) rather than mirroring the production formula under test.
5. **UI Contrast & Inset Check**: Confirm all screens apply `WindowInsets.safeDrawing` and all floating map controls use opaque or high-alpha (`>= 0.90f`) themed containers readable over bright Topo tiles.
6. **ADB Device Smoke Test (When Device Attached)**: If `adb devices` shows an authorized handset, install `app-debug.apk`, clear logcat, launch `com.droptine.app`, wait 3 seconds, and assert `adb logcat -d -b crash` returns zero fatal exceptions.
