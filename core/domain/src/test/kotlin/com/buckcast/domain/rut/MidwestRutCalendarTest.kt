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
    fun `all 13 phases have unique numbers, unique Drury names, non-blank biological names, and contiguous offsets`() {
        val phases = RutPhase.entries
        assertEquals(13, phases.size)
        assertEquals((1..13).toList(), phases.map { it.number })
        assertEquals(13, phases.map { it.druryName }.distinct().size)
        assertEquals(13, phases.map { it.biologicalName }.distinct().size)
        assertTrue(phases.all { it.druryName.isNotBlank() && it.biologicalName.isNotBlank() })

        // Verify strictly contiguous, zero-gap coverage from -57 to +65
        assertEquals(-57, phases.first().startOffsetDays)
        assertEquals(65, phases.last().endOffsetDays)
        for (i in 0 until phases.lastIndex) {
            assertEquals(
                phases[i].endOffsetDays + 1,
                phases[i + 1].startOffsetDays,
                "Gap or overlap between ${phases[i].druryName} and ${phases[i + 1].druryName}",
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "2026-09-15, 1, A New Beginning",
        "2026-09-24, 1, A New Beginning",
        "2026-09-25, 2, Greener Pastures",
        "2026-10-10, 3, The October Lull",
        "2026-10-25, 4, Pre-Lock",
        "2026-11-01, 5, High Anticipation",
        "2026-11-06, 6, Buck Parade",
        "2026-11-10, 6, Buck Parade",
        "2026-11-11, 7, Lock Down",
        "2026-11-14, 7, Lock Down",
        "2026-11-15, 8, Desperately Seeking",
        "2026-11-20, 9, Party's Over",
        "2026-11-21, 9, Party's Over",
        "2026-11-25, 9, Party's Over",
        "2026-11-26, 10, Green Revisited",
        "2026-12-05, 11, Waiting on a Front",
        "2026-12-12, 12, Feedback",
        "2026-12-22, 13, The Grand Finale",
        "2027-01-15, 13, The Grand Finale",
    )
    fun `default Nov 11 peak rut date resolves every Missouri-Midwest phase boundary including Nov 21 Party's Over`(
        isoDate: String,
        expectedPhaseNum: Int,
        expectedDruryName: String,
    ) {
        val result = MidwestRutCalendar.evaluate(LocalDate.parse(isoDate))
        assertFalse(result.isOffSeason)
        assertNotNull(result.phase)
        assertEquals(expectedPhaseNum, result.phase!!.number)
        assertEquals(expectedDruryName, result.phase!!.druryName)
    }

    @Test
    fun `cross-year January date anchors to prior November season peak and supports Jan 31 extension`() {
        val jan10 = MidwestRutCalendar.evaluate(LocalDate.of(2027, 1, 10))
        assertEquals(RutPhase.PHASE_13_THE_GRAND_FINALE, jan10.phase)
        assertEquals(LocalDate.of(2026, 11, 11), jan10.seasonPeakDate)
        assertEquals(60, jan10.daysFromPeakRut)

        // Default ends Jan 15 (+65) -> Jan 25 is off-season
        val jan25Default = MidwestRutCalendar.evaluate(LocalDate.of(2027, 1, 25))
        assertTrue(jan25Default.isOffSeason)

        // Extended late season (+81 -> Jan 31) includes Jan 25 in Phase 13
        val jan25Extended = MidwestRutCalendar.evaluate(
            date = LocalDate.of(2027, 1, 25),
            lateSeasonEndOffsetDays = 81,
        )
        assertFalse(jan25Extended.isOffSeason)
        assertEquals(RutPhase.PHASE_13_THE_GRAND_FINALE, jan25Extended.phase)
        assertEquals(LocalDate.of(2027, 1, 31), jan25Extended.phaseEndDate)
    }

    @Test
    fun `custom peak rut date shifts entire 13-phase schedule accordingly`() {
        val southernPeak = MonthDay.of(11, 20)
        // Nov 20 becomes Lock Down (offset 0); Nov 15 becomes Buck Parade (offset -5)
        val lockDownDay = MidwestRutCalendar.evaluate(LocalDate.of(2026, 11, 20), peakRutDate = southernPeak)
        assertEquals(RutPhase.PHASE_07_LOCK_DOWN, lockDownDay.phase)
        assertEquals(0, lockDownDay.daysFromPeakRut)

        val buckParadeDay = MidwestRutCalendar.evaluate(LocalDate.of(2026, 11, 15), peakRutDate = southernPeak)
        assertEquals(RutPhase.PHASE_06_BUCK_PARADE, buckParadeDay.phase)
    }

    @Test
    fun `off-season summer date returns isOffSeason with countdown to Phase 1`() {
        val july15 = MidwestRutCalendar.evaluate(LocalDate.of(2026, 7, 15))
        assertTrue(july15.isOffSeason)
        assertNull(july15.phase)
        assertEquals(RutPhase.PHASE_01_A_NEW_BEGINNING, july15.nextPhase)
        assertEquals(62, july15.daysUntilNextPhase)
        assertEquals(20.0, july15.amBaselineScore)
    }
}
