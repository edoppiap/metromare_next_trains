package io.github.edoppiap.orarilido.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Calcola i prossimi passaggi a partire da un [Timetable] (orari programmati).
 *
 * Idea di fondo: l'orario dice a che ora ogni corsa parte dal capolinea e
 * quanti minuti impiega per arrivare a ciascuna stazione. Quindi
 *   passaggio in stazione = partenza + minuti(stazione) − minuti(origine).
 */
class TimetableScheduleProvider(private val timetable: Timetable) : ScheduleProvider {

    override val stations: List<Station> get() = timetable.stations
    override val validFrom: LocalDate get() = timetable.validFrom

    // Indice di ogni stazione per id, calcolato una volta sola.
    private val indexById: Map<String, Int> =
        timetable.stations.withIndex().associate { (i, s) -> s.id to i }

    private val lastIndex = timetable.stations.lastIndex

    override fun servesDirection(stationId: String, direction: Direction): Boolean {
        val index = indexOf(stationId)
        // L'ultima fermata di una direzione è il capolinea d'arrivo:
        // da lì non parte nessun treno in quella direzione.
        return when (direction) {
            Direction.TO_ROMA -> index != 0
            Direction.TO_OSTIA -> index != lastIndex
        }
    }

    override fun nextDepartures(
        stationId: String,
        direction: Direction,
        now: LocalDateTime,
        count: Int,
    ): List<Departure> {
        if (!servesDirection(stationId, direction)) return emptyList()
        val stationIndex = indexOf(stationId)
        val minutes = timetable.minutesFromTerminus.getValue(direction)
        // Si ragiona al minuto: un treno delle 08:00 viene mostrato
        // ("tra 0 min") fino alle 08:00:59.
        val from = now.truncatedTo(ChronoUnit.MINUTES)

        // Guardiamo tre giornate di servizio: ieri (per le corse dopo
        // mezzanotte), oggi, e domani (per il primo treno dopo l'ultimo).
        val result = mutableListOf<Departure>()
        for (dayOffset in -1L..1L) {
            val day = from.toLocalDate().plusDays(dayOffset)
            val trips = timetable.trips.getValue(dayTypeOf(day)).getValue(direction)
            for (trip in trips) {
                if (!passesThrough(trip, stationIndex, direction)) continue
                val minuteOfServiceDay =
                    trip.departureMinute + minutes[stationIndex] - minutes[trip.originIndex]
                val time = day.atStartOfDay().plusMinutes(minuteOfServiceDay.toLong())
                if (!time.isBefore(from)) result += Departure(time, trip.unconfirmed)
            }
        }
        return result.sortedBy { it.time }.take(count)
    }

    /** Quale tabella vale in una certa data. */
    fun dayTypeOf(date: LocalDate): DayType = when {
        date in timetable.holidays -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
        else -> DayType.WEEKDAY
    }

    /**
     * Una corsa passa da una stazione se la stazione è sulla sua strada,
     * cioè dall'origine in poi nel verso di marcia. Serve per le corse
     * che partono a metà linea (es. da Acilia verso Roma).
     */
    private fun passesThrough(trip: Trip, stationIndex: Int, direction: Direction): Boolean =
        when (direction) {
            Direction.TO_OSTIA -> stationIndex >= trip.originIndex  // indici crescenti
            Direction.TO_ROMA -> stationIndex <= trip.originIndex   // indici decrescenti
        }

    private fun indexOf(stationId: String): Int =
        indexById[stationId] ?: throw IllegalArgumentException("Stazione sconosciuta: $stationId")
}
