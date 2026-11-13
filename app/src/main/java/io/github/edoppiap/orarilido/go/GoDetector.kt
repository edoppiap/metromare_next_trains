package io.github.edoppiap.orarilido.go

import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.Station
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/** Una posizione GPS con l'ora in cui è stata presa. Solo in memoria, mai salvata. */
data class GoSample(val point: GeoPoint, val time: LocalDateTime)

/** Arrivo stimato a una stazione davanti a te. */
data class GoArrival(val stationIndex: Int, val station: Station, val time: LocalDateTime)

/** Cosa pensa la modalità Go in questo momento. */
sealed interface GoState {
    /** Non sei sul treno (o non ancora riconosciuto). */
    data object Off : GoState

    /** Sembra un treno: ancora qualche posizione per esserne sicuri ("Sei in treno?…"). */
    data class Detecting(val direction: Direction) : GoState

    /** Sei sul treno: dove sei e quando arrivi alle stazioni davanti. */
    data class OnTrain(
        val direction: Direction,
        /** Il tuo punto sulla linea (metri da Porta San Paolo). */
        val alongMeters: Double,
        /** Il tuo punto "agganciato" ai binari: per il pallino sulla mappa. */
        val pointOnLine: GeoPoint,
        /** Stazioni davanti, nell'ordine in cui le incontri. Vuota = sei al capolinea. */
        val ahead: List<GoArrival>,
    ) : GoState
}

/**
 * Riconosce se sei sul treno, guardando le posizioni GPS una alla volta
 * (chiamare [update] a ogni nuova posizione).
 *
 * Come ragiona:
 *  1. ogni posizione viene "proiettata" sulla linea ([LineTrack]): in che
 *     punto della linea sei e quanto sei lontano dai binari;
 *  2. tra due posizioni consecutive si calcola la velocità LUNGO LA LINEA.
 *     Un "passo da treno" è: entrambe vicine ai binari, più di 25 km/h, e lo
 *     spostamento è quasi tutto parallelo alla linea (un'auto che attraversa
 *     i binari si sposta di lato, non lungo);
 *  3. dopo [STEPS_TO_CONFIRM] passi da treno nello stesso verso: sei sul
 *     treno. La direzione è il verso dello spostamento;
 *  4. gli arrivi alle stazioni davanti si calcolano dai tempi di percorrenza
 *     dell'orario ("da Vitinia ad Acilia 8 min"), partendo da DOVE SEI ADESSO.
 *     Così il ritardo è già compreso: se il treno è in ritardo, sei in
 *     ritardo anche tu, e gli arrivi slittano da soli. Non serve sapere
 *     quale corsa stai facendo.
 *
 * Kotlin puro, senza Android: si testa con JUnit simulando viaggi.
 */
class GoDetector(
    private val track: LineTrack,
    private val stations: List<Station>,
    /** Minuti dal capolinea per stazione, per direzione (dall'orario). */
    private val minutesFromTerminus: (Direction) -> IntArray,
) {
    var state: GoState = GoState.Off
        private set

    /** Una posizione già proiettata sulla linea. */
    private data class Fix(val sample: GoSample, val projection: LineTrack.Projection)

    private var last: Fix? = null
    private var streak = 0                          // passi da treno consecutivi
    private var streakDirection = Direction.TO_ROMA
    private var lastTrainStep: LocalDateTime? = null
    private var stoppedSince: LocalDateTime? = null  // in Go: da quando sei fermo
    private var offLineCount = 0                     // in Go: posizioni di fila lontane dai binari

    /** Si riparte da zero, dimenticando anche l'ultima posizione (es. inizio di una simulazione). */
    fun reset() {
        resetDetection()
        last = null
    }

    /** Da chiamare a ogni nuova posizione GPS. Restituisce il nuovo stato. */
    fun update(sample: GoSample): GoState {
        val fix = Fix(sample, track.project(sample.point))
        val previous = last
        last = fix
        val seconds = previous?.let { Duration.between(it.sample.time, sample.time).toMillis() / 1000.0 }

        // Prima posizione, ore sbagliate o buco troppo lungo (es. app in
        // background): non si può calcolare una velocità affidabile.
        if (previous == null || seconds == null || seconds <= 0 || seconds > MAX_GAP_S) {
            if (state !is GoState.OnTrain) resetDetection()
            else updateOnTrain(fix, speed = null)  // in Go si aggiorna comunque la posizione
            return state
        }

        val dAlong = fix.projection.alongMeters - previous.projection.alongMeters
        val alongSpeed = abs(dAlong) / seconds
        val straight = Geo.distanceMeters(previous.sample.point, sample.point)
        val direction = if (dAlong > 0) Direction.TO_OSTIA else Direction.TO_ROMA

        val trainStep = fix.projection.offsetMeters <= ON_LINE_M &&
            previous.projection.offsetMeters <= ON_LINE_M &&
            alongSpeed in MIN_TRAIN_SPEED_MPS..MAX_TRAIN_SPEED_MPS &&
            abs(dAlong) >= PARALLEL_RATIO * straight

        when (state) {
            is GoState.OnTrain -> updateOnTrain(fix, alongSpeed)
            else -> detect(fix, trainStep, direction)
        }
        return state
    }

    /** Fase di riconoscimento (Off o Detecting). */
    private fun detect(fix: Fix, trainStep: Boolean, direction: Direction) {
        val time = fix.sample.time
        when {
            trainStep && streak > 0 && direction == streakDirection -> streak++
            trainStep -> { streak = 1; streakDirection = direction }
            // Fermo in stazione ma sempre sui binari: il conteggio resta in
            // sospeso (non sale, non si azzera), per un po'.
            fix.projection.offsetMeters <= ON_LINE_M &&
                lastTrainStep?.let { Duration.between(it, time).seconds <= MAX_DETECT_PAUSE_S } == true -> {}
            else -> streak = 0
        }
        if (trainStep) lastTrainStep = time

        state = when {
            streak >= STEPS_TO_CONFIRM -> {
                stoppedSince = null
                offLineCount = 0
                onTrain(time, fix.projection.alongMeters, streakDirection)
            }
            streak > 0 -> GoState.Detecting(streakDirection)
            else -> GoState.Off
        }
    }

    /** Già sul treno: aggiorna posizione e arrivi, o esce da Go. */
    private fun updateOnTrain(fix: Fix, speed: Double?) {
        val current = state as GoState.OnTrain
        val time = fix.sample.time

        // Lontano dai binari per due posizioni di fila: sei sceso (o eri in auto).
        if (fix.projection.offsetMeters > OFF_LINE_M) {
            offLineCount++
            if (offLineCount >= OFF_LINE_FIXES_TO_EXIT) resetDetection()
            return  // una posizione sola lontana può essere un errore del GPS: si ignora
        }
        offLineCount = 0

        // Fermo da troppo: sei sceso e stai camminando in stazione (o il
        // treno è fermo a lungo). Al capolinea d'arrivo si esce prima.
        if (speed != null && speed < STOPPED_SPEED_MPS) {
            val since = stoppedSince ?: time.also { stoppedSince = it }
            val stopped = Duration.between(since, time).seconds
            val atTerminus = current.ahead.isEmpty() || isNearArrivalTerminus(fix, current.direction)
            if (stopped >= STOPPED_EXIT_S || (atTerminus && stopped >= TERMINUS_EXIT_S)) {
                resetDetection()
                return
            }
        } else if (speed != null) {
            stoppedSince = null
        }

        // Il treno non torna indietro: se la posizione arretra è rumore del
        // GPS, e si tiene quella di prima (se no la "prossima fermata" potrebbe
        // sfarfallare avanti e indietro).
        val forward = when (current.direction) {
            Direction.TO_OSTIA -> fix.projection.alongMeters - current.alongMeters
            Direction.TO_ROMA -> current.alongMeters - fix.projection.alongMeters
        }
        val along = if (forward < 0) current.alongMeters else fix.projection.alongMeters
        state = onTrain(time, along, current.direction)
    }

    private fun isNearArrivalTerminus(fix: Fix, direction: Direction): Boolean {
        val terminus = if (direction == Direction.TO_OSTIA) track.stationAlong.last() else track.stationAlong.first()
        return abs(fix.projection.alongMeters - terminus) <= TERMINUS_RADIUS_M
    }

    /** Costruisce lo stato "sul treno", con gli arrivi stimati alle stazioni davanti. */
    private fun onTrain(time: LocalDateTime, along: Double, direction: Direction): GoState.OnTrain {
        val minutes = minutesFromTerminus(direction)
        val hereMinutes = minutesAt(along, minutes)
        val indices = when (direction) {
            Direction.TO_OSTIA -> stations.indices.filter { track.stationAlong[it] > along + AT_STATION_M }
            Direction.TO_ROMA -> stations.indices.reversed().filter { track.stationAlong[it] < along - AT_STATION_M }
        }
        val ahead = indices.map { i ->
            val secondsToGo = ((minutes[i] - hereMinutes) * 60).roundToLong().coerceAtLeast(0)
            GoArrival(i, stations[i], time.plusSeconds(secondsToGo))
        }
        return GoState.OnTrain(direction, along, track.pointAt(along), ahead)
    }

    /**
     * "Minuto dell'orario" corrispondente al punto [along] della linea:
     * tra due stazioni si interpola in proporzione alla distanza (a metà
     * strada tra Vitinia, 13', e Casal Bernocchi, 17' → 15').
     */
    private fun minutesAt(along: Double, minutes: IntArray): Double {
        val a = track.stationAlong
        if (along <= a.first()) return minutes.first().toDouble()
        if (along >= a.last()) return minutes.last().toDouble()
        val i = (0 until a.size - 1).first { along <= a[it + 1] }
        val fraction = (along - a[i]) / (a[i + 1] - a[i])
        return minutes[i] + fraction * (minutes[i + 1] - minutes[i])
    }

    private fun resetDetection() {
        state = GoState.Off
        streak = 0
        lastTrainStep = null
        stoppedSince = null
        offLineCount = 0
    }

    companion object {
        // Soglie: valori di partenza ragionevoli, da affinare sul treno vero.
        /** Entro questa distanza dai binari sei "sulla linea" (errore GPS tipico 5–30 m). */
        const val ON_LINE_M = 50.0
        /** Oltre questa distanza, in Go, sei "fuori dalla linea". */
        const val OFF_LINE_M = 150.0
        const val OFF_LINE_FIXES_TO_EXIT = 2
        /** 25 km/h: più di una bici, meno del treno in marcia. */
        const val MIN_TRAIN_SPEED_MPS = 25 / 3.6
        /** 150 km/h: oltre è un salto del GPS, non un treno (la linea fa al massimo ~90). */
        const val MAX_TRAIN_SPEED_MPS = 150 / 3.6
        /** Quanto dello spostamento deve essere parallelo alla linea. */
        const val PARALLEL_RATIO = 0.7
        const val STEPS_TO_CONFIRM = 3
        /** Durante il riconoscimento, una fermata in stazione può durare fino a tanto. */
        const val MAX_DETECT_PAUSE_S = 90L
        /** Oltre questo intervallo tra due posizioni non si calcola la velocità. */
        const val MAX_GAP_S = 60.0
        /** Sotto ~5 km/h sei "fermo" (o cammini). */
        const val STOPPED_SPEED_MPS = 1.5
        /** Fermo per 5 minuti (non al capolinea): esci da Go. */
        const val STOPPED_EXIT_S = 300L
        /** Al capolinea d'arrivo basta 1 minuto fermo. */
        const val TERMINUS_EXIT_S = 60L
        const val TERMINUS_RADIUS_M = 200.0
        /** Una stazione entro questa distanza è "dove sei", non "davanti". */
        const val AT_STATION_M = 30.0
    }
}
