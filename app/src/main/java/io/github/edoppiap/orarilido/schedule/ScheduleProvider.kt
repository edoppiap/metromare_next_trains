package io.github.edoppiap.orarilido.schedule

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ciò che il resto dell'app sa chiedere all'orario.
 *
 * È un'interfaccia apposta: oggi la implementa [TimetableScheduleProvider]
 * (orari programmati da schedule.json), domani potrebbe implementarla
 * qualcosa basato su GTFS o sul tempo reale, senza toccare la UI.
 */
interface ScheduleProvider {
    /** Stazioni in ordine da Roma verso Ostia. */
    val stations: List<Station>

    /** Data da cui vale l'orario in uso (da mostrare in piccolo nella UI). */
    val validFrom: LocalDate

    /**
     * false se da questa stazione non partono treni in quella direzione:
     * succede ai capolinea (da Porta San Paolo non si va "verso Roma").
     */
    fun servesDirection(stationId: String, direction: Direction): Boolean

    /**
     * I prossimi [count] treni che passano da [stationId] in [direction]
     * a partire da [now] (incluso il minuto corrente). Se per oggi le corse
     * sono finite, restituisce le prime del giorno dopo.
     */
    fun nextDepartures(
        stationId: String,
        direction: Direction,
        now: LocalDateTime,
        count: Int = 3,
    ): List<Departure>

    /**
     * Tutti i passaggi della giornata di servizio [serviceDate], in ordine.
     * Le corse dopo mezzanotte (es. 00:04) hanno la data del giorno dopo
     * ma appartengono a questa giornata.
     */
    fun departuresForDay(stationId: String, direction: Direction, serviceDate: LocalDate): List<Departure>

    /**
     * Minuti di viaggio dal capolinea di partenza a ogni stazione (per indice),
     * in quella direzione. Uguali per tutte le corse: li usa la modalità Go.
     */
    fun minutesFromTerminus(direction: Direction): IntArray

    /** Feriale, sabato o festivo: quale tabella vale in quella data. */
    fun dayTypeOf(date: LocalDate): DayType

    /** Tutte le fermate dei bus sostitutivi (vuota = niente bus). */
    val busStops: List<BusStop>

    /** La fermata del bus sostitutivo di una stazione in un verso (null = il bus non ferma qui). */
    fun busStop(stationId: String, direction: Direction): BusStop?

    /** true se in quel tipo di giornata ci sono bus sostitutivi. */
    fun hasBuses(dayType: DayType): Boolean

    /** I bus sostitutivi della giornata [serviceDate] che fermano a [stationId] in [direction]. */
    fun busesForDay(stationId: String, direction: Direction, serviceDate: LocalDate): List<BusPass>

    /** Il prossimo bus sostitutivo ancora da mostrare a [now] (null = nessuno). */
    fun nextBus(stationId: String, direction: Direction, now: LocalDateTime): BusPass?

    /**
     * Modalità Go: può esserci un treno in [direction] vicino a [stationId] adesso?
     * Sì se lì ne è passato uno negli ultimi 10 minuti o ne arriva uno entro 20
     * (l'intervallo più lungo dell'orario è di 30 minuti, quindi di giorno è sempre sì).
     * Dopo l'ultimo treno della sera, passati 10 minuti, è no: sei sul bus.
     */
    fun trainPossible(stationId: String, direction: Direction, now: LocalDateTime): Boolean
}
