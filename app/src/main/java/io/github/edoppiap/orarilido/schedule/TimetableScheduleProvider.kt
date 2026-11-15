package io.github.edoppiap.orarilido.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

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
        // Si ragiona al minuto: un treno delle 08:00 viene mostrato
        // ("tra 0 min") fino alle 08:00:59.
        val from = now.truncatedTo(ChronoUnit.MINUTES)

        // Guardiamo tre giornate di servizio: ieri (per le corse dopo
        // mezzanotte), oggi, e domani (per il primo treno dopo l'ultimo).
        return (-1L..1L)
            .flatMap { departuresForDay(stationId, direction, from.toLocalDate().plusDays(it)) }
            .filter { !it.time.isBefore(from) }
            .sortedBy { it.time }
            .take(count)
    }

    override fun departuresForDay(
        stationId: String,
        direction: Direction,
        serviceDate: LocalDate,
    ): List<Departure> {
        if (!servesDirection(stationId, direction)) return emptyList()
        val stationIndex = indexOf(stationId)
        val minutes = timetable.minutesFromTerminus.getValue(direction)
        val trips = timetable.trips.getValue(dayTypeOf(serviceDate)).getValue(direction)
        return trips
            .filter { passesThrough(it, stationIndex, direction) }
            .map { trip ->
                val minuteOfServiceDay =
                    trip.departureMinute + minutes[stationIndex] - minutes[trip.originIndex]
                val start = serviceDate.atStartOfDay()
                Departure(
                    start.plusMinutes(minuteOfServiceDay.toLong()),
                    trip.unconfirmed,
                    origin = start.plusMinutes(trip.departureMinute.toLong()),
                )
            }
            .sortedBy { it.time }  // le corse aggiuntive sono in coda alla lista: riordiniamo
    }

    override fun minutesFromTerminus(direction: Direction): IntArray =
        timetable.minutesFromTerminus.getValue(direction).copyOf()  // copia: nessuno può modificare l'orario

    /** Quale tabella vale in una certa data. */
    override fun dayTypeOf(date: LocalDate): DayType = when {
        date in timetable.holidays -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY -> DayType.SATURDAY
        else -> DayType.WEEKDAY
    }

    // ---- Bus sostitutivi ----

    override val busStops: List<BusStop> get() = timetable.buses?.stops.orEmpty()

    private val stopByKey: Map<Pair<String, Direction>, BusStop> =
        busStops.associateBy { it.stationId to it.direction }

    override fun busStop(stationId: String, direction: Direction): BusStop? = stopByKey[stationId to direction]

    override fun hasBuses(dayType: DayType): Boolean = timetable.buses?.days?.contains(dayType) == true

    override fun busesForDay(stationId: String, direction: Direction, serviceDate: LocalDate): List<BusPass> {
        val buses = timetable.buses ?: return emptyList()
        if (dayTypeOf(serviceDate) !in buses.days || busStop(stationId, direction) == null) return emptyList()
        val minutes = timetable.minutesFromTerminus.getValue(direction)[indexOf(stationId)]
        val start = serviceDate.atStartOfDay()
        return buses.departures.getValue(direction).map { dep ->
            val departure = start.plusMinutes(dep.toLong())
            val atTerminus = minutes == 0
            BusPass(
                departure = departure,
                atTerminus = atTerminus,
                // Gli orari di passaggio non sono pubblicati: stima larga, solo per sapere
                // quando toglierlo (il bus è più lento del treno e si ferma ai semafori).
                visibleUntil = if (atTerminus) departure
                    else departure.plusMinutes((minutes * BUS_SLOWNESS).roundToLong() + BUS_MARGIN_MIN),
                sortTime = departure.plusMinutes(minutes.toLong()),
            )
        }
    }

    override fun nextBus(stationId: String, direction: Direction, now: LocalDateTime): BusPass? {
        val from = now.truncatedTo(ChronoUnit.MINUTES)
        // Ieri (un bus delle 23:45 si vede ancora dopo mezzanotte) e oggi.
        return (-1L..0L)
            .flatMap { busesForDay(stationId, direction, from.toLocalDate().plusDays(it)) }
            .filter { !it.visibleUntil.isBefore(from) }
            .minByOrNull { it.departure }
    }

    override fun trainPossible(stationId: String, direction: Direction, now: LocalDateTime): Boolean {
        if (!servesDirection(stationId, direction)) {
            // Capolinea d'arrivo: conta il treno che ci arriva, cioè quello della stazione prima.
            val i = indexOf(stationId)
            val before = if (direction == Direction.TO_OSTIA) i - 1 else i + 1
            return trainPossible(timetable.stations[before].id, direction, now)
        }
        val from = now.minusMinutes(TRAIN_PASSED_MIN)
        val to = now.plusMinutes(TRAIN_COMING_MIN)
        return (-1L..1L)
            .flatMap { departuresForDay(stationId, direction, now.toLocalDate().plusDays(it)) }
            .any { !it.time.isBefore(from) && !it.time.isAfter(to) }
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

    private companion object {
        /** Il bus ci mette al massimo una volta e mezza il treno... */
        const val BUS_SLOWNESS = 1.5
        /** ...più 10 minuti di margine. */
        const val BUS_MARGIN_MIN = 10L
        /** Go: un treno passato da meno di tanto può essere quello su cui sei (in ritardo). */
        const val TRAIN_PASSED_MIN = 10L
        /** Go: un treno in arrivo entro tanto può essere già lì (gli intervalli arrivano a 30 min). */
        const val TRAIN_COMING_MIN = 20L
    }
}
