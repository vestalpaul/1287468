package com.buckcast.feature.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buckcast.domain.astronomy.LegalLightRule
import com.buckcast.domain.astronomy.LunarCalculator
import com.buckcast.domain.astronomy.LunarDayInfo
import com.buckcast.domain.astronomy.NoaaSolarCalculator
import com.buckcast.domain.astronomy.SolarDayInfo
import com.buckcast.domain.entitlement.AlphaFeature
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.CompassDirection
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.HourlyWeatherPoint
import com.buckcast.domain.model.OfflineMessageFormatter
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.rut.MidwestRutCalendar
import com.buckcast.domain.rut.RutDayAssessment
import com.buckcast.domain.scoring.HuntSession
import com.buckcast.domain.scoring.MovementRating
import com.buckcast.domain.scoring.MovementScoreReport
import com.buckcast.domain.scoring.MovementScorer
import com.buckcast.domain.scoring.PrecipType
import com.buckcast.domain.scoring.SessionWeatherInput
import com.buckcast.domain.stands.StandFreshnessCalculator
import com.buckcast.domain.stands.StandFreshnessLevel
import com.buckcast.domain.stands.StandFreshnessResult
import com.buckcast.domain.stands.StandSitRecord
import com.buckcast.domain.wind.StandWindConfig
import com.buckcast.domain.wind.StandWindEvaluation
import com.buckcast.domain.wind.StandWindEvaluator
import com.buckcast.domain.wind.ThermalDriftCalculator
import com.buckcast.domain.wind.ThermalInput
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

enum class OutlookViewMode {
    CARDS,
    TIMELINE,
}

enum class StandWindSlot(val label: String) {
    NOW("Now"),
    TODAY_AM("Today AM"),
    TODAY_PM("Today PM"),
    TOMORROW_AM("Tomorrow AM"),
    TOMORROW_PM("Tomorrow PM"),
}

data class DayOutlookUi(
    val dayIndex: Int,
    val date: LocalDate,
    val dayLabel: String,
    val tempMaxDegF: Double,
    val tempMinDegF: Double,
    val windSpeedMph: Double,
    val windDirectionLabel: String,
    val windFromDeg: Double,
    val pressureInHg: Double,
    val pressureTrend3hInHg: Double,
    val solar: SolarDayInfo,
    val lunar: LunarDayInfo,
    val rut: RutDayAssessment,
    val amReport: MovementScoreReport,
    val pmReport: MovementScoreReport,
    val daytimeHours: List<HourlyWeatherPoint>,
)

data class RankedStandUi(
    val stand: Stand,
    val evaluation: StandWindEvaluation,
    val freshness: StandFreshnessResult,
    val isLockedByScout: Boolean,
)

data class ForecastUiState(
    val locationLabel: String = "Midwest Default",
    val latitude: Double = DEFAULT_LAT,
    val longitude: Double = DEFAULT_LON,
    val currentHour: HourlyWeatherPoint? = null,
    val todayOutlook: DayOutlookUi? = null,
    val sevenDayOutlook: List<DayOutlookUi> = emptyList(),
    val rankedStands: List<RankedStandUi> = emptyList(),
    val selectedStandSlot: StandWindSlot = StandWindSlot.NOW,
    val outlookViewMode: OutlookViewMode = OutlookViewMode.CARDS,
    val entitlement: EntitlementState = EntitlementState(
        tier = SubscriptionTier.SCOUT,
        source = EntitlementSource.NONE,
        previewStartedEpochMs = 0L,
        previewExpiresEpochMs = 0L,
        subscriptionExpiresEpochMs = null,
        lastVerifiedEpochMs = null,
        isOfflineGraceActive = false,
        remainingPreviewMs = 0L,
    ),
    val isCacheStale: Boolean = false,
    val cachedAtEpochMs: Long? = null,
    val isRefreshing: Boolean = false,
    val offlineBannerMessage: String? = null,
    val selectedIntelDayIndex: Int? = null,
) {
    val canViewSevenDays: Boolean
        get() = entitlement.hasAccess(AlphaFeature.SEVEN_DAY_OUTLOOK)

    val canViewFiveFactorIntel: Boolean
        get() = entitlement.hasAccess(AlphaFeature.FIVE_FACTOR_INTEL)

    val canViewFullStandIntel: Boolean
        get() = entitlement.hasAccess(AlphaFeature.STAND_WIND_FRESHNESS)

    companion object {
        const val DEFAULT_LAT = 41.5868
        const val DEFAULT_LON = -93.6250
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForecastViewModel @Inject constructor(
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val standRepository: StandRepository,
    private val entitlementRepository: EntitlementRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val selectedSlotFlow = MutableStateFlow(StandWindSlot.NOW)
    private val viewModeFlow = MutableStateFlow(OutlookViewMode.CARDS)
    private val refreshingFlow = MutableStateFlow(false)
    private val offlineMsgFlow = MutableStateFlow<String?>(null)
    private val intelDayFlow = MutableStateFlow<Int?>(null)

    private val standsWithSitsFlow: Flow<List<Pair<Stand, List<StandSit>>>> =
        standRepository.observeStands().flatMapLatest { stands ->
            if (stands.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    stands.map { stand ->
                        standRepository.observeSitsForStand(stand.id).combine(flowOf(stand)) { sits, s ->
                            s to sits
                        }
                    },
                ) { array -> array.toList() }
            }
        }

    val uiState: StateFlow<ForecastUiState> = combine(
        weatherRepository.observeLatestCachedForecast(),
        settingsRepository.observeSettings(),
        standsWithSitsFlow,
        entitlementRepository.observeEntitlement(),
        combine(
            locationProvider.observeLocation(LocationPowerMode.BATTERY_SAVER),
            selectedSlotFlow,
            viewModeFlow,
            refreshingFlow,
            combine(offlineMsgFlow, intelDayFlow) { msg, idx -> msg to idx },
        ) { loc, slot, mode, refreshing, extras ->
            UiControls(loc, slot, mode, refreshing, extras.first, extras.second)
        },
    ) { cachedForecast, settings, standsWithSits, entitlement, controls ->
        buildUiState(
            cachedForecast = cachedForecast,
            settings = settings,
            standsWithSits = standsWithSits,
            entitlement = entitlement,
            controls = controls,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ForecastUiState(),
    )

    init {
        viewModelScope.launch {
            entitlementRepository.ensureInitialized()
        }
        refreshNow()
    }

    fun setOutlookViewMode(mode: OutlookViewMode) {
        viewModeFlow.value = mode
    }

    fun selectStandSlot(slot: StandWindSlot) {
        selectedSlotFlow.value = slot
    }

    fun openDayIntel(dayIndex: Int): Boolean {
        val state = uiState.value
        if (dayIndex > 0 && !state.canViewSevenDays) {
            return false
        }
        if (!state.canViewFiveFactorIntel) {
            return false
        }
        intelDayFlow.value = dayIndex
        return true
    }

    fun closeDayIntel() {
        intelDayFlow.value = null
    }

    fun refreshNow() {
        viewModelScope.launch {
            refreshingFlow.value = true
            val current = uiState.value
            val settings = settingsRepository.getSettings()
            val latestCached = weatherRepository.observeLatestCachedForecast().firstOrNull()
            val lat = current.latitude.takeIf { it != 0.0 }
                ?: settings.lastKnownLat
                ?: latestCached?.latitude
                ?: ForecastUiState.DEFAULT_LAT
            val lon = current.longitude.takeIf { it != 0.0 }
                ?: settings.lastKnownLon
                ?: latestCached?.longitude
                ?: ForecastUiState.DEFAULT_LON

            settingsRepository.updateLastKnownLocation(lat, lon)
            val result = weatherRepository.refreshForecast(lat, lon)
            offlineMsgFlow.value = if (result.isFailure) {
                OfflineMessageFormatter.formatWeatherError(
                    error = result.exceptionOrNull(),
                    cachedAtEpochMs = latestCached?.fetchedAtEpochMs ?: uiState.value.cachedAtEpochMs,
                )
            } else {
                null
            }
            refreshingFlow.value = false
        }
    }

    private fun buildUiState(
        cachedForecast: CachedWeatherForecast?,
        settings: com.buckcast.domain.model.AppSettings,
        standsWithSits: List<Pair<Stand, List<StandSit>>>,
        entitlement: EntitlementState,
        controls: UiControls,
    ): ForecastUiState {
        val fix = controls.locationState as? LocationState.Fix
        val lat = fix?.point?.latitude ?: settings.lastKnownLat ?: cachedForecast?.latitude ?: ForecastUiState.DEFAULT_LAT
        val lon = fix?.point?.longitude ?: settings.lastKnownLon ?: cachedForecast?.longitude ?: ForecastUiState.DEFAULT_LON
        val locationLabel = when {
            fix != null -> String.format(Locale.US, "GPS %.2f, %.2f", lat, lon)
            cachedForecast != null -> "Cell ${cachedForecast.gridKey}"
            else -> String.format(Locale.US, "%.2f, %.2f", lat, lon)
        }

        val zoneId = cachedForecast?.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val nowSec = nowInstant.epochSecond
        val currentHour = cachedForecast?.hourly?.minByOrNull { abs(it.epochSeconds - nowSec) }

        val peakRut = parseMonthDayOrDefault(settings.peakRutMonthDay)
        val legalRule = LegalLightRule(
            beforeSunriseMinutes = settings.legalLightBeforeSunriseMinutes,
            afterSunsetMinutes = settings.legalLightAfterSunsetMinutes,
        )

        val todayDate = LocalDate.now(zoneId)
        val sevenDayList = buildSevenDayOutlook(
            todayDate = todayDate,
            lat = lat,
            lon = lon,
            zoneId = zoneId,
            peakRut = peakRut,
            legalRule = legalRule,
            cachedForecast = cachedForecast,
        )

        val targetSlotHour = resolveSlotHour(
            slot = controls.slot,
            currentHour = currentHour,
            sevenDay = sevenDayList,
            zoneId = zoneId,
        )

        val rankedStands = buildRankedStands(
            standsWithSits = standsWithSits,
            slotHour = targetSlotHour,
            todaySolar = sevenDayList.firstOrNull()?.solar,
            nowInstant = nowInstant,
            zoneId = zoneId,
            entitlement = entitlement,
        )

        return ForecastUiState(
            locationLabel = locationLabel,
            latitude = lat,
            longitude = lon,
            currentHour = currentHour,
            todayOutlook = sevenDayList.firstOrNull(),
            sevenDayOutlook = sevenDayList,
            rankedStands = rankedStands,
            selectedStandSlot = controls.slot,
            outlookViewMode = controls.viewMode,
            entitlement = entitlement,
            isCacheStale = cachedForecast?.isStale ?: false,
            cachedAtEpochMs = cachedForecast?.fetchedAtEpochMs,
            isRefreshing = controls.refreshing,
            offlineBannerMessage = controls.offlineMsg,
            selectedIntelDayIndex = controls.intelDayIndex,
        )
    }

    private fun buildSevenDayOutlook(
        todayDate: LocalDate,
        lat: Double,
        lon: Double,
        zoneId: ZoneId,
        peakRut: MonthDay,
        legalRule: LegalLightRule,
        cachedForecast: CachedWeatherForecast?,
    ): List<DayOutlookUi> {
        val allHourly = cachedForecast?.hourly.orEmpty()
        val sevenDayMeanTemp = allHourly.takeIf { it.isNotEmpty() }
            ?.map { it.tempDegF }?.average()
            ?: 42.0
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.US)

        return (0 until 7).map { idx ->
            val date = todayDate.plusDays(idx.toLong())
            val dateIso = date.toString()
            val dailySummary = cachedForecast?.daily?.firstOrNull { it.isoDate == dateIso }
            val dayHours = allHourly.filter { it.isoLocalTime.startsWith(dateIso) }
            val daytimeHours = dayHours.filter { h ->
                val hr = runCatching { LocalDateTime.parse(h.isoLocalTime).hour }.getOrDefault(-1)
                hr in 4..21
            }

            val solar = NoaaSolarCalculator.calculateDay(date, lat, lon, zoneId, legalRule)
            val lunar = LunarCalculator.calculateDay(date, lat, lon, zoneId)
            val rut = MidwestRutCalendar.evaluate(date, peakRutDate = peakRut)

            val amHour = dayHours.firstOrNull { it.isoLocalTime.contains("T07:") } ?: dayHours.firstOrNull()
            val pmHour = dayHours.firstOrNull { it.isoLocalTime.contains("T16:") } ?: dayHours.lastOrNull()

            val amReport = scoreSessionFromHour(HuntSession.AM, amHour, sevenDayMeanTemp, rut)
            val pmReport = scoreSessionFromHour(HuntSession.PM, pmHour, sevenDayMeanTemp, rut)

            val windFrom = dailySummary?.dominantWindFromDeg ?: amHour?.windFromDeg ?: 0.0
            val windMph = dailySummary?.windSpeedMaxMph ?: amHour?.windSpeedMph ?: 6.0
            val maxTemp = dailySummary?.tempMaxDegF ?: dayHours.maxOfOrNull { it.tempDegF } ?: 45.0
            val minTemp = dailySummary?.tempMinDegF ?: dayHours.minOfOrNull { it.tempDegF } ?: 28.0
            val repPressure = amHour?.barometricPressureInHg ?: 30.05
            val repTrend = amHour?.pressureTrend3hInHg ?: 0.0

            DayOutlookUi(
                dayIndex = idx,
                date = date,
                dayLabel = if (idx == 0) "Today" else dayFmt.format(date),
                tempMaxDegF = maxTemp,
                tempMinDegF = minTemp,
                windSpeedMph = windMph,
                windDirectionLabel = CompassDirection.fromBearing(windFrom).abbreviation,
                windFromDeg = windFrom,
                pressureInHg = repPressure,
                pressureTrend3hInHg = repTrend,
                solar = solar,
                lunar = lunar,
                rut = rut,
                amReport = amReport,
                pmReport = pmReport,
                daytimeHours = daytimeHours,
            )
        }
    }

    private fun scoreSessionFromHour(
        session: HuntSession,
        hour: HourlyWeatherPoint?,
        sevenDayMeanDegF: Double,
        rut: RutDayAssessment,
    ): MovementScoreReport {
        val input = if (hour != null) {
            SessionWeatherInput(
                session = session,
                barometricPressureInHg = hour.barometricPressureInHg.coerceIn(25.0, 33.0),
                pressureTrend3hInHg = hour.pressureTrend3hInHg,
                tempDegF = hour.tempDegF.coerceIn(-60.0, 130.0),
                sevenDayMeanTempDegF = sevenDayMeanDegF.coerceIn(-60.0, 130.0),
                rutAssessment = rut,
                windSpeedMph = hour.windSpeedMph.coerceAtLeast(0.0),
                windGustMph = hour.windGustMph,
                precipInchesPerHr = hour.precipInchesPerHr.coerceAtLeast(0.0),
                precipType = inferPrecipType(hour.weatherCode, hour.precipInchesPerHr, hour.tempDegF),
            )
        } else {
            SessionWeatherInput(
                session = session,
                barometricPressureInHg = 30.05,
                pressureTrend3hInHg = 0.0,
                tempDegF = 42.0,
                sevenDayMeanTempDegF = 42.0,
                rutAssessment = rut,
                windSpeedMph = 7.0,
            )
        }
        return MovementScorer.evaluateSession(input)
    }

    private fun inferPrecipType(weatherCode: Int, precipIn: Double, tempDegF: Double): PrecipType = when {
        precipIn <= 0.001 -> PrecipType.NONE
        weatherCode in 71..77 || weatherCode in 85..86 -> PrecipType.SNOW
        weatherCode in 56..57 || weatherCode in 66..67 -> PrecipType.SLEET_FREEZING_RAIN
        weatherCode in 51..55 -> PrecipType.DRIZZLE
        precipIn > 0.20 || weatherCode in listOf(65, 82, 95, 96, 99) -> PrecipType.HEAVY_RAIN
        tempDegF <= 32.0 -> PrecipType.SNOW
        else -> PrecipType.LIGHT_RAIN
    }

    private fun resolveSlotHour(
        slot: StandWindSlot,
        currentHour: HourlyWeatherPoint?,
        sevenDay: List<DayOutlookUi>,
        zoneId: ZoneId,
    ): HourlyWeatherPoint? = when (slot) {
        StandWindSlot.NOW -> currentHour
        StandWindSlot.TODAY_AM -> sevenDay.getOrNull(0)?.daytimeHours?.firstOrNull { it.isoLocalTime.contains("T07:") }
            ?: currentHour
        StandWindSlot.TODAY_PM -> sevenDay.getOrNull(0)?.daytimeHours?.firstOrNull { it.isoLocalTime.contains("T16:") }
            ?: currentHour
        StandWindSlot.TOMORROW_AM -> sevenDay.getOrNull(1)?.daytimeHours?.firstOrNull { it.isoLocalTime.contains("T07:") }
            ?: currentHour
        StandWindSlot.TOMORROW_PM -> sevenDay.getOrNull(1)?.daytimeHours?.firstOrNull { it.isoLocalTime.contains("T16:") }
            ?: currentHour
    }

    private fun buildRankedStands(
        standsWithSits: List<Pair<Stand, List<StandSit>>>,
        slotHour: HourlyWeatherPoint?,
        todaySolar: SolarDayInfo?,
        nowInstant: Instant,
        zoneId: ZoneId,
        entitlement: EntitlementState,
    ): List<RankedStandUi> {
        val windFromDeg = slotHour?.windFromDeg ?: 315.0
        val windSpeedMph = slotHour?.windSpeedMph ?: 7.0
        val cloudPct = slotHour?.cloudCoverPercent ?: 25.0

        val isAlpha = entitlement.hasAccess(AlphaFeature.STAND_WIND_FRESHNESS)

        return standsWithSits.mapIndexed { idx, (stand, sits) ->
            val thermal = if (
                stand.slopeAspectDeg != null &&
                stand.slopeGradePercent != null &&
                todaySolar?.sunrise != null &&
                todaySolar.sunset != null
            ) {
                ThermalDriftCalculator.calculate(
                    ThermalInput(
                        slopeAspectDeg = stand.slopeAspectDeg!!,
                        slopeGradePercent = stand.slopeGradePercent!!,
                        currentTime = ZonedDateTime.ofInstant(nowInstant, zoneId),
                        sunrise = todaySolar.sunrise!!,
                        sunset = todaySolar.sunset!!,
                        cloudCoverPercent = cloudPct,
                        ambientWindSpeedMph = windSpeedMph,
                        ambientWindFromDeg = windFromDeg,
                    ),
                )
            } else {
                null
            }

            val evaluation = StandWindEvaluator.evaluate(
                config = StandWindConfig(
                    standId = stand.id,
                    standName = stand.name,
                    preferredWindFromSectors = stand.preferredWindSectors,
                ),
                ambientWindFromDeg = windFromDeg,
                ambientWindSpeedMph = windSpeedMph,
                thermalDrift = thermal,
            )
            val freshness = StandFreshnessCalculator.evaluate(
                sits = sits.map { sit ->
                    StandSitRecord(
                        startTime = Instant.ofEpochMilli(sit.startTimeEpochMs),
                        endTime = Instant.ofEpochMilli(sit.endTimeEpochMs),
                        windedDeer = sit.windedDeer,
                    )
                },
                now = nowInstant,
            )
            RankedStandUi(
                stand = stand,
                evaluation = evaluation,
                freshness = freshness,
                isLockedByScout = !isAlpha && idx >= 1,
            )
        }.sortedByDescending { it.evaluation.score }
    }

    private fun parseMonthDayOrDefault(raw: String): MonthDay {
        val parts = raw.split("-")
        return if (parts.size == 2) {
            runCatching { MonthDay.of(parts[0].toInt(), parts[1].toInt()) }
                .getOrElse { MidwestRutCalendar.DEFAULT_PEAK_RUT_DATE }
        } else {
            MidwestRutCalendar.DEFAULT_PEAK_RUT_DATE
        }
    }

    private data class UiControls(
        val locationState: LocationState,
        val slot: StandWindSlot,
        val viewMode: OutlookViewMode,
        val refreshing: Boolean,
        val offlineMsg: String?,
        val intelDayIndex: Int?,
    )
}
