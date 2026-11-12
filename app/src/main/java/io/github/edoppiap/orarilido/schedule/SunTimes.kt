package io.github.edoppiap.orarilido.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Alba e tramonto calcolati sul telefono (nessuna rete), con la
 * "sunrise equation" semplificata della NOAA: precisione di un paio di minuti,
 * più che sufficiente per decidere se la mappa va mostrata chiara o scura.
 *
 * Usiamo un punto fisso al centro della linea (non la posizione dell'utente):
 * su 30 km di linea la differenza è di pochi secondi.
 */
object SunTimes {

    /** Punto di riferimento: circa a metà linea (Acilia). */
    private const val LAT = 41.78
    private const val LON = 12.36

    data class Times(val sunrise: Instant, val sunset: Instant)

    fun forDate(date: LocalDate, lat: Double = LAT, lon: Double = LON): Times {
        // Giorno giuliano a mezzanotte UTC della data.
        val julianDate = date.toEpochDay() + 2440587.5
        val n = ceil(julianDate - 2451545.0 + 0.0008)
        // Mezzogiorno solare medio (longitudine est positiva).
        val jStar = n - lon / 360.0
        // Anomalia media del Sole e equazione del centro.
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val c = 1.9148 * sinD(m) + 0.02 * sinD(2 * m) + 0.0003 * sinD(3 * m)
        // Longitudine eclittica e mezzogiorno solare vero.
        val lambda = (m + c + 180.0 + 102.9372).mod(360.0)
        val jTransit = 2451545.0 + jStar + 0.0053 * sinD(m) - 0.0069 * sinD(2 * lambda)
        // Declinazione del Sole e angolo orario dell'alba (−0.833° = rifrazione + raggio del disco).
        val declination = Math.toDegrees(asin(sinD(lambda) * sinD(23.4397)))
        val cosOmega = (sinD(-0.833) - sinD(lat) * sinD(declination)) / (cosD(lat) * cosD(declination))
        val omega = Math.toDegrees(acos(cosOmega.coerceIn(-1.0, 1.0)))
        return Times(
            sunrise = julianToInstant(jTransit - omega / 360.0),
            sunset = julianToInstant(jTransit + omega / 360.0),
        )
    }

    /** true se in quell'istante il Sole è sotto l'orizzonte (a Roma). */
    fun isNight(now: ZonedDateTime): Boolean {
        val t = forDate(now.toLocalDate())
        val instant = now.toInstant()
        return instant.isBefore(t.sunrise) || instant.isAfter(t.sunset)
    }

    private fun julianToInstant(j: Double): Instant =
        Instant.ofEpochMilli(((j - 2440587.5) * 86_400_000.0).toLong())

    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
    private fun cosD(deg: Double) = cos(Math.toRadians(deg))
}
