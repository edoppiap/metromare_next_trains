package io.github.edoppiap.orarilido.go

import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.Station
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Il tracciato della linea (line.json, da OpenStreetMap) visto come un
 * "righello": ogni punto della linea ha una distanza in metri da Porta
 * San Paolo (0 m) verso Cristoforo Colombo (~28 km).
 *
 * Serve alla modalità Go per rispondere a due domande:
 *   - in che punto della linea sei? ([project]: distanza lungo la linea)
 *   - quanto sei lontano dai binari? (per capire se sei davvero sul treno)
 *
 * Kotlin puro: si testa con JUnit sul PC.
 */
class LineTrack(points: List<GeoPoint>, stations: List<Station>) {

    /** Dove ti trovi rispetto alla linea. */
    data class Projection(
        /** Metri lungo la linea da Porta San Paolo (cresce andando verso Ostia). */
        val alongMeters: Double,
        /** Distanza in metri dal binario più vicino. */
        val offsetMeters: Double,
    )

    // Trucco per fare i conti in metri senza trigonometria a ogni passo:
    // su una zona piccola come la linea (30 km) la Terra si può trattare
    // come piatta. Un grado di latitudine vale sempre ~111,2 km; uno di
    // longitudine vale meno, tanto meno quanto più si è a nord (× cos lat).
    private val lat0 = points.first().lat
    private val lon0 = points.first().lon
    private val metersPerDegLat = 111_195.0
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat0))

    private val xs = DoubleArray(points.size) { (points[it].lon - lon0) * metersPerDegLon }
    private val ys = DoubleArray(points.size) { (points[it].lat - lat0) * metersPerDegLat }

    /** cumulative[i] = metri lungo la linea dal primo punto fino al punto i. */
    private val cumulative = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    /** Lunghezza totale della linea in metri. */
    val lengthMeters: Double get() = cumulative.last()

    /** Posizione di ogni stazione lungo la linea (stesso ordine di [stations]). */
    val stationAlong: DoubleArray = DoubleArray(stations.size) {
        project(GeoPoint(stations[it].lat, stations[it].lon)).alongMeters
    }

    /**
     * Il punto della linea più vicino a [p]: per ogni tratto tra due punti
     * consecutivi si cerca il punto più vicino (proiezione su un segmento) e
     * si tiene il migliore. 595 punti: pochi microsecondi.
     */
    fun project(p: GeoPoint): Projection {
        val px = (p.lon - lon0) * metersPerDegLon
        val py = (p.lat - lat0) * metersPerDegLat
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            // t = dove cade la proiezione sul segmento: 0 = inizio, 1 = fine.
            val t = if (len2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy))
            if (d < bestDist) {
                bestDist = d
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return Projection(bestAlong, bestDist)
    }

    /**
     * Un punto spostato di [sideMeters] di lato rispetto ai binari (a destra
     * andando verso Ostia se positivo). Serve al simulatore e ai test: un GPS
     * vero non cade mai esattamente sul binario.
     */
    fun pointBeside(alongMeters: Double, sideMeters: Double): GeoPoint {
        val p = pointAt(alongMeters)
        if (sideMeters == 0.0) return p
        // Direzione della linea lì intorno (20 m prima e dopo), poi la perpendicolare.
        val a = pointAt(alongMeters - 20); val b = pointAt(alongMeters + 20)
        val dx = (b.lon - a.lon) * metersPerDegLon; val dy = (b.lat - a.lat) * metersPerDegLat
        val len = hypot(dx, dy)
        if (len == 0.0) return p
        return GeoPoint(p.lat - dx / len * sideMeters / metersPerDegLat, p.lon + dy / len * sideMeters / metersPerDegLon)
    }

    /** Il contrario di [project]: il punto della linea a [alongMeters] da Porta San Paolo. */
    fun pointAt(alongMeters: Double): GeoPoint {
        val a = alongMeters.coerceIn(0.0, lengthMeters)
        // Primo punto oltre la distanza cercata (ricerca binaria).
        var i = cumulative.binarySearch(a).let { if (it >= 0) it else -it - 1 }.coerceIn(1, xs.size - 1)
        val segment = cumulative[i] - cumulative[i - 1]
        val t = if (segment == 0.0) 0.0 else (a - cumulative[i - 1]) / segment
        val x = xs[i - 1] + t * (xs[i] - xs[i - 1])
        val y = ys[i - 1] + t * (ys[i] - ys[i - 1])
        return GeoPoint(lat0 + y / metersPerDegLat, lon0 + x / metersPerDegLon)
    }

    companion object {
        /** Legge il testo di line.json: {"points": [[lat, lon], ...]} */
        fun parsePoints(json: String): List<GeoPoint> =
            Json.parseToJsonElement(json).jsonObject.getValue("points").jsonArray.map { p ->
                val pair = p.jsonArray
                GeoPoint(pair[0].jsonPrimitive.double, pair[1].jsonPrimitive.double)
            }
    }
}
