package io.github.edoppiap.orarilido.go

import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.Station
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Una posizione GPS con l'ora in cui è stata presa. Solo in memoria, mai salvata.
 * [accuracy] = raggio di incertezza in metri dichiarato dal GPS (null = ignoto).
 */
data class GoSample(val point: GeoPoint, val time: LocalDateTime, val accuracy: Double? = null)

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
        /** Il tuo punto sulla linea (metri da Porta San Paolo), all'ora [fixTime]. */
        val alongMeters: Double,
        /** Ora dell'ultima posizione usata: da qui si stima dove sei adesso ([GoDetector.estimateAlong]). */
        val fixTime: LocalDateTime,
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
    /**
     * true = Go parte da solo quando riconosce il treno. Nell'app è false
     * (dal 15/11): sul treno vero il GPS aggiorna a scatti e il riconoscimento
     * non partiva, quindi Go si avvia col pulsante "Vai" ([start]). Il
     * riconoscimento resta qui (e nei test) per eventuali usi futuri.
     */
    private val autoStart: Boolean = true,
    /**
     * Può esserci un treno lì, a quell'ora? (indice della stazione più vicina,
     * verso, ora). La sera, passato l'ultimo treno, la strada che corre accanto
     * ai binari sembrerebbe un treno: così sul bus sostitutivo Go non parte.
     */
    private val trainPossible: (Int, Direction, LocalDateTime) -> Boolean = { _, _, _ -> true },
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
    private var startAlong = 0.0                     // in Go avviato a mano: dove hai premuto "Vai"
    private var directionChecked = true              // in Go avviato a mano: false finché il movimento non conferma la direzione

    /** Si riparte da zero, dimenticando anche l'ultima posizione (es. inizio di una simulazione). */
    fun reset() {
        resetDetection()
        last = null
    }

    /**
     * Pulsante "Vai": sei sul treno (o stai per salirci) verso [direction].
     * [sample] = la tua posizione adesso. Se poi il treno si muove nel verso
     * opposto, la direzione si corregge da sola (vedi [updateOnTrain]).
     */
    fun start(direction: Direction, sample: GoSample) {
        resetDetection()
        val projection = track.project(sample.point)
        last = Fix(sample, projection)
        startAlong = projection.alongMeters
        directionChecked = false
        state = onTrain(sample.time, projection.alongMeters, direction)
    }

    /** Cambio di direzione a mano (selettore in Go): stessa posizione, arrivi ricalcolati. */
    fun setDirection(direction: Direction) {
        val s = state as? GoState.OnTrain ?: return
        directionChecked = true  // scelta tua: la correzione automatica non la ribalta
        if (s.direction == direction) return
        state = onTrain(s.fixTime, s.alongMeters, direction)
    }

    /** Esci da Go (pulsante X): si torna spenti, ricordando l'ultima posizione. */
    fun stop() = resetDetection()

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
            abs(dAlong) >= PARALLEL_RATIO * straight &&
            // Si chiede all'orario solo per i passi che sembrano da treno.
            trainPossible(nearestStation(fix.projection.alongMeters), direction, sample.time)

        when {
            state is GoState.OnTrain -> updateOnTrain(fix, alongSpeed)
            autoStart -> detect(fix, trainStep, direction)
            // Avvio solo col pulsante: fuori da Go si ricorda solo l'ultima posizione.
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
        var current = state as GoState.OnTrain
        val time = fix.sample.time
        // Incertezza della posizione: quella dichiarata dal GPS, ma mai meno di 20 m.
        val accuracy = maxOf(fix.sample.accuracy ?: MIN_ACCURACY_M, MIN_ACCURACY_M)

        // Lontano dai binari per due posizioni di fila: sei sceso (o eri in auto).
        // Si toglie l'incertezza: una posizione da celle telefoniche "a 300 m,
        // ± 400 m" può benissimo essere sul treno.
        if (fix.projection.offsetMeters - accuracy > OFF_LINE_M) {
            offLineCount++
            if (offLineCount >= OFF_LINE_FIXES_TO_EXIT) resetDetection()
            return  // una posizione sola lontana può essere un errore del GPS: si ignora
        }
        offLineCount = 0

        // Avviato col pulsante: la direzione era una scommessa. Se una posizione
        // precisa mostra che ti sei mosso di parecchio nel verso opposto, si gira.
        if (!directionChecked && accuracy <= ON_LINE_M) {
            val moved = fix.projection.alongMeters - startAlong
            if (abs(moved) >= DIRECTION_CHECK_M) {
                directionChecked = true
                val real = if (moved > 0) Direction.TO_OSTIA else Direction.TO_ROMA
                if (real != current.direction) current = onTrain(time, fix.projection.alongMeters, real)
            }
        }

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

        // Dove sei: si parte dalla STIMA (dove dovresti essere andando coi tempi
        // dell'orario dall'ultima posizione) e la si sposta il minimo necessario
        // per farla stare dentro l'incertezza della posizione nuova. Con un GPS
        // preciso vince il GPS; con uno impreciso (in treno capita spesso) si
        // tiene la stima, finché il GPS non dice chiaramente il contrario.
        val estimate = estimateAlong(current, time)
        val corrected = estimate.coerceIn(fix.projection.alongMeters - accuracy, fix.projection.alongMeters + accuracy)

        // Il treno non torna indietro: se la posizione arretra è rumore del
        // GPS, e si tiene quella di prima (se no la "prossima fermata" potrebbe
        // sfarfallare avanti e indietro).
        val forward = when (current.direction) {
            Direction.TO_OSTIA -> corrected - current.alongMeters
            Direction.TO_ROMA -> current.alongMeters - corrected
        }
        val along = if (forward < 0) current.alongMeters else corrected
        state = onTrain(time, along, current.direction)
    }

    /**
     * Dove dovresti essere ADESSO ([now]), anche se il GPS tace da un po':
     * dall'ultima posizione si va avanti coi tempi dell'orario, ma non oltre
     * la prossima stazione (lì il treno si ferma: meglio aspettarlo lì che
     * "passarla" senza saperlo). Serve a muovere il pallino in modo fluido e a
     * dare un punto di partenza a ogni nuova posizione. null = non sei in Go.
     */
    fun estimateAlong(now: LocalDateTime): Double? =
        (state as? GoState.OnTrain)?.let { estimateAlong(it, now) }

    private fun estimateAlong(s: GoState.OnTrain, now: LocalDateTime): Double {
        val elapsedMin = (Duration.between(s.fixTime, now).toMillis() / 60_000.0).coerceIn(0.0, MAX_ESTIMATE_MIN)
        if (elapsedMin == 0.0) return s.alongMeters
        val minutes = minutesFromTerminus(s.direction)
        val target = minutesAt(s.alongMeters, minutes) + elapsedMin
        // La prossima stazione davanti: oltre non si va.
        val a = track.stationAlong
        val cap = when (s.direction) {
            Direction.TO_OSTIA -> a.firstOrNull { it > s.alongMeters + AT_STATION_M } ?: a.last()
            Direction.TO_ROMA -> a.lastOrNull { it < s.alongMeters - AT_STATION_M } ?: a.first()
        }
        if (minutesAt(cap, minutes) <= target) return cap
        // Tra te e la stazione i minuti crescono con la strada fatta: si cerca
        // il punto giusto dimezzando l'intervallo (40 passi: precisione al millimetro).
        var lo = s.alongMeters  // qui i minuti sono ancora meno del necessario
        var hi = cap            // qui sono già di più
        repeat(40) {
            val mid = (lo + hi) / 2
            if (minutesAt(mid, minutes) < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Indice della stazione più vicina al punto [along] della linea. */
    private fun nearestStation(along: Double): Int =
        track.stationAlong.indices.minBy { abs(track.stationAlong[it] - along) }

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
        return GoState.OnTrain(direction, along, time, track.pointAt(along), ahead)
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
        directionChecked = true
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
        /** Incertezza minima di una posizione (anche se il GPS dice "5 m"). */
        const val MIN_ACCURACY_M = 20.0
        /** Avvio col pulsante: dopo 200 m percorsi si sa davvero in che verso vai. */
        const val DIRECTION_CHECK_M = 200.0
        /** La stima va avanti al massimo per 10 minuti senza posizioni nuove. */
        const val MAX_ESTIMATE_MIN = 10.0
    }
}
