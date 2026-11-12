package io.github.edoppiap.orarilido.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Test sull'orario VERO incluso nell'app (src/main/assets/schedule.json).
 * Gli orari attesi sono controllati a mano sul PDF ufficiale.
 *
 * Date usate (novembre 2026):
 *   ven 13 · sab 14 · dom 15 · lun 16;  mar 8 dicembre = festivo.
 */
class TimetableScheduleProviderTest {

    // I test girano dalla cartella del modulo "app", da cui il percorso relativo.
    private val provider = TimetableScheduleProvider(
        ScheduleParser.parse(File("src/main/assets/schedule.json").readText())
    )

    /** Scorciatoia: prossimi orari come stringhe "HH:MM", con "?" se non confermati. */
    private fun next(station: String, dir: Direction, now: String, count: Int = 3): List<String> =
        provider.nextDepartures(station, dir, LocalDateTime.parse(now), count).map {
            "%02d:%02d".format(it.time.hour, it.time.minute) + if (it.unconfirmed) "?" else ""
        }

    @Test
    fun `l'orario incluso è valido e ha 14 stazioni`() {
        assertEquals(14, provider.stations.size)
        assertEquals("porta_san_paolo", provider.stations.first().id)
        assertEquals("cristoforo_colombo", provider.stations.last().id)
        assertEquals(LocalDate.of(2026, 11, 9), provider.validFrom)
    }

    @Test
    fun `feriale - passaggi a meta linea`() {
        // Verso Ostia da Porta San Paolo ogni 15', Tor di Valle a +10'.
        assertEquals(
            listOf("08:10", "08:25", "08:40"),
            next("tor_di_valle", Direction.TO_OSTIA, "2026-11-16T08:00"),
        )
    }

    @Test
    fun `il treno del minuto corrente si vede ancora`() {
        assertEquals("08:10", next("tor_di_valle", Direction.TO_OSTIA, "2026-11-16T08:10:45", 1).single())
    }

    @Test
    fun `corse da Acilia - feriali, verso Roma, non confermate`() {
        // Acilia: regolari a :03 :18 :33 :48 (Colombo +18), più la corsa delle 08:10.
        assertEquals(
            listOf("08:03", "08:10?", "08:18"),
            next("acilia", Direction.TO_ROMA, "2026-11-16T08:00"),
        )
        // A Basilica San Paolo la corsa delle 08:10 da Acilia passa alle 08:26 (+16');
        // le regolari a :04 :19 :34 :49 (Colombo +34).
        assertEquals(
            listOf("08:26?", "08:34", "08:49"),
            next("basilica_san_paolo", Direction.TO_ROMA, "2026-11-16T08:20"),
        )
    }

    @Test
    fun `corse da Acilia - non passano dalle stazioni precedenti`() {
        // Ostia Antica viene prima di Acilia andando verso Roma.
        assertTrue(next("ostia_antica", Direction.TO_ROMA, "2026-11-16T07:55", 6).none { it.endsWith("?") })
    }

    @Test
    fun `corse da Acilia - non il sabato ne verso Ostia`() {
        assertTrue(next("acilia", Direction.TO_ROMA, "2026-11-14T08:00", 6).none { it.endsWith("?") })
        assertTrue(next("acilia", Direction.TO_OSTIA, "2026-11-16T05:30", 20).none { it.endsWith("?") })
    }

    @Test
    fun `venerdi dopo l'ultimo treno - primo del sabato`() {
        // Lun–ven ultima partenza da Porta San Paolo alle 22:30.
        assertEquals(
            listOf("05:15", "05:30", "05:45"),
            next("porta_san_paolo", Direction.TO_OSTIA, "2026-11-13T22:40"),
        )
    }

    @Test
    fun `sabato - corse delle 23 e 23_30, poi la domenica`() {
        assertEquals(
            listOf("23:00", "23:30", "05:15"),
            next("porta_san_paolo", Direction.TO_OSTIA, "2026-11-14T22:40"),
        )
    }

    @Test
    fun `mezzanotte - la corsa del sabato arriva dopo le 00`() {
        // Partenza 23:30 da Colombo il sabato, Basilica San Paolo a +34' = 00:04 di domenica.
        val result = provider.nextDepartures(
            "basilica_san_paolo", Direction.TO_ROMA, LocalDateTime.parse("2026-11-15T00:01"), 3,
        )
        assertEquals(LocalDateTime.parse("2026-11-15T00:04"), result[0].time)
        // Poi la domenica: 05:15 e 05:30 da Colombo, +34'.
        assertEquals(LocalDateTime.parse("2026-11-15T05:49"), result[1].time)
        assertEquals(LocalDateTime.parse("2026-11-15T06:04"), result[2].time)
    }

    @Test
    fun `domenica - ogni 20 minuti`() {
        assertEquals(
            listOf("10:05", "10:25", "10:45"),
            next("porta_san_paolo", Direction.TO_OSTIA, "2026-11-15T10:00"),
        )
    }

    @Test
    fun `festivo infrasettimanale - orario della domenica`() {
        assertEquals(DayType.HOLIDAY, provider.dayTypeOf(LocalDate.of(2026, 12, 8)))
        assertEquals(
            listOf("10:05", "10:25", "10:45"),
            next("porta_san_paolo", Direction.TO_OSTIA, "2026-12-08T10:00"),
        )
    }

    @Test
    fun `capolinea - solo la direzione utile`() {
        assertFalse(provider.servesDirection("porta_san_paolo", Direction.TO_ROMA))
        assertTrue(provider.servesDirection("porta_san_paolo", Direction.TO_OSTIA))
        assertFalse(provider.servesDirection("cristoforo_colombo", Direction.TO_OSTIA))
        assertTrue(provider.servesDirection("cristoforo_colombo", Direction.TO_ROMA))
        assertTrue(next("porta_san_paolo", Direction.TO_ROMA, "2026-11-16T08:00").isEmpty())
    }

    @Test
    fun `tipi di giornata`() {
        assertEquals(DayType.WEEKDAY, provider.dayTypeOf(LocalDate.of(2026, 11, 13)))
        assertEquals(DayType.SATURDAY, provider.dayTypeOf(LocalDate.of(2026, 11, 14)))
        assertEquals(DayType.HOLIDAY, provider.dayTypeOf(LocalDate.of(2026, 11, 15)))
    }
}
