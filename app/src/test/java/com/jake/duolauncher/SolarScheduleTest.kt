package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SolarScheduleTest {
    private val ny = ZoneId.of("America/New_York")

    @Test fun newYorkEquinoxIsPlausible() {
        val result = solarSchedule(LocalDate.of(2026, 3, 20), 40.7128, -74.006, ny)
        assertEquals(PolarDaylight.NORMAL, result.polar)
        assertTrue(result.sunrise!!.hour in 6..7)
        assertTrue(result.sunset!!.hour in 18..19)
    }

    @Test fun newYorkSummerDayIsLongerThanWinter() {
        fun length(date: LocalDate): Duration = solarSchedule(date, 40.7128, -74.006, ny).let {
            Duration.between(it.sunrise, it.sunset)
        }
        assertTrue(length(LocalDate.of(2026, 6, 21)) > length(LocalDate.of(2026, 12, 21)))
    }

    @Test fun southernHemisphereSeasonsReverse() {
        val zone = ZoneId.of("Australia/Sydney")
        fun length(month: Int) = solarSchedule(LocalDate.of(2026, month, 21), -33.8688, 151.2093, zone).let {
            Duration.between(it.sunrise, it.sunset)
        }
        assertTrue(length(12) > length(6))
    }

    @Test fun leapDateAndTimezoneStayOnRequestedLocalDate() {
        val date = LocalDate.of(2024, 2, 29)
        val result = solarSchedule(date, 35.6762, 139.6503, ZoneId.of("Asia/Tokyo"))
        assertEquals(date, result.sunrise!!.toLocalDate())
        assertEquals(date, result.sunset!!.toLocalDate())
    }

    @Test fun dateLineZoneStaysOnRequestedCivilDate() {
        val date = LocalDate.of(2026, 4, 12)
        val result = solarSchedule(date, 1.8721, -157.4278, ZoneId.of("Pacific/Kiritimati"))
        assertEquals(date, result.sunrise!!.toLocalDate())
        assertEquals(date, result.sunset!!.toLocalDate())
    }

    @Test fun polarDayAndNightAreReported() {
        assertEquals(PolarDaylight.DAY, solarSchedule(LocalDate.of(2026, 6, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen")).polar)
        assertEquals(PolarDaylight.NIGHT, solarSchedule(LocalDate.of(2026, 12, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen")).polar)
    }

    @Test fun invalidCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { solarSchedule(LocalDate.now(), 91.0, 0.0, ZoneId.of("UTC")) }
        assertThrows(IllegalArgumentException::class.java) { solarSchedule(LocalDate.now(), 0.0, Double.NaN, ZoneId.of("UTC")) }
    }

    @Test fun dstStartChangesWallClockOnceWhileInstantStaysSmooth() {
        assertAdjacentSunriseIsSmooth(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), 60)
    }

    @Test fun dstEndChangesWallClockOnceWhileInstantStaysSmooth() {
        assertAdjacentSunriseIsSmooth(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), -60)
    }

    private fun assertAdjacentSunriseIsSmooth(first: LocalDate, second: LocalDate, expectedWallShift: Int) {
        fun sunrise(date: LocalDate) = solarSchedule(date, 40.7128, -74.006, ny).sunrise!!
        val firstRise = sunrise(first); val secondRise = sunrise(second)
        val elapsedMinutes = Duration.between(firstRise.toInstant(), secondRise.toInstant()).toMinutes()
        // The physical event remains close to 24 hours apart. Local wall time intentionally
        // changes by one hour when the UTC offset changes.
        assertTrue(kotlin.math.abs(elapsedMinutes - 24 * 60) < 10)
        val firstMinute = firstRise.toLocalTime().toSecondOfDay() / 60
        val secondMinute = secondRise.toLocalTime().toSecondOfDay() / 60
        assertTrue(kotlin.math.abs((secondMinute - firstMinute) - expectedWallShift) < 10)
    }

    @Test fun wrappedClockIntervalAgreesAcrossZonesAtSameInstant() {
        val instant = Instant.parse("2026-06-21T12:00:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val newYork = ZoneId.of("America/New_York")
        val tokyoResult = solarSchedule(instant.atZone(tokyo).toLocalDate(), 35.6762, 139.6503, tokyo).isDark(instant.atZone(tokyo))
        val mismatchedResult = solarSchedule(instant.atZone(newYork).toLocalDate(), 35.6762, 139.6503, newYork).isDark(instant.atZone(newYork))
        assertEquals(tokyoResult, mismatchedResult)
    }
}
