package io.github.edoppiap.orarilido.schedule

import java.time.LocalDate
import java.time.LocalDateTime

// Modello dei dati dell'orario, in Kotlin puro (nessuna dipendenza Android):
// così la logica si può testare con JUnit sul PC, senza telefono.

/** Le due direzioni della linea. */
enum class Direction(val jsonKey: String, val label: String) {
    TO_ROMA("toRoma", "Verso Roma (Porta San Paolo)"),
    TO_OSTIA("toOstia", "Verso Ostia (Colombo)"),
}

/**
 * Tipo di giornata: decide quale tabella di partenze usare.
 * Le domeniche e i festivi infrasettimanali usano la stessa tabella (HOLIDAY).
 */
enum class DayType(val jsonKey: String) {
    WEEKDAY("weekday"),   // lunedì–venerdì
    SATURDAY("saturday"),
    HOLIDAY("holiday"),   // domenica e festivi
}

/** Una stazione, con coordinate (da OpenStreetMap). L'ordine nella lista va da Roma (indice 0) verso Ostia. */
data class Station(val id: String, val name: String, val lat: Double, val lon: Double)

/**
 * Una corsa: parte dalla stazione [originIndex] al minuto [departureMinute]
 * della giornata di servizio. I minuti possono superare 1440 (= mezzanotte):
 * una corsa "della domenica" che passa alle 00:04 di lunedì appartiene
 * ancora alla domenica.
 */
data class Trip(
    val originIndex: Int,
    val departureMinute: Int,
    val unconfirmed: Boolean = false,
)

/** Un passaggio di un treno in una stazione: è quello che mostra la UI. */
data class Departure(
    val time: LocalDateTime,
    /** true = corsa non confermata dalla fonte ufficiale: la UI mostra un "?" rosso. */
    val unconfirmed: Boolean = false,
)

/**
 * L'orario completo, già validato e pronto all'uso.
 * Si ottiene da [ScheduleParser.parse].
 */
data class Timetable(
    val version: Int,
    val validFrom: LocalDate,
    val source: String,
    val stations: List<Station>,
    /** Per ogni direzione: minuti dal capolinea di partenza, per indice di stazione. */
    val minutesFromTerminus: Map<Direction, IntArray>,
    /** Per ogni tipo di giornata e direzione: tutte le corse (regolari + aggiuntive). */
    val trips: Map<DayType, Map<Direction, List<Trip>>>,
    val holidays: Set<LocalDate>,
)
