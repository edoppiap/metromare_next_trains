package io.github.edoppiap.orarilido.schedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Errore lanciato quando schedule.json è malformato o incoerente. */
class InvalidScheduleException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Legge schedule.json e lo trasforma in un [Timetable], controllando che
 * sia coerente. Se qualcosa non va lancia [InvalidScheduleException]:
 * chi lo chiama (es. il download da internet) userà allora la copia
 * precedente invece di un orario rotto.
 */
object ScheduleParser {

    // ignoreUnknownKeys: un file più nuovo con campi in più resta leggibile.
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Timetable {
        val raw = try {
            json.decodeFromString<ScheduleJson>(text)
        } catch (e: SerializationException) {
            throw InvalidScheduleException("JSON non valido: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidScheduleException("JSON non valido: ${e.message}", e)
        }
        return validate(raw)
    }

    private fun validate(raw: ScheduleJson): Timetable {
        check(raw.version >= 1) { "version deve essere >= 1" }
        val validFrom = parseDate(raw.validFrom, "validFrom")

        // Stazioni: almeno due, id tutti diversi.
        val stations = raw.stations.map { Station(it.id, it.name) }
        check(stations.size >= 2) { "servono almeno 2 stazioni" }
        check(stations.map { it.id }.toSet().size == stations.size) { "id di stazione duplicati" }
        val indexById = stations.withIndex().associate { (i, s) -> s.id to i }

        // Minuti dal capolinea: ogni stazione deve averli, e devono crescere
        // nel verso di marcia (0 al capolinea di partenza).
        val minutes = Direction.entries.associateWith { dir ->
            val map = raw.minutesFromTerminus[dir.jsonKey]
                ?: fail("minutesFromTerminus.${dir.jsonKey} mancante")
            val array = IntArray(stations.size) { i ->
                map[stations[i].id] ?: fail("minuti mancanti per ${stations[i].id} (${dir.jsonKey})")
            }
            // Ordine di marcia: verso Ostia = indici crescenti, verso Roma = decrescenti.
            val inTravelOrder = if (dir == Direction.TO_OSTIA) array.toList() else array.reversed()
            check(inTravelOrder.first() == 0) { "il capolinea di ${dir.jsonKey} deve avere 0 minuti" }
            check(inTravelOrder.zipWithNext().all { (a, b) -> b > a }) {
                "i minuti di ${dir.jsonKey} devono crescere nel verso di marcia"
            }
            array
        }

        // Corse regolari: partono dal capolinea della loro direzione.
        val trips = DayType.entries.associateWith { day ->
            val byDir = raw.departures[day.jsonKey] ?: fail("departures.${day.jsonKey} mancante")
            Direction.entries.associateWith { dir ->
                val times = byDir[dir.jsonKey] ?: fail("departures.${day.jsonKey}.${dir.jsonKey} mancante")
                val minutesList = times.map { parseTime(it) }
                check(minutesList.isNotEmpty()) { "nessuna corsa in ${day.jsonKey}.${dir.jsonKey}" }
                check(minutesList.zipWithNext().all { (a, b) -> b > a }) {
                    "orari non in ordine crescente in ${day.jsonKey}.${dir.jsonKey}"
                }
                val origin = if (dir == Direction.TO_OSTIA) 0 else stations.lastIndex
                minutesList.map { Trip(origin, it) }.toMutableList()
            }
        }

        // Corse aggiuntive (es. da Acilia): si aggiungono alle tabelle dei giorni indicati.
        for (extra in raw.extraTrips) {
            val dir = Direction.entries.firstOrNull { it.jsonKey == extra.direction }
                ?: fail("direzione sconosciuta: ${extra.direction}")
            val origin = indexById[extra.from] ?: fail("stazione sconosciuta: ${extra.from}")
            val trip = Trip(origin, parseTime(extra.time), extra.unconfirmed)
            check(extra.days.isNotEmpty()) { "corsa aggiuntiva ${extra.time} senza giorni" }
            for (dayKey in extra.days) {
                val day = DayType.entries.firstOrNull { it.jsonKey == dayKey }
                    ?: fail("tipo di giorno sconosciuto: $dayKey")
                trips.getValue(day).getValue(dir) += trip
            }
        }

        return Timetable(
            version = raw.version,
            validFrom = validFrom,
            source = raw.source,
            stations = stations,
            minutesFromTerminus = minutes,
            trips = trips,
            holidays = raw.holidays.map { parseDate(it, "holidays") }.toSet(),
        )
    }

    /** "HH:MM" → minuti dalla mezzanotte. Ammesse ore oltre 23 (es. "24:10") per le corse notturne. */
    internal fun parseTime(text: String): Int {
        val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text)
            ?: fail("orario non valido: \"$text\"")
        val (h, m) = match.destructured
        val hours = h.toInt()
        val mins = m.toInt()
        check(hours < 48 && mins < 60) { "orario non valido: \"$text\"" }
        return hours * 60 + mins
    }

    private fun parseDate(text: String, field: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw InvalidScheduleException("data non valida in $field: \"$text\"", e)
    }

    private fun fail(message: String): Nothing = throw InvalidScheduleException(message)

    // Versione di check() che lancia la nostra eccezione invece di IllegalStateException.
    private inline fun check(condition: Boolean, message: () -> String) {
        if (!condition) fail(message())
    }
}

// ---- Struttura del file JSON, così com'è scritto (prima della validazione) ----

@Serializable
private data class ScheduleJson(
    val version: Int,
    val validFrom: String,
    val source: String = "",
    val stations: List<StationJson>,
    /** direzione → (id stazione → minuti dal capolinea) */
    val minutesFromTerminus: Map<String, Map<String, Int>>,
    /** tipo di giorno → direzione → lista di orari "HH:MM" dal capolinea */
    val departures: Map<String, Map<String, List<String>>>,
    val extraTrips: List<ExtraTripJson> = emptyList(),
    val holidays: List<String> = emptyList(),
)

@Serializable
private data class StationJson(val id: String, val name: String)

@Serializable
private data class ExtraTripJson(
    val direction: String,
    val from: String,
    val time: String,
    val days: List<String>,
    val unconfirmed: Boolean = false,
)
