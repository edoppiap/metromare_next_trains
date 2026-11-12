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

    /** Feriale, sabato o festivo: quale tabella vale in quella data. */
    fun dayTypeOf(date: LocalDate): DayType
}
