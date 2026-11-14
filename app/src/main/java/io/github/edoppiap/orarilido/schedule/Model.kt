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
 * Fermata di un bus sostitutivo vicino a una stazione, per un verso
 * (le due fermate di una stazione sono spesso su lati o strade diverse).
 */
data class BusStop(
    val stationId: String,
    val direction: Direction,
    /** Codice della palina (Atac o Cotral), come nella pagina Cotral. */
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Bus sostitutivi serali (lavori sulla linea): partono dal capolinea a orari
 * noti, ma gli orari di passaggio alle altre fermate NON sono pubblicati.
 */
data class ReplacementBuses(
    /** In quali tipi di giornata ci sono (oggi solo feriali). */
    val days: Set<DayType>,
    /** Per direzione: minuti di partenza dal capolinea, nella giornata di servizio. */
    val departures: Map<Direction, List<Int>>,
    val stops: List<BusStop>,
)

/** Un bus sostitutivo che riguarda una stazione: parte dal capolinea a [departure]. */
data class BusPass(
    /** Partenza dal capolinea (l'unico orario pubblicato). */
    val departure: LocalDateTime,
    /** true = la stazione È il capolinea di partenza: qui l'orario è esatto. */
    val atTerminus: Boolean,
    /**
     * Fino a quando ha senso mostrarlo in questa stazione: al capolinea fino alla
     * partenza; altrove una stima larga (partenza + minuti del treno × 1,5 + 10)
     * che serve solo a toglierlo, e non si mostra mai.
     */
    val visibleUntil: LocalDateTime,
    /** Ordine rispetto ai treni: quando passerebbe un treno partito alla stessa ora. */
    val sortTime: LocalDateTime,
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
    /** Bus sostitutivi serali; null = non ce ne sono. */
    val buses: ReplacementBuses? = null,
)
