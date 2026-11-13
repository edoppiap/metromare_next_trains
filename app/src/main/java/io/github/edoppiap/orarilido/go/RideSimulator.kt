package io.github.edoppiap.orarilido.go

import java.time.LocalDateTime

/**
 * Un viaggio in treno FINTO: posizioni lungo il tracciato vero, con i tempi
 * dell'orario. Serve a provare la modalità Go senza salire sul treno
 * (simulatore nella build di prova) e nei test.
 *
 * Il treno finto:
 *   - tra due stazioni impiega i minuti dell'orario;
 *   - in ogni stazione intermedia si ferma un po' (20 s, meno nei tratti brevi),
 *     dentro quei minuti, così arriva puntuale;
 *   - il GPS "balla" di lato di ±[jitterMeters];
 *   - arrivato al capolinea resta fermo [stayAfterSeconds] (per vedere l'uscita da Go).
 */
object RideSimulator {

    fun ride(
        track: LineTrack,
        /** Minuti dal capolinea per stazione, nella direzione del viaggio. */
        minutes: IntArray,
        fromIndex: Int,
        toIndex: Int,
        start: LocalDateTime,
        everySeconds: Long = 5,
        jitterMeters: Double = 10.0,
        stayAfterSeconds: Long = 90,
    ): List<GoSample> {
        val path = if (toIndex > fromIndex) fromIndex..toIndex else fromIndex downTo toIndex
        val along = track.stationAlong

        // Il programma del viaggio: per ogni tratto, quando parte, quando arriva.
        data class Leg(val fromAlong: Double, val toAlong: Double, val departS: Double, val arriveS: Double)
        val legs = path.zipWithNext { i, j ->
            val segStart = (kotlin.math.abs(minutes[i] - minutes[fromIndex]) * 60).toDouble()
            val segEnd = (kotlin.math.abs(minutes[j] - minutes[fromIndex]) * 60).toDouble()
            // Sosta nella stazione di partenza del tratto (non alla prima: si parte subito).
            val dwell = if (i == fromIndex) 0.0 else minOf(20.0, (segEnd - segStart) / 4)
            Leg(along[i], along[j], segStart + dwell, segEnd)
        }
        val totalS = legs.last().arriveS

        fun alongAt(t: Double): Double {
            val leg = legs.firstOrNull { t < it.arriveS } ?: return along[toIndex]
            if (t <= leg.departS) return leg.fromAlong  // fermo in stazione
            val f = (t - leg.departS) / (leg.arriveS - leg.departS)
            return leg.fromAlong + f * (leg.toAlong - leg.fromAlong)
        }

        val samples = mutableListOf<GoSample>()
        var t = 0L
        while (t <= totalS + stayAfterSeconds) {
            val side = if (samples.size % 2 == 0) jitterMeters else -jitterMeters
            samples += GoSample(track.pointBeside(alongAt(t.toDouble()), side), start.plusSeconds(t))
            t += everySeconds
        }
        return samples
    }
}
