---
trigger: always_on
---

# BuckCastAndroid Architecture & Persistence Rules

## 1. Multi-Module Structure & Dependency DAG
BuckCastAndroid uses an 11-module Gradle Kotlin DSL architecture under namespace `com.buckcast`:

- `:app` — Thin application shell (`BuckCastApplication` `@HiltAndroidApp`, `MainActivity`, 4-tab navigation shell: Home / Map / Track / Settings, top-level Hilt DI wiring). Contains zero business math or persistence logic.
- `:core:domain` — Pure Kotlin JVM module (`org.jetbrains.kotlin.jvm`). **MUST have zero `android.*`, `androidx.*`, Room, Retrofit, Ktor, Hilt-Android, or MapLibre imports.** Owns all whitetail domain models, astronomy, rut calendar, movement scoring, wind/thermal math, route auditing, ballistics, and repository interfaces.
- `:core:data` — Owns Room SQLite database (entities, DAOs, TypeConverters, migrations), Open-Meteo network client + offline forecast cache, and repository implementations for `:core:domain` interfaces.
- `:core:sensors` — Owns Android hardware sensor fusion (`TYPE_ROTATION_VECTOR` + `GeomagneticField`) and GNSS track recording. Depends on `:core:domain`.
- `:feature:forecast` — Forecast dashboard & 5-factor movement breakdown UI.
- `:feature:map` — MapLibre Native 12.x offline/hybrid tactical map UI, stand & route overlays, live scent cone & thermal drift HUD.
- `:feature:stands` — Tree stand / blind management UI and wind-match evaluation.
- `:feature:fieldlog` — Field logs, deer sightings, harvest & scrape/rub journal UI.
- `:feature:tracker` — Live GNSS track recording & walk-in/exit route telemetry UI.
- `:feature:gemini` — Gemini field-assistant handoff & context builder.
- `:feature:settings` — Peak rut date, legal light rules, and offline cache management.

### Dependency Boundaries
1. `:feature:*` modules depend on `:core:domain` (and `:core:sensors` where required), **never** directly on Room DAOs, Room entities, or network DTOs.
2. `:core:data` depends on `:core:domain` and maps Room entities / Open-Meteo DTOs to `:core:domain` models at the repository boundary.
3. No feature-to-feature direct dependencies; cross-feature navigation routes flow through `:app`.

## 2. Offline-First Room Persistence (Single Source of Truth)
All hunting data MUST work reliably in zero-cell backcountry conditions:

- **Immediate Local Writes**: Stands, entry/exit routes, GNSS tracks, field logs, sightings, and user settings MUST persist immediately to Room SQLite with zero network dependency.
- **Cached Open-Meteo Forecasts**: Hourly and daily Open-Meteo responses MUST be cached in Room keyed by spatial grid cell + `fetchedAtEpochMillis`.
- **Reactive SSOT & Stale-While-Revalidate**: UI reads exclusively from reactive Room `Flow` streams. Network refreshes update Room silently; when offline or when network fails, cached Open-Meteo forecasts remain active with explicit staleness metadata (`fetchedAtEpochMillis`, `isStale`).

## 3. Build & Tech Stack Standards
- **Gradle Kotlin DSL** (`*.gradle.kts`) with centralized version catalog `gradle/libs.versions.toml`.
- **SDK Targets**: `minSdk = 26`, `targetSdk = 35`, `compileSdk = 36`, JVM toolchain `17`.
- **AGP 9 Built-in Kotlin**: Do NOT apply `org.jetbrains.kotlin.android` to Android modules (`com.android.application` or `com.android.library`).
- **Annotation Processing**: Use **KSP** (never KAPT) for Room and Hilt.
- **Mapping & UI**: Jetpack Compose + Hilt + Room + MapLibre Native `12.x`.
- **Domain Testing**: JUnit 5 (`useJUnitPlatform()`) in `:core:domain`.

## 4. Domain Unit Convention
- `:core:domain` standardizes US Imperial units with explicit property suffixes: `Mph`, `DegF`, `InHg`, `InchesPerHr`, `Yards`, and `Feet`.
- Metric-to-imperial conversion (e.g., Open-Meteo `km/h`, `°C`, `hPa`, `mm`) MUST occur strictly at the `:core:data` repository mapping boundary using `:core:domain` unit helpers.
