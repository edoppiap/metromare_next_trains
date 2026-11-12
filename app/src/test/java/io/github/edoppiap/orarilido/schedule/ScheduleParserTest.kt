package io.github.edoppiap.orarilido.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il parser deve rifiutare file rotti, così l'app non usa mai un orario sbagliato. */
class ScheduleParserTest {

    // Orario minimo valido con 3 stazioni fittizie A–B–C.
    private fun schedule(
        validFrom: String = "2026-11-09",
        toOstiaMinutes: String = """{"a": 0, "b": 5, "c": 10}""",
        weekdayToOstia: String = """["06:00", "06:15"]""",
        extra: String = "[]",
    ) = """
        {
          "version": 1,
          "validFrom": "$validFrom",
          "campoSconosciuto": "ignorato",
          "stations": [{"id": "a", "name": "A"}, {"id": "b", "name": "B"}, {"id": "c", "name": "C"}],
          "minutesFromTerminus": {
            "toOstia": $toOstiaMinutes,
            "toRoma": {"c": 0, "b": 5, "a": 10}
          },
          "departures": {
            "weekday":  {"toRoma": ["06:00"], "toOstia": $weekdayToOstia},
            "saturday": {"toRoma": ["06:00"], "toOstia": ["06:00"]},
            "holiday":  {"toRoma": ["06:00"], "toOstia": ["06:00"]}
          },
          "extraTrips": $extra
        }
    """.trimIndent()

    private fun assertInvalid(json: String) {
        assertThrows(InvalidScheduleException::class.java) { ScheduleParser.parse(json) }
    }

    @Test
    fun `file valido`() {
        val t = ScheduleParser.parse(schedule())
        assertEquals(3, t.stations.size)
        assertEquals(2, t.trips.getValue(DayType.WEEKDAY).getValue(Direction.TO_OSTIA).size)
    }

    @Test fun `JSON rotto`() = assertInvalid("{ non è json")
    @Test fun `data non valida`() = assertInvalid(schedule(validFrom = "9 novembre"))
    @Test fun `stazione senza minuti`() = assertInvalid(schedule(toOstiaMinutes = """{"a": 0, "b": 5}"""))
    @Test fun `minuti non crescenti`() = assertInvalid(schedule(toOstiaMinutes = """{"a": 0, "b": 12, "c": 10}"""))
    @Test fun `orari non in ordine`() = assertInvalid(schedule(weekdayToOstia = """["06:15", "06:00"]"""))
    @Test fun `orario malformato`() = assertInvalid(schedule(weekdayToOstia = """["6.00"]"""))

    @Test
    fun `corsa aggiuntiva da stazione inesistente`() = assertInvalid(
        schedule(extra = """[{"direction": "toRoma", "from": "zzz", "time": "07:00", "days": ["weekday"]}]""")
    )

    @Test
    fun `orari dopo mezzanotte ammessi`() {
        assertEquals(24 * 60 + 10, ScheduleParser.parseTime("24:10"))
    }
}
