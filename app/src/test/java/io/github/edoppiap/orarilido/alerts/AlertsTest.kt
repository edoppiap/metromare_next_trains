package io.github.edoppiap.orarilido.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Regole degli avvisi Cotral: cosa si vede nel menu e quando c'è il pallino rosso. */
class AlertsTest {

    // Il file vero scritto dallo script il 15/11/2026.
    private val real = """
        {
          "updated": "2026-11-15T02:08",
          "alerts": [
            { "id": 5278, "modified": "2026-11-13T15:14:58", "level": "urgente",
              "title": "SCIOPERO Venerdì 20 novembre", "link": "https://cotralspa.it/avvisi/sciopero/",
              "end": "2026-11-20T23:59:00", "trainStrikeBands": ["08:30-16:59", "20:00-23:59"] },
            { "id": 5935, "modified": "2026-11-13T15:00:38", "level": "notizia",
              "title": "METROMARE – frequenza a 15 minuti da lunedì 9 novembre",
              "link": "https://cotralspa.it/avvisi/metromare-frequenza-a-15-minuti-da-lunedi-9-novembre/",
              "end": null }
          ]
        }
    """.trimIndent()

    private val file = AlertsParser.parse(real)!!
    private val strike = file.alerts.first { it.id == 5278L }
    private val sunday = LocalDateTime.of(2026, 11, 15, 10, 0)

    @Test
    fun `legge il file vero`() {
        assertEquals(2, file.alerts.size)
        assertTrue(strike.urgent)
        assertTrue(strike.isStrike)
        assertEquals("Treni in sciopero 08:30–16:59 · 20:00–23:59", AlertRules.strikeLabel(strike))
        assertEquals("aggiornato 13/11 15:14", AlertRules.updatedLabel(strike))
    }

    @Test
    fun `file rovinato o non json = null`() {
        assertNull(AlertsParser.parse("<html>errore</html>"))
        assertNull(AlertsParser.parse(""))
    }

    @Test
    fun `campi in piu' di una versione futura dello script vengono ignorati`() {
        val f = AlertsParser.parse(real.replace("\"updated\"", "\"nuovo\": 1, \"updated\""))
        assertNotNull(f)
        assertEquals(2, f!!.alerts.size)
    }

    @Test
    fun `link che non portano a Cotral vengono scartati`() {
        val f = AlertsParser.parse(real.replace("https://cotralspa.it/avvisi/sciopero/", "https://esempio.com/x"))!!
        assertEquals(listOf(5935L), f.alerts.map { it.id })
    }

    @Test
    fun `urgente in cima, notizia solo nella lista, pallino acceso`() {
        val v = AlertRules.view(file, sunday, emptySet(), emptySet())
        assertEquals(listOf(5278L), v.urgent.map { it.id })
        assertEquals(listOf(5278L, 5935L), v.all.map { it.id })  // urgenti per primi
        assertTrue(v.badge)
    }

    @Test
    fun `aperto il menu il pallino si spegne ma l'avviso resta in cima`() {
        val seen = AlertRules.keysToMarkSeen(file, sunday)
        val v = AlertRules.view(file, sunday, seen, emptySet())
        assertFalse(v.badge)
        assertEquals(1, v.urgent.size)
    }

    @Test
    fun `la X lo toglie dalla cima ma resta nelle Notizie`() {
        val v = AlertRules.view(file, sunday, emptySet(), setOf(AlertRules.key(strike, sunday)))
        assertTrue(v.urgent.isEmpty())
        assertFalse(v.badge)
        assertEquals(2, v.all.size)
    }

    @Test
    fun `la sera prima dello sciopero il pallino si riaccende, anche se tolto con la X`() {
        val seen = AlertRules.keysToMarkSeen(file, sunday)
        val dismissed = setOf(AlertRules.key(strike, sunday))
        val thursday17 = LocalDateTime.of(2026, 11, 19, 17, 59)
        val thursday18 = LocalDateTime.of(2026, 11, 19, 18, 0)
        assertFalse(AlertRules.view(file, thursday17, seen, dismissed).badge)
        val eve = AlertRules.view(file, thursday18, seen, dismissed)
        assertTrue(eve.badge)
        assertEquals(1, eve.urgent.size)
        // Aperto il menu la vigilia: spento fino alla fine.
        val seenEve = seen + AlertRules.keysToMarkSeen(file, thursday18)
        assertFalse(AlertRules.view(file, LocalDateTime.of(2026, 11, 20, 9, 0), seenEve, dismissed).badge)
    }

    @Test
    fun `se Cotral modifica l'avviso il pallino si riaccende`() {
        val seen = AlertRules.keysToMarkSeen(file, sunday)
        val changed = AlertsParser.parse(real.replace("2026-11-13T15:14:58", "2026-11-16T09:00:00"))!!
        assertTrue(AlertRules.view(changed, sunday, seen, emptySet()).badge)
    }

    @Test
    fun `finito lo sciopero sparisce da solo, anche se il file non si aggiorna`() {
        val saturday = LocalDateTime.of(2026, 11, 21, 0, 0)
        val v = AlertRules.view(file, saturday, emptySet(), emptySet())
        assertTrue(v.urgent.isEmpty())
        assertFalse(v.badge)
        assertEquals(listOf(5935L), v.all.map { it.id })
    }

    @Test
    fun `senza file (primo avvio offline) niente avvisi e niente pallino`() {
        val v = AlertRules.view(null, sunday, emptySet(), emptySet())
        assertTrue(v.all.isEmpty())
        assertFalse(v.badge)
    }

    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m)

    @Test
    fun `lo sciopero c'e' solo il giorno della fine, fino all'ultima fascia`() {
        assertNull(AlertRules.strikeToday(file, at(19, 23, 0)))           // il giorno prima: no
        val s = AlertRules.strikeToday(file, at(20, 6, 0))!!
        assertEquals("Sciopero oggi · treni a rischio 08:30–16:59 e 20:00–23:59", s.title)
        assertEquals("● = treno a rischio, potrebbe non passare", s.legend)
        assertNotNull(AlertRules.strikeToday(file, at(20, 23, 59)))
        assertNull(AlertRules.strikeToday(file, at(21, 0, 0)))            // il giorno dopo: no
    }

    @Test
    fun `a rischio conta la partenza dal capolinea, non il passaggio`() {
        val s = AlertRules.strikeToday(file, at(20, 8, 0))!!
        assertFalse(s.atRisk(at(20, 8, 25)))   // passa ad Acilia alle 08:46, ma è partito prima
        assertTrue(s.atRisk(at(20, 8, 30)))    // primo minuto della fascia
        assertTrue(s.atRisk(at(20, 16, 59)))   // ultimo minuto
        assertFalse(s.atRisk(at(20, 17, 0)))   // fascia garantita
        assertFalse(s.atRisk(at(20, 19, 45)))  // passa ad Acilia alle 20:06: non a rischio
        assertTrue(s.atRisk(at(20, 20, 0)))
        assertFalse(s.atRisk(at(21, 8, 30)))   // un altro giorno
    }

    @Test
    fun `senza fasce lette c'e' la riga ma nessun pallino`() {
        val f = AlertsParser.parse(real.replace("\"trainStrikeBands\": [\"08:30-16:59\", \"20:00-23:59\"]", "\"trainStrikeBands\": []"))!!
        val s = AlertRules.strikeToday(f, at(20, 10, 0))!!
        assertEquals("Sciopero oggi", s.title)
        assertNull(s.legend)
        assertFalse(s.atRisk(at(20, 10, 0)))
    }

    @Test
    fun `le chiavi di avvisi spariti dal file vengono dimenticate`() {
        val keys = setOf("5278@2026-11-13T15:14:58@annuncio", "1234@2026-09-09T22:06:00@annuncio")
        assertEquals(setOf("5278@2026-11-13T15:14:58@annuncio"), AlertRules.prune(keys, file))
    }
}
