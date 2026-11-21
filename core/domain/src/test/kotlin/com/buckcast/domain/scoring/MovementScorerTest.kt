package com.buckcast.domain.scoring

import com.buckcast.domain.rut.MidwestRutCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MovementScorerTest {

    private val peakRut = MidwestRutCalendar.evaluate(LocalDate.of(2026, 11, 8)) // Buck Parade
    private val octLull = MidwestRutCalendar.evaluate(LocalDate.of(2026, 10, 15)) // October Lull

    @Test
    fun `factor weights sum strictly to 1_0 and overallScore equals sum of weighted contributions`() {
        assertEquals(1.0, MovementFactor.entries.sumOf { it.weight }, 1e-9)

        val report = MovementScorer.evaluateSession(baselineInput())
        assertEquals(5, report.factors.size)
        assertEquals(
            report.factors.sumOf { it.weightedContribution },
            report.overallScore,
            1e-6,
        )
    }

    @Test
    fun `barometer factor rewards BOTH rapid pre-front drops and post-front rising pressure`() {
        val stagnantLow = baselineInput(baroInHg = 29.55, trendInHg = 0.0)
        val preFrontDrop = baselineInput(baroInHg = 29.85, trendInHg = -0.07)
        val postFrontRise = baselineInput(baroInHg = 30.12, trendInHg = 0.06)

        val lowScore = scoreOfFactor(stagnantLow, MovementFactor.BAROMETRIC_PRESSURE)
        val dropScore = scoreOfFactor(preFrontDrop, MovementFactor.BAROMETRIC_PRESSURE)
        val riseScore = scoreOfFactor(postFrontRise, MovementFactor.BAROMETRIC_PRESSURE)

        assertTrue(dropScore >= 85.0, "Rapid pre-front drop should score >= 85, was $dropScore")
        assertTrue(riseScore >= 85.0, "Post-front rise should score >= 85, was $riseScore")
        assertTrue(dropScore > lowScore + 40.0)
        assertTrue(riseScore > lowScore + 40.0)
    }

    @Test
    fun `temperature factor rewards cold front drops and penalizes unseasonable heat`() {
        val coldFront = baselineInput(tempDegF = 34.0, sevenDayMeanDegF = 49.0)
        val neutral = baselineInput(tempDegF = 49.0, sevenDayMeanDegF = 49.0)
        val warmFront = baselineInput(tempDegF = 64.0, sevenDayMeanDegF = 49.0)
        val hotEarly = baselineInput(tempDegF = 84.0, sevenDayMeanDegF = 72.0)

        val coldScore = scoreOfFactor(coldFront, MovementFactor.TEMPERATURE_DELTA)
        val neutralScore = scoreOfFactor(neutral, MovementFactor.TEMPERATURE_DELTA)
        val warmScore = scoreOfFactor(warmFront, MovementFactor.TEMPERATURE_DELTA)
        val hotScore = scoreOfFactor(hotEarly, MovementFactor.TEMPERATURE_DELTA)

        assertTrue(coldScore > neutralScore)
        assertTrue(neutralScore > warmScore)
        assertTrue(hotScore < 20.0, "Hot 84°F weather should heavily suppress score, was $hotScore")
    }

    @Test
    fun `wind and precipitation factors respond realistically to gusts, snow, post-rain clearing, and storms`() {
        val sweetWind = scoreOfFactor(baselineInput(windMph = 7.0, gustMph = 9.0), MovementFactor.WIND)
        val gustyWind = scoreOfFactor(baselineInput(windMph = 12.0, gustMph = 26.0), MovementFactor.WIND)
        val galeWind = scoreOfFactor(baselineInput(windMph = 32.0, gustMph = 42.0), MovementFactor.WIND)
        assertTrue(sweetWind > gustyWind)
        assertTrue(gustyWind > galeWind)

        val clearedRain = scoreOfFactor(
            baselineInput(precipType = PrecipType.NONE, precipRate = 0.0, hoursSincePrecip = 1.0),
            MovementFactor.PRECIPITATION,
        )
        val lightSnow = scoreOfFactor(
            baselineInput(precipType = PrecipType.SNOW, precipRate = 0.04),
            MovementFactor.PRECIPITATION,
        )
        val heavyRain = scoreOfFactor(
            baselineInput(precipType = PrecipType.HEAVY_RAIN, precipRate = 0.35),
            MovementFactor.PRECIPITATION,
        )
        assertTrue(clearedRain > 90.0)
        assertTrue(lightSnow > 85.0)
        assertTrue(heavyRain < 20.0)
    }

    @Test
    fun `four-tier rating thresholds map SLOW, OKAY, GOOD, GREAT cleanly and evaluateDay compares AM vs PM`() {
        assertEquals(MovementRating.SLOW, MovementRating.fromScore(39.9))
        assertEquals(MovementRating.OKAY, MovementRating.fromScore(40.0))
        assertEquals(MovementRating.GOOD, MovementRating.fromScore(60.0))
        assertEquals(MovementRating.GREAT, MovementRating.fromScore(80.0))

        val greatMorning = MovementScorer.evaluateSession(
            baselineInput(
                session = HuntSession.AM,
                baroInHg = 30.28,
                trendInHg = 0.07,
                tempDegF = 30.0,
                sevenDayMeanDegF = 46.0,
                rut = peakRut,
                windMph = 6.0,
                precipType = PrecipType.SNOW,
                precipRate = 0.03,
            ),
        )
        assertEquals(MovementRating.GREAT, greatMorning.rating)

        val slowAfternoon = MovementScorer.evaluateSession(
            baselineInput(
                session = HuntSession.PM,
                baroInHg = 29.55,
                trendInHg = 0.0,
                tempDegF = 80.0,
                sevenDayMeanDegF = 65.0,
                rut = octLull,
                windMph = 28.0,
                gustMph = 38.0,
                precipType = PrecipType.HEAVY_RAIN,
                precipRate = 0.40,
            ),
        )
        assertEquals(MovementRating.SLOW, slowAfternoon.rating)

        val dayForecast = MovementScorer.evaluateDay(
            amInput = baselineInput(session = HuntSession.AM, rut = octLull),
            pmInput = baselineInput(session = HuntSession.PM, rut = octLull),
        )
        // October Lull has stronger PM food movement than AM
        assertEquals(HuntSession.PM, dayForecast.recommendedSession)
        assertTrue(dayForecast.pmReport.overallScore > dayForecast.amReport.overallScore)
    }

    private fun scoreOfFactor(input: SessionWeatherInput, factor: MovementFactor): Double =
        MovementScorer.evaluateSession(input).factors.first { it.factor == factor }.rawScore

    private fun baselineInput(
        session: HuntSession = HuntSession.AM,
        baroInHg: Double = 30.00,
        trendInHg: Double = 0.0,
        tempDegF: Double = 42.0,
        sevenDayMeanDegF: Double = 44.0,
        rut: com.buckcast.domain.rut.RutDayAssessment = peakRut,
        windMph: Double = 7.0,
        gustMph: Double? = null,
        precipType: PrecipType = PrecipType.NONE,
        precipRate: Double = 0.0,
        hoursSincePrecip: Double? = null,
    ) = SessionWeatherInput(
        session = session,
        barometricPressureInHg = baroInHg,
        pressureTrend3hInHg = trendInHg,
        tempDegF = tempDegF,
        sevenDayMeanTempDegF = sevenDayMeanDegF,
        rutAssessment = rut,
        windSpeedMph = windMph,
        windGustMph = gustMph,
        precipInchesPerHr = precipRate,
        precipType = precipType,
        hoursSincePrecipEnded = hoursSincePrecip,
    )
}
