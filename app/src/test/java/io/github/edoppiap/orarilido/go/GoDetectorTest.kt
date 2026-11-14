package io.github.edoppiap.orarilido.go

import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.ScheduleParser
import io.github.edoppiap.orarilido.schedule.TimetableScheduleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Modalità Go "da casa": si simulano viaggi sul tracciato VERO (line.json)
 * con i tempi dell'orario VERO (schedule.json), generando una posizione
 * finta ogni 5 secondi, e si controlla cosa capisce il riconoscitore.
 *
 * Posizioni finte solo lungo la linea o in punti inventati vicino ad essa
 * (nessuna posizione reale di nessuno).
 */
class GoDetectorTest {

    private val provider = TimetableScheduleProvider(
        ScheduleParser.parse(File("src/main/assets/schedule.json").readText())
    )
    private val stations = provider.stations
    private val track = LineTrack(LineTrack.parsePoints(File("src/main/assets/line.json").readText()), stations)

    private fun newDetector() = GoDetector(track, stations, provider::minutesFromTerminus)

    private fun idx(id: String) = stations.indexOfFirst { it.id == id }
    private fun along(id: String) = track.stationAlong[idx(id)]

    // --- Generatori di posizioni finte ---------------------------------

    /**
     * Un punto a [along] metri sulla linea, spostato di lato di [side] metri
     * (perpendicolare ai binari): simula l'errore del GPS o una strada parallela.
     */
    private fun pointNear(along: Double, side: Double = 0.0): GeoPoint {
        val p = track.pointAt(along)
        if (side == 0.0) return p
        // Direzione della linea lì vicino, poi la perpendicolare.
        val a = track.pointAt(along - 20); val b = track.pointAt(along + 20)
        val mLat = 111_195.0; val mLon = mLat * Math.cos(Math.toRadians(p.lat))
        val dx = (b.lon - a.lon) * mLon; val dy = (b.lat - a.lat) * mLat
        val len = sqrt(dx * dx + dy * dy)
        val nx = -dy / len; val ny = dx / len
        return GeoPoint(p.lat + ny * side / mLat, p.lon + nx * side / mLon)
    }

    /**
     * Un viaggio in treno da [fromId] a [toId] partendo alle [start], con i
     * tempi dell'orario (velocità costante tra due stazioni), una posizione
     * ogni [everySeconds]. Il GPS "balla" di lato di ±[jitter] metri.
     */
    private fun trainRide(
        fromId: String, toId: String, start: LocalDateTime,
        everySeconds: Long = 5, jitter: Double = 12.0,
    ): List<GoSample> {
        val dir = if (idx(toId) > idx(fromId)) Direction.TO_OSTIA else Direction.TO_ROMA
        val minutes = provider.minutesFromTerminus(dir)
        val path = if (dir == Direction.TO_OSTIA) idx(fromId)..idx(toId) else idx(fromId) downTo idx(toId)
        val samples = mutableListOf<GoSample>()
        var t = 0L  // secondi dalla partenza
        path.zipWithNext { i, j ->
            val segSeconds = abs(minutes[j] - minutes[i]) * 60L
            while (t < (abs(minutes[j] - minutes[idx(fromId)]) * 60L)) {
                val segStart = abs(minutes[i] - minutes[idx(fromId)]) * 60L
                val f = (t - segStart).toDouble() / segSeconds
                val a = track.stationAlong[i] + f * (track.stationAlong[j] - track.stationAlong[i])
                val side = if (samples.size % 2 == 0) jitter else -jitter
                samples += GoSample(pointNear(a, side), start.plusSeconds(t))
                t += everySeconds
            }
        }
        // ultima posizione: arrivato.
        samples += GoSample(pointNear(along(toId)), start.plusSeconds(t))
        return samples
    }

    /** Dà le posizioni al riconoscitore; restituisce lo stato dopo ognuna. */
    private fun GoDetector.feed(samples: List<GoSample>): List<GoState> = samples.map { update(it) }

    private val morning = LocalDateTime.of(2026, 11, 16, 8, 13)  // lunedì

    // --- Il tracciato ---------------------------------------------------

    @Test
    fun `il tracciato passa per tutte le stazioni, in ordine`() {
        val a = track.stationAlong
        assertEquals(0.0, a.first(), 50.0)  // Porta San Paolo all'inizio
        for (i in 1 until a.size) assertTrue("${stations[i].name} dopo ${stations[i - 1].name}", a[i] > a[i - 1])
        assertTrue("lunghezza ${track.lengthMeters}", track.lengthMeters in 26_000.0..30_000.0)
        // Ogni stazione è a pochi metri dai binari.
        stations.forEach { s -> assertTrue(s.name, track.project(GeoPoint(s.lat, s.lon)).offsetMeters < 30) }
    }

    @Test
    fun `pointAt e project sono uno l'inverso dell'altro`() {
        for (m in listOf(500.0, 7_300.0, 15_000.0, 26_000.0)) {
            val p = track.project(track.pointAt(m))
            assertEquals(m, p.alongMeters, 1.0)
            assertEquals(0.0, p.offsetMeters, 1.0)
        }
    }

    // --- Riconoscimento ---------------------------------------------------

    /** Riconoscitore come nell'app: chiede all'orario se a quell'ora un treno può essere lì. */
    private fun detectorWithSchedule() = GoDetector(track, stations, provider::minutesFromTerminus) { i, dir, time ->
        provider.trainPossible(stations[i].id, dir, time)
    }

    @Test
    fun `di sera sul bus sostitutivo - Go non parte`() {
        // Feriale, 23:20: l'ultimo treno è passato da Vitinia alle 22:43 verso Ostia.
        // Un mezzo che va "come un treno" lungo i binari è il bus (o un treno MOLTO in ritardo).
        val states = detectorWithSchedule().feed(trainRide("vitinia", "lido_centro", LocalDateTime.of(2026, 11, 16, 23, 20)))
        assertTrue(states.none { it is GoState.OnTrain || it is GoState.Detecting })
    }

    @Test
    fun `stesso viaggio di giorno, col controllo dell'orario - Go parte`() {
        val states = detectorWithSchedule().feed(trainRide("vitinia", "lido_centro", morning))
        assertTrue(states.any { it is GoState.OnTrain })
    }

    @Test
    fun `ultimo treno appena in ritardo - Go parte`() {
        // L'ultimo treno feriale verso Ostia passa da Vitinia alle 22:43: 5 minuti dopo è ancora lui.
        val states = detectorWithSchedule().feed(trainRide("vitinia", "lido_centro", LocalDateTime.of(2026, 11, 16, 22, 48)))
        assertTrue(states.any { it is GoState.OnTrain })
    }

    @Test
    fun `sul treno verso Ostia - riconosciuto in meno di 30 secondi`() {
        val d = newDetector()
        val ride = trainRide("vitinia", "lido_centro", morning)
        val states = d.feed(ride)
        val first = states.indexOfFirst { it is GoState.OnTrain }
        assertTrue("mai riconosciuto", first >= 0)
        assertTrue("riconosciuto dopo ${first * 5} s", first * 5 <= 30)
        assertTrue("prima c'è la fase 'Sto riconoscendo…'", states.take(first).any { it is GoState.Detecting })
        val s = states[first] as GoState.OnTrain
        assertEquals(Direction.TO_OSTIA, s.direction)
        assertEquals("casal_bernocchi", s.ahead.first().station.id)
        assertEquals("cristoforo_colombo", s.ahead.last().station.id)
    }

    @Test
    fun `sul treno verso Roma - direzione giusta e fermate davanti in ordine`() {
        val d = newDetector()
        val s = d.feed(trainRide("ostia_antica", "eur_magliana", morning)).first { it is GoState.OnTrain } as GoState.OnTrain
        assertEquals(Direction.TO_ROMA, s.direction)
        assertEquals("acilia_sud", s.ahead.first().station.id)
        assertEquals("porta_san_paolo", s.ahead.last().station.id)
    }

    @Test
    fun `arrivi stimati - treno in orario`() {
        // Parte da Vitinia alle 08:13 = il treno delle 08:00 da Porta San Paolo.
        // Dall'orario: Lido Centro alle 08:31.
        val d = newDetector()
        val s = d.feed(trainRide("vitinia", "lido_centro", morning)).first { it is GoState.OnTrain } as GoState.OnTrain
        val lido = s.ahead.first { it.station.id == "lido_centro" }.time
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 31).toString(), lido.withSecond(0).withNano(0).toString())
    }

    @Test
    fun `arrivi stimati - treno in ritardo di 6 minuti, il ritardo si vede da solo`() {
        val d = newDetector()
        val s = d.feed(trainRide("vitinia", "lido_centro", morning.plusMinutes(6)))
            .first { it is GoState.OnTrain } as GoState.OnTrain
        val lido = s.ahead.first { it.station.id == "lido_centro" }.time
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 37).toString(), lido.withSecond(0).withNano(0).toString())
    }

    @Test
    fun `durante il viaggio la prossima fermata avanza`() {
        val d = newDetector()
        val nexts = d.feed(trainRide("eur_magliana", "acilia", morning))
            .filterIsInstance<GoState.OnTrain>()
            .mapNotNull { it.ahead.firstOrNull()?.station?.id }
            .distinct()
        assertEquals(listOf("tor_di_valle", "vitinia", "casal_bernocchi", "acilia"), nexts.take(4))
    }

    @Test
    fun `GPS che salta un po' indietro non fa tornare indietro la prossima fermata`() {
        val d = newDetector()
        val ride = trainRide("vitinia", "acilia", morning, jitter = 0.0)
        // Ogni tanto il GPS rimanda una posizione 80 m più indietro.
        val noisy = ride.mapIndexed { i, s ->
            if (i > 6 && i % 4 == 0) s.copy(point = pointNear(track.project(s.point).alongMeters - 80)) else s
        }
        var maxAlong = 0.0
        d.feed(noisy).filterIsInstance<GoState.OnTrain>().forEach {
            assertTrue("arretrato a ${it.alongMeters}", it.alongMeters >= maxAlong)
            maxAlong = it.alongMeters
        }
    }

    // --- Falsi allarmi ------------------------------------------------------

    @Test
    fun `a piedi lungo i binari - non e' un treno`() {
        val d = newDetector()
        val start = along("acilia") - 300
        val walk = (0 until 60).map { i ->  // 5 minuti a 5 km/h
            GoSample(pointNear(start + i * 5 * 1.4, side = 15.0), morning.plusSeconds(i * 5L))
        }
        assertTrue(d.feed(walk).all { it == GoState.Off })
    }

    @Test
    fun `fermo in stazione - non e' un treno`() {
        val d = newDetector()
        val wait = (0 until 60).map { i ->
            GoSample(pointNear(along("lido_centro"), side = if (i % 2 == 0) 20.0 else -20.0), morning.plusSeconds(i * 5L))
        }
        assertTrue(d.feed(wait).all { it == GoState.Off })
    }

    @Test
    fun `in auto su una strada parallela a 200 m - non e' un treno`() {
        val d = newDetector()
        val start = along("vitinia")
        val car = (0 until 40).map { i ->  // 50 km/h
            GoSample(pointNear(start + i * 5 * 14.0, side = 200.0), morning.plusSeconds(i * 5L))
        }
        assertTrue(d.feed(car).none { it is GoState.OnTrain })
    }

    @Test
    fun `in auto che attraversa i binari - non e' un treno`() {
        val d = newDetector()
        val cross = (-6..6).map { i ->  // 50 km/h, perpendicolare alla linea
            GoSample(pointNear(along("tor_di_valle") + 500, side = i * 5 * 14.0), morning.plusSeconds((i + 6) * 5L))
        }
        assertTrue(d.feed(cross).none { it is GoState.OnTrain })
    }

    // --- Uscita da Go -------------------------------------------------------

    @Test
    fun `scendi e ti allontani dai binari - si esce da Go`() {
        val d = newDetector()
        val ride = trainRide("vitinia", "acilia", morning)
        d.feed(ride)
        assertTrue(d.state is GoState.OnTrain)
        val end = ride.last().time
        // Cammini via dalla stazione: 200 m, poi 300 m dai binari.
        d.update(GoSample(pointNear(along("acilia"), side = 200.0), end.plusSeconds(120)))
        d.update(GoSample(pointNear(along("acilia"), side = 300.0), end.plusSeconds(180)))
        assertEquals(GoState.Off, d.state)
    }

    @Test
    fun `una sola posizione sbagliata lontano dai binari non fa uscire da Go`() {
        val d = newDetector()
        val ride = trainRide("vitinia", "acilia", morning)
        val withGlitch = ride.mapIndexed { i, s ->
            if (i == ride.size / 2) s.copy(point = pointNear(track.project(s.point).alongMeters, side = 400.0)) else s
        }
        val states = d.feed(withGlitch)
        val first = states.indexOfFirst { it is GoState.OnTrain }
        assertTrue(states.drop(first).all { it is GoState.OnTrain })
    }

    @Test
    fun `fermo al capolinea per piu' di un minuto - si esce da Go`() {
        val d = newDetector()
        val ride = trainRide("stella_polare", "cristoforo_colombo", morning)
        d.feed(ride)
        assertTrue(d.state is GoState.OnTrain)
        val end = ride.last().time
        (1..15).forEach { d.update(GoSample(pointNear(along("cristoforo_colombo"), side = 5.0), end.plusSeconds(it * 5L))) }
        assertEquals(GoState.Off, d.state)
    }

    @Test
    fun `fermo a meta' linea per 2 minuti (treno fermo) - si resta in Go`() {
        val d = newDetector()
        val ride = trainRide("vitinia", "acilia", morning)
        d.feed(ride)
        val end = ride.last().time
        (1..24).forEach { d.update(GoSample(pointNear(along("acilia"), side = 5.0), end.plusSeconds(it * 5L))) }
        assertTrue(d.state is GoState.OnTrain)
    }

    @Test
    fun `il riconoscimento sopravvive alla sosta in stazione`() {
        // Due posizioni da treno, poi 40 s fermi a Vitinia, poi si riparte:
        // il conteggio non deve ripartire da zero.
        val d = newDetector()
        val a0 = along("vitinia")
        var t = morning
        fun at(a: Double) = d.update(GoSample(pointNear(a), t)).also { t = t.plusSeconds(5) }
        at(a0 - 200); at(a0 - 100); at(a0 - 20)        // arrivo in stazione (2 passi)
        repeat(8) { at(a0) }                             // sosta 40 s
        at(a0 + 30); val s = at(a0 + 100)                // ripartenza
        assertTrue("stato: $s", s is GoState.OnTrain)
    }

    @Test
    fun `il generatore di punti finti sposta davvero di lato`() {
        // Il generatore sposta davvero di lato della distanza voluta.
        val p = pointNear(10_000.0, side = 100.0)
        assertEquals(100.0, Geo.distanceMeters(p, track.pointAt(10_000.0)), 3.0)
        assertEquals(100.0, track.project(p).offsetMeters, 3.0)
    }

    // --- Simulatore (lo stesso del menu della build di prova) ----------------

    private fun simulatedRide(direction: Direction, fromId: String, toId: String) =
        RideSimulator.ride(track, provider.minutesFromTerminus(direction), idx(fromId), idx(toId), morning)

    @Test
    fun `viaggio simulato completo verso Ostia - Go fino al capolinea, poi esce da solo`() {
        val d = newDetector()
        val states = d.feed(simulatedRide(Direction.TO_OSTIA, "vitinia", "cristoforo_colombo"))
        val first = states.indexOfFirst { it is GoState.OnTrain }
        val last = states.indexOfLast { it is GoState.OnTrain }
        assertTrue("riconosciuto dopo ${first * 5} s", first in 1..6)
        // Dal riconoscimento all'arrivo, soste in stazione comprese, sempre in Go.
        val arrival = states.size - 1 - 90 / 5
        assertTrue("uscito troppo presto (posizione $last di ${states.size})", last >= arrival)
        assertEquals("dopo un po' fermo al capolinea si esce", GoState.Off, states.last())
    }

    @Test
    fun `viaggio simulato completo verso Roma - sempre direzione Roma`() {
        val d = newDetector()
        val states = d.feed(simulatedRide(Direction.TO_ROMA, "lido_centro", "porta_san_paolo"))
        val onTrain = states.filterIsInstance<GoState.OnTrain>()
        assertTrue(onTrain.isNotEmpty())
        assertTrue(onTrain.all { it.direction == Direction.TO_ROMA })
        assertEquals(GoState.Off, states.last())
    }

    // --- Avvio col pulsante "Vai" (dal 15/11) ------------------------------

    private fun manualDetector() = GoDetector(track, stations, provider::minutesFromTerminus, autoStart = false)

    @Test
    fun `senza pulsante Go non parte da sola`() {
        val d = manualDetector()
        assertTrue(d.feed(trainRide("vitinia", "lido_centro", morning)).all { it == GoState.Off })
    }

    @Test
    fun `Vai a Vitinia, poi GPS a scatti e impreciso - Go resta e gli arrivi tengono`() {
        val d = manualDetector()
        val ride = trainRide("vitinia", "lido_centro", morning)
        d.start(Direction.TO_OSTIA, ride.first())
        // Come sul treno vero: una posizione al minuto, da celle telefoniche (± 150 m).
        val sparse = ride.filterIndexed { i, _ -> i > 0 && i % 12 == 0 }.map { it.copy(accuracy = 150.0) }
        val states = d.feed(sparse)
        assertTrue("uscita da Go durante il viaggio", states.all { it is GoState.OnTrain })
        // Lido Centro dall'orario alle 08:31: al massimo un minuto di scarto.
        states.filterIsInstance<GoState.OnTrain>().forEach { s ->
            val lido = s.ahead.firstOrNull { it.station.id == "lido_centro" } ?: return@forEach
            val off = java.time.Duration.between(LocalDateTime.of(2026, 11, 16, 8, 31), lido.time).seconds
            assertTrue("arrivo a Lido Centro spostato di $off s", abs(off) <= 60)
        }
    }

    @Test
    fun `senza posizioni nuove il pallino avanza coi tempi dell'orario, fino alla prossima stazione`() {
        val d = manualDetector()
        d.start(Direction.TO_OSTIA, GoSample(pointNear(along("vitinia")), morning))
        val after1 = d.estimateAlong(morning.plusMinutes(1))!!
        val after2 = d.estimateAlong(morning.plusMinutes(2))!!
        assertTrue("dopo 1 min non si è mosso", after1 > along("vitinia") + 200)
        assertTrue("dopo 2 min non è più avanti di dopo 1", after2 > after1)
        // Dopo 10 minuti senza GPS si aspetta alla prossima stazione (Casal Bernocchi).
        assertEquals(along("casal_bernocchi"), d.estimateAlong(morning.plusMinutes(10))!!, 1.0)
    }

    @Test
    fun `direzione sbagliata al Vai - si corregge da sola appena il treno si muove`() {
        val d = manualDetector()
        val ride = trainRide("vitinia", "acilia", morning)
        d.start(Direction.TO_ROMA, ride.first())
        val last = d.feed(ride.drop(1)).last { it is GoState.OnTrain } as GoState.OnTrain
        assertEquals(Direction.TO_OSTIA, last.direction)
    }

    @Test
    fun `direzione scelta a mano - la correzione automatica non la ribalta`() {
        val d = manualDetector()
        val ride = trainRide("vitinia", "acilia", morning)
        d.start(Direction.TO_ROMA, ride.first())
        d.setDirection(Direction.TO_ROMA)
        val s = d.feed(ride.drop(1).take(10)).last() as GoState.OnTrain
        assertEquals(Direction.TO_ROMA, s.direction)
    }
}
