package com.buckcast.domain.rut

import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/**
 * The 13 official Midwest whitetail season phases pairing Drury Outdoors THIRTEEN names
 * with biological behavioral stages, anchored to Lock Down start (`peakRutDate`, default Nov 11).
 */
enum class RutPhase(
    val number: Int,
    val druryName: String,
    val biologicalName: String,
    val startOffsetDays: Int,
    val endOffsetDays: Int,
    val amBaselineScore: Double,
    val pmBaselineScore: Double,
    val tacticalFocus: String,
) {
    PHASE_01_A_NEW_BEGINNING(
        number = 1,
        druryName = "A New Beginning",
        biologicalName = "Velvet Shed & Bachelor Breakup",
        startOffsetDays = -57,
        endOffsetDays = -48,
        amBaselineScore = 38.0,
        pmBaselineScore = 56.0,
        tacticalFocus = "Evening destination food sources and low-impact observation sets.",
    ),
    PHASE_02_GREENER_PASTURES(
        number = 2,
        druryName = "Greener Pastures",
        biologicalName = "Early-Season Food Patterning",
        startOffsetDays = -47,
        endOffsetDays = -33,
        amBaselineScore = 44.0,
        pmBaselineScore = 68.0,
        tacticalFocus = "Primary green plots, alfalfa/beans, and acorn transitions on cool evenings.",
    ),
    PHASE_03_THE_OCTOBER_LULL(
        number = 3,
        druryName = "The October Lull",
        biologicalName = "Mid-October Range Shift & Mast Focus",
        startOffsetDays = -32,
        endOffsetDays = -18,
        amBaselineScore = 30.0,
        pmBaselineScore = 45.0,
        tacticalFocus = "Staging areas near hard mast and bedding edges on cold-front evenings.",
    ),
    PHASE_04_PRE_LOCK(
        number = 4,
        druryName = "Pre-Lock",
        biologicalName = "Pre-Rut Scrape Activity & Testosterone Surge",
        startOffsetDays = -17,
        endOffsetDays = -11,
        amBaselineScore = 70.0,
        pmBaselineScore = 78.0,
        tacticalFocus = "Active scrape lines, staging rubs, and timber corridors near doe bedding.",
    ),
    PHASE_05_HIGH_ANTICIPATION(
        number = 5,
        druryName = "High Anticipation",
        biologicalName = "Seeking Phase (Early Estrus Search)",
        startOffsetDays = -10,
        endOffsetDays = -6,
        amBaselineScore = 92.0,
        pmBaselineScore = 88.0,
        tacticalFocus = "Dawn downwind edges of doe bedding and mid-morning pinch points.",
    ),
    PHASE_06_BUCK_PARADE(
        number = 6,
        druryName = "Buck Parade",
        biologicalName = "Peak Rut — Cruising & Chasing",
        startOffsetDays = -5,
        endOffsetDays = -1,
        amBaselineScore = 100.0,
        pmBaselineScore = 98.0,
        tacticalFocus = "All-day rut funnels, creek crossings, and saddle travel corridors.",
    ),
    PHASE_07_LOCK_DOWN(
        number = 7,
        druryName = "Lock Down",
        biologicalName = "Peak Breeding — Doe Tending & Lockdown",
        startOffsetDays = 0,
        endOffsetDays = 3,
        amBaselineScore = 64.0,
        pmBaselineScore = 62.0,
        tacticalFocus = "Thick thermal cover, remote ditches, and midday cruising checks between doe pockets.",
    ),
    PHASE_08_DESPERATELY_SEEKING(
        number = 8,
        druryName = "Desperately Seeking",
        biologicalName = "Late-Rut Secondary Seeking & Cruising",
        startOffsetDays = 4,
        endOffsetDays = 8,
        amBaselineScore = 84.0,
        pmBaselineScore = 80.0,
        tacticalFocus = "Bucks break lockdown searching remaining unbred does along travel corridors.",
    ),
    PHASE_09_PARTYS_OVER(
        number = 9,
        druryName = "Party's Over",
        biologicalName = "Post-Rut Recovery & Exhaustion Lull",
        startOffsetDays = 9,
        endOffsetDays = 14,
        amBaselineScore = 32.0,
        pmBaselineScore = 44.0,
        tacticalFocus = "Target sheltered south-facing bedding cover and late afternoon food recovery.",
    ),
    PHASE_10_GREEN_REVISITED(
        number = 10,
        druryName = "Green Revisited",
        biologicalName = "Secondary Rut (Fawn Estrus) & Food Return",
        startOffsetDays = 15,
        endOffsetDays = 23,
        amBaselineScore = 55.0,
        pmBaselineScore = 72.0,
        tacticalFocus = "Evening green plots and standing grain drawing doe groups and secondary cruisers.",
    ),
    PHASE_11_WAITING_ON_A_FRONT(
        number = 11,
        druryName = "Waiting on a Front",
        biologicalName = "Early Winter Cold-Front Feeding",
        startOffsetDays = 24,
        endOffsetDays = 30,
        amBaselineScore = 48.0,
        pmBaselineScore = 70.0,
        tacticalFocus = "Strike aggressively on sharp temperature drops and rising post-front pressure.",
    ),
    PHASE_12_FEEDBACK(
        number = 12,
        druryName = "Feedback",
        biologicalName = "Mid-Winter Caloric Recovery",
        startOffsetDays = 31,
        endOffsetDays = 40,
        amBaselineScore = 50.0,
        pmBaselineScore = 76.0,
        tacticalFocus = "High-calorie winter food sources on afternoon thermal-slope sits.",
    ),
    PHASE_13_THE_GRAND_FINALE(
        number = 13,
        druryName = "The Grand Finale",
        biologicalName = "Late-Winter Food-Source Concentration",
        startOffsetDays = 41,
        endOffsetDays = 65,
        amBaselineScore = 52.0,
        pmBaselineScore = 80.0,
        tacticalFocus = "Late-season destination feed patterns ahead of winter fronts.",
    ),
}

/**
 * Resolved rut calendar state for a given local date.
 */
data class RutDayAssessment(
    val date: LocalDate,
    val peakRutDate: MonthDay,
    val seasonPeakDate: LocalDate,
    val daysFromPeakRut: Int,
    val isOffSeason: Boolean,
    val phase: RutPhase?,
    val dayInPhase: Int?,
    val phaseTotalDays: Int?,
    val phaseStartDate: LocalDate?,
    val phaseEndDate: LocalDate?,
    val nextPhase: RutPhase?,
    val daysUntilNextPhase: Int?,
    val amBaselineScore: Double,
    val pmBaselineScore: Double,
) {
    val phaseProgress: Double?
        get() = if (dayInPhase != null && phaseTotalDays != null && phaseTotalDays > 0) {
            dayInPhase.toDouble() / phaseTotalDays.toDouble()
        } else {
            null
        }
}

/**
 * Pure-Kotlin Midwest 13-phase Whitetail Rut Calendar calculator.
 */
object MidwestRutCalendar {

    val DEFAULT_PEAK_RUT_DATE: MonthDay = MonthDay.of(Month.NOVEMBER, 11)
    const val DEFAULT_LATE_SEASON_END_OFFSET_DAYS: Int = 65
    const val MAX_LATE_SEASON_END_OFFSET_DAYS: Int = 81
    private const val SEASON_START_OFFSET_DAYS: Int = -57
    private const val OFF_SEASON_BASELINE_SCORE: Double = 20.0

    fun evaluate(
        date: LocalDate,
        peakRutDate: MonthDay = DEFAULT_PEAK_RUT_DATE,
        lateSeasonEndOffsetDays: Int = DEFAULT_LATE_SEASON_END_OFFSET_DAYS,
    ): RutDayAssessment {
        require(lateSeasonEndOffsetDays in DEFAULT_LATE_SEASON_END_OFFSET_DAYS..MAX_LATE_SEASON_END_OFFSET_DAYS) {
            "lateSeasonEndOffsetDays must be in $DEFAULT_LATE_SEASON_END_OFFSET_DAYS..$MAX_LATE_SEASON_END_OFFSET_DAYS, was: $lateSeasonEndOffsetDays"
        }

        // Check adjacent season anchor years to resolve Dec -> Jan cross-year seasons cleanly.
        val candidateYears = listOf(date.year - 1, date.year, date.year + 1)
        for (year in candidateYears) {
            val peakDate = resolvePeakDateForYear(year, peakRutDate)
            val offset = ChronoUnit.DAYS.between(peakDate, date).toInt()
            if (offset in SEASON_START_OFFSET_DAYS..lateSeasonEndOffsetDays) {
                val phase = resolvePhaseForOffset(offset, lateSeasonEndOffsetDays)
                val effectiveEndOffset = effectiveEndOffset(phase, lateSeasonEndOffsetDays)
                val startDate = peakDate.plusDays(phase.startOffsetDays.toLong())
                val endDate = peakDate.plusDays(effectiveEndOffset.toLong())
                val dayInPhase = offset - phase.startOffsetDays + 1
                val totalDays = effectiveEndOffset - phase.startOffsetDays + 1
                val next = RutPhase.entries.getOrNull(phase.ordinal + 1)
                val daysUntilNext = next?.let { it.startOffsetDays - offset }

                return RutDayAssessment(
                    date = date,
                    peakRutDate = peakRutDate,
                    seasonPeakDate = peakDate,
                    daysFromPeakRut = offset,
                    isOffSeason = false,
                    phase = phase,
                    dayInPhase = dayInPhase,
                    phaseTotalDays = totalDays,
                    phaseStartDate = startDate,
                    phaseEndDate = endDate,
                    nextPhase = next,
                    daysUntilNextPhase = daysUntilNext,
                    amBaselineScore = phase.amBaselineScore,
                    pmBaselineScore = phase.pmBaselineScore,
                )
            }
        }

        val nextSeasonPeak = candidateYears
            .map { resolvePeakDateForYear(it, peakRutDate) }
            .firstOrNull { !it.plusDays(SEASON_START_OFFSET_DAYS.toLong()).isBefore(date) }
            ?: resolvePeakDateForYear(date.year + 1, peakRutDate)

        val nextStartDate = nextSeasonPeak.plusDays(SEASON_START_OFFSET_DAYS.toLong())
        val daysUntilStart = ChronoUnit.DAYS.between(date, nextStartDate).toInt()
        val daysFromNearestPeak = ChronoUnit.DAYS.between(nextSeasonPeak, date).toInt()

        return RutDayAssessment(
            date = date,
            peakRutDate = peakRutDate,
            seasonPeakDate = nextSeasonPeak,
            daysFromPeakRut = daysFromNearestPeak,
            isOffSeason = true,
            phase = null,
            dayInPhase = null,
            phaseTotalDays = null,
            phaseStartDate = null,
            phaseEndDate = null,
            nextPhase = RutPhase.PHASE_01_A_NEW_BEGINNING,
            daysUntilNextPhase = daysUntilStart,
            amBaselineScore = OFF_SEASON_BASELINE_SCORE,
            pmBaselineScore = OFF_SEASON_BASELINE_SCORE,
        )
    }

    private fun resolvePeakDateForYear(year: Int, peakRutDate: MonthDay): LocalDate {
        return if (peakRutDate.month == Month.FEBRUARY && peakRutDate.dayOfMonth == 29 && !LocalDate.of(year, 1, 1).isLeapYear) {
            LocalDate.of(year, Month.FEBRUARY, 28)
        } else {
            peakRutDate.atYear(year)
        }
    }

    private fun resolvePhaseForOffset(offsetDays: Int, lateSeasonEndOffsetDays: Int): RutPhase =
        RutPhase.entries.first { phase ->
            val end = effectiveEndOffset(phase, lateSeasonEndOffsetDays)
            offsetDays in phase.startOffsetDays..end
        }

    private fun effectiveEndOffset(phase: RutPhase, lateSeasonEndOffsetDays: Int): Int =
        if (phase == RutPhase.PHASE_13_THE_GRAND_FINALE) lateSeasonEndOffsetDays else phase.endOffsetDays
}
