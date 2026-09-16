package com.jake.duolauncher

import java.time.*
import kotlin.math.*

enum class PolarDaylight { NORMAL, DAY, NIGHT }

data class SolarSchedule(val sunrise: ZonedDateTime?, val sunset: ZonedDateTime?, val polar: PolarDaylight) {
    fun isDark(at: ZonedDateTime): Boolean = when (polar) {
        PolarDaylight.DAY -> false
        PolarDaylight.NIGHT -> true
        PolarDaylight.NORMAL -> {
            val rise = requireNotNull(sunrise); val set = requireNotNull(sunset)
            val daylight = if (set.isAfter(rise)) !at.isBefore(rise) && at.isBefore(set)
                else !at.isBefore(rise) || at.isBefore(set)
            !daylight
        }
    }
}

/** NOAA fractional-year approximation, using the standard 90.833° apparent sunrise zenith. */
fun solarSchedule(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SolarSchedule {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
    val days = if (date.isLeapYear) 366.0 else 365.0
    val gamma = 2.0 * PI / days * (date.dayOfYear - 1)
    val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
        0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
    val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
        0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
        0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
    val latitudeRadians = Math.toRadians(latitude)
    val hourAngleCos = cos(Math.toRadians(90.833)) / (cos(latitudeRadians) * cos(declination)) -
        tan(latitudeRadians) * tan(declination)
    if (hourAngleCos < -1.0) return SolarSchedule(null, null, PolarDaylight.DAY)
    if (hourAngleCos > 1.0) return SolarSchedule(null, null, PolarDaylight.NIGHT)
    val hourAngle = Math.toDegrees(acos(hourAngleCos))
    val noon = date.atTime(12, 0).atZone(zone)
    val offsetMinutes = noon.offset.totalSeconds / 60.0
    val solarNoonMinutes = 720.0 - 4.0 * longitude - equation + offsetMinutes
    // These are local wall-clock minutes. Resolve the finished LocalDateTime in the zone so a
    // DST transition between midnight and sunrise does not add or subtract another hour.
    fun at(minutes: Double): ZonedDateTime {
        val localMinutes = ((minutes % 1440.0) + 1440.0) % 1440.0
        return date.atStartOfDay().plusSeconds((localMinutes * 60.0).roundToLong()).atZone(zone)
    }
    return SolarSchedule(at(solarNoonMinutes - 4.0 * hourAngle), at(solarNoonMinutes + 4.0 * hourAngle), PolarDaylight.NORMAL)
}
