package io.github.edoppiap.orarilido.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Bus sostitutivi serali e controllo "può esserci un treno?" della modalità Go,
 * sull'orario VERO incluso nell'app.
 *
 * Date: lun 16 novembre 2026 (feriale), sab 14, mar 8 dicembre (festivo).
 * Bus: da Porta San Paolo alle 23:00 (verso Ostia), da Colombo alle 23:45 (verso Roma).
 */
class ReplacementBusTest {

    private val provider = TimetableScheduleProvider(
        ScheduleParser.parse(File("src/main/assets/schedule.json").readText())
    )

    private fun at(text: String) = LocalDateTime.parse(text)

    @Test
    fun `fermate - una per stazione e verso, niente ad Acilia Sud`() {
        assertEquals(24, provider.busStops.size)
        assertNull(provider.busStop("acilia_sud", Direction.TO_OSTIA))
        assertNull(provider.busStop("acilia_sud", Direction.TO_ROMA))
        // Ai capolinea solo il verso in cui il bus parte.
        assertNull(provider.busStop("porta_san_paolo", Direction.TO_ROMA))
        assertNull(provider.busStop("cristoforo_colombo", Direction.TO_OSTIA))
        // Acilia: verso Ostia su Viale dei Romagnoli, verso Roma sulla Via Ostiense
        // (la tabella Cotral li aveva invertiti).
        assertEquals("76762", provider.busStop("acilia", Direction.TO_OSTIA)?.code)
        assertEquals("76675", provider.busStop("acilia", Direction.TO_ROMA)?.code)
    }

    @Test
    fun `feriale - ad Acilia il bus si vede fino alla stima larga, poi sparisce`() {
        val bus = provider.nextBus("acilia", Direction.TO_OSTIA, at("2026-11-16T22:40"))
        assertNotNull(bus)
        assertEquals(at("2026-11-16T23:00"), bus!!.departure)
        assertFalse(bus.atTerminus)
        // 21 minuti di treno × 1,5 = 31,5 → 32, più 10 di margine.
        assertEquals(at("2026-11-16T23:42"), bus.visibleUntil)
        assertNotNull(provider.nextBus("acilia", Direction.TO_OSTIA, at("2026-11-16T23:42")))
        assertNull(provider.nextBus("acilia", Direction.TO_OSTIA, at("2026-11-16T23:43")))
    }

    @Test
    fun `feriale - al capolinea il bus ha l'orario esatto e sparisce alla partenza`() {
        val bus = provider.nextBus("porta_san_paolo", Direction.TO_OSTIA, at("2026-11-16T22:40"))
        assertTrue(bus!!.atTerminus)
        assertEquals(at("2026-11-16T23:00"), bus.visibleUntil)
        assertNull(provider.nextBus("porta_san_paolo", Direction.TO_OSTIA, at("2026-11-16T23:01")))
    }

    @Test
    fun `il bus delle 23 e 45 verso Roma si vede ancora dopo mezzanotte`() {
        val bus = provider.nextBus("acilia", Direction.TO_ROMA, at("2026-11-17T00:10"))
        assertEquals(at("2026-11-16T23:45"), bus?.departure)
    }

    @Test
    fun `sabato e festivi niente bus`() {
        assertNull(provider.nextBus("acilia", Direction.TO_OSTIA, at("2026-11-14T22:40")))
        assertNull(provider.nextBus("acilia", Direction.TO_OSTIA, at("2026-12-08T22:40")))  // martedì festivo
        assertFalse(provider.hasBuses(DayType.SATURDAY))
        assertTrue(provider.hasBuses(DayType.WEEKDAY))
    }

    @Test
    fun `Go - dopo l'ultimo treno bastano 10 minuti per dire che non e un treno`() {
        // Acilia verso Roma: ultimo treno alle 22:48.
        assertTrue(provider.trainPossible("acilia", Direction.TO_ROMA, at("2026-11-16T22:58")))
        assertFalse(provider.trainPossible("acilia", Direction.TO_ROMA, at("2026-11-16T22:59")))
        assertFalse(provider.trainPossible("acilia", Direction.TO_ROMA, at("2026-11-16T23:15")))
        // Capolinea d'arrivo: conta il treno che ci arriva (ultimo a Colombo alle 23:07).
        assertTrue(provider.trainPossible("cristoforo_colombo", Direction.TO_OSTIA, at("2026-11-16T23:15")))
        assertFalse(provider.trainPossible("cristoforo_colombo", Direction.TO_OSTIA, at("2026-11-16T23:30")))
    }

    @Test
    fun `Go - durante il servizio un treno e sempre possibile, anche negli intervalli di 30 minuti`() {
        // Ogni minuto da 10 min prima del primo treno a 10 min dopo l'ultimo, in tutte le
        // stazioni, direzioni e tipi di giornata: sempre "sì". Dentro gli intervalli di
        // 30 minuti (sabato sera, domenica all'alba) non deve esserci nessun buco.
        for (date in listOf("2026-11-16", "2026-11-14", "2026-11-15")) {
            val day = java.time.LocalDate.parse(date)
            for (station in provider.stations) for (dir in Direction.entries) {
                if (!provider.servesDirection(station.id, dir)) continue
                val times = provider.departuresForDay(station.id, dir, day).map { it.time }
                var t = times.first().minusMinutes(10)
                while (!t.isAfter(times.last().plusMinutes(10))) {
                    assertTrue("${station.id} $dir $t", provider.trainPossible(station.id, dir, t))
                    t = t.plusMinutes(1)
                }
            }
        }
    }
}
