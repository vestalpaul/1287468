package com.buckcast.domain.rut

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.MonthDay

class MidwestRutCalendarTest {

    @Test
    fun `all 13 phases have unique numbers, unique DropTine names, non-blank biological names, and contiguous offsets`() {
        val phases = RutPhase.entries
        assertEquals(13, phases.size)
        assertEquals((1..13).toList(), phases.map { it.number })
        assertEquals(13, phases.map { it.phaseName }.distinct().size)
        assertEquals(13, phases.map { it.biologicalName }.distinct().size)
        assertTrue(phases.all { it.phaseName.isNotBlank() && it.biologicalName.isNotBlank() })

        assertEquals(-57, phases.first().startOffsetDays)
        assertEquals(65, phases.last().endOffsetDays)
        for (i in 0 until phases.lastIndex) {
            assertEquals(
                phases[i].endOffsetDays + 1,
                phases[i + 1].startOffsetDays,
                "Gap or overlap between ${phases[i].phaseName} and ${phases[i + 1].phaseName}",
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "2026-09-15, 1, Velvet Breakup",
        "2026-09-24, 1, Velvet Breakup",
        "2026-09-25, 2, Early Feed",
        "2026-10-10, 3, October Shift",
        "2026-10-25, 4, Scrape Surge",
        "2026-11-01, 5, Cruising Window",
        "2026-11-06, 6, Peak Chase",
        "2026-11-10, 6, Peak Chase",
        "2026-11-11, 7, Breeding Lockdown",
        "2026-11-14, 7, Breeding Lockdown",
        "2026-11-15, 8, Second Wind",
        "2026-11-20, 9, Post-Rut Crash",
        "2026-11-21, 9, Post-Rut Crash",
        "2026-11-25, 9, Post-Rut Crash",
        "2026-11-26, 10, Secondary Estrus",
        "2026-12-05, 11, Cold-Front Feed",
        "2026-12-12, 12, Winter Recovery",
        "2026-12-22, 13, Late-Season Concentrate",
        "2027-01-15, 13, Late-Season Concentrate",
    )
    fun `default Nov 11 peak rut date resolves every DropTine phase boundary including Nov 21 Post-Rut Crash`(
        isoDate: String,
        expectedPhaseNum: Int,
        expectedPhaseName: String,
    ) {
        val result = MidwestRutCalendar.evaluate(LocalDate.parse(isoDate))
        assertFalse(result.isOffSeason)
        assertNotNull(result.phase)
        assertEquals(expectedPhaseNum, result.phase!!.number)
        assertEquals(expectedPhaseName, result.phase!!.phaseName)
    }

    @Test
    fun `cross-year January date anchors to prior November season peak and supports Jan 31 extension`() {
        val jan10 = MidwestRutCalendar.evaluate(LocalDate.of(2027, 1, 10))
        assertEquals(RutPhase.PHASE_13_LATE_SEASON_CONCENTRATE, jan10.phase)
        assertEquals(LocalDate.of(2026, 11, 11), jan10.seasonPeakDate)
        assertEquals(60, jan10.daysFromPeakRut)

        val jan25Default = MidwestRutCalendar.evaluate(LocalDate.of(2027, 1, 25))
        assertTrue(jan25Default.isOffSeason)

        val jan25Extended = MidwestRutCalendar.evaluate(
            date = LocalDate.of(2027, 1, 25),
            lateSeasonEndOffsetDays = 81,
        )
        assertFalse(jan25Extended.isOffSeason)
        assertEquals(RutPhase.PHASE_13_LATE_SEASON_CONCENTRATE, jan25Extended.phase)
        assertEquals(LocalDate.of(2027, 1, 31), jan25Extended.phaseEndDate)
    }

    @Test
    fun `custom peak rut date shifts entire 13-phase schedule accordingly`() {
        val southernPeak = MonthDay.of(11, 20)
        val lockDownDay = MidwestRutCalendar.evaluate(LocalDate.of(2026, 11, 20), peakRutDate = southernPeak)
        assertEquals(RutPhase.PHASE_07_BREEDING_LOCKDOWN, lockDownDay.phase)
        assertEquals(0, lockDownDay.daysFromPeakRut)

        val peakChaseDay = MidwestRutCalendar.evaluate(LocalDate.of(2026, 11, 15), peakRutDate = southernPeak)
        assertEquals(RutPhase.PHASE_06_PEAK_CHASE, peakChaseDay.phase)
    }

    @Test
    fun `off-season summer date returns isOffSeason with countdown to Phase 1`() {
        val july15 = MidwestRutCalendar.evaluate(LocalDate.of(2026, 7, 15))
        assertTrue(july15.isOffSeason)
        assertNull(july15.phase)
        assertEquals(RutPhase.PHASE_01_VELVET_BREAKUP, july15.nextPhase)
        assertEquals(62, july15.daysUntilNextPhase)
        assertEquals(20.0, july15.amBaselineScore)
    }
}
