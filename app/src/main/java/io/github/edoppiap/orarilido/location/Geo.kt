package io.github.edoppiap.orarilido.location

import io.github.edoppiap.orarilido.schedule.Station
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Un punto sulla Terra. Vive solo in memoria: per privacy non viene mai salvato. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Calcoli geografici, in Kotlin puro (testabili con JUnit).
 * Distanze "in linea d'aria" con la formula dell'haversine.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Velocità a piedi usata per i cerchi "5/10/15 minuti": ~4,8 km/h. */
    const val WALK_METERS_PER_MINUTE = 80.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun distanceMeters(from: GeoPoint, station: Station): Double =
        distanceMeters(from, GeoPoint(station.lat, station.lon))

    /** Stazioni ordinate dalla più vicina alla più lontana. */
    fun sortByDistance(stations: List<Station>, from: GeoPoint): List<Station> =
        stations.sortedBy { distanceMeters(from, it) }

    /** "80 m", "350 m", "1,2 km", "15 km" */
    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"   // arrotondato ai 10 m
        meters < 10_000 -> "%.1f km".format(java.util.Locale.ITALIAN, meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
}
