package io.github.edoppiap.orarilido.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Alba/tramonto a Roma confrontati con valori noti (tolleranza 5 minuti). */
class SunTimesTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun assertNear(expected: String, actual: java.time.Instant) {
        val local = actual.atZone(rome).toLocalTime()
        val diff = Duration.between(LocalTime.parse(expected), local).abs()
        assertTrue("atteso ~$expected, calcolato $local", diff <= Duration.ofMinutes(5))
    }

    @Test
    fun `novembre - alba verso le 7 e tramonto verso le 17`() {
        val t = SunTimes.forDate(LocalDate.of(2026, 11, 12), 41.90, 12.50)
        assertNear("06:59", t.sunrise)
        assertNear("16:57", t.sunset)
    }

    @Test
    fun `solstizio d'estate - ora legale`() {
        val t = SunTimes.forDate(LocalDate.of(2026, 6, 21), 41.90, 12.50)
        assertNear("05:36", t.sunrise)
        assertNear("20:48", t.sunset)
    }

    @Test
    fun `giorno e notte`() {
        assertFalse(SunTimes.isNight(ZonedDateTime.of(2026, 11, 12, 12, 0, 0, 0, rome)))
        assertTrue(SunTimes.isNight(ZonedDateTime.of(2026, 11, 12, 18, 30, 0, 0, rome)))
        assertTrue(SunTimes.isNight(ZonedDateTime.of(2026, 11, 13, 5, 0, 0, 0, rome)))
    }
}
