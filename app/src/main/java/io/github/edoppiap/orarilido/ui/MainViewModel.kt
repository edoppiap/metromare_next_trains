package io.github.edoppiap.orarilido.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edoppiap.orarilido.OrariApp
import io.github.edoppiap.orarilido.data.PrimaryMode
import io.github.edoppiap.orarilido.schedule.Departure
import io.github.edoppiap.orarilido.schedule.DayType
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.ScheduleProvider
import io.github.edoppiap.orarilido.schedule.Station
import io.github.edoppiap.orarilido.schedule.SunTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

// ---- Stato della schermata: tutto ciò che la UI deve disegnare, già "masticato" ----

data class MainUiState(
    val ready: Boolean = false,
    /** Cosa c'è "In primo piano": la stazione più vicina o le preferite. */
    val mode: PrimaryMode = PrimaryMode.NEAREST,
    /** Schede della sezione "In primo piano" (vuota se Preferite e nessuna preferita). */
    val primaryCards: List<StationCardUi> = emptyList(),
    /** Schede di tutte le altre stazioni, per distanza (per ora: fermate sulla linea). */
    val otherCards: List<StationCardUi> = emptyList(),
    /** Stazioni evidenziate sulla mappa = quelle in primo piano. */
    val highlightedIds: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val stations: List<Station> = emptyList(),
    /** Es. "9/11/2026": data di inizio validità dell'orario in uso. */
    val validFrom: String = "",
    /** Mappa in versione notturna (tra tramonto e alba), indipendente dal tema dell'app. */
    val mapNight: Boolean = false,
)

/** La scheda di una stazione: nome e una riga per ogni direzione servita. */
data class StationCardUi(
    val stationId: String,
    val name: String,
    val favorite: Boolean,
    val blocks: List<DirectionBlock>,
)

/** Una riga "Verso Roma" / "Verso Ostia" con i suoi prossimi treni. */
data class DirectionBlock(
    val direction: Direction,
    val title: String,       // "Verso Roma"
    val terminus: String,    // "Porta San Paolo"
    val rows: List<DepartureRow>,
)

/** Una riga: "tra 4 min · 08:17". */
data class DepartureRow(
    val wait: String,        // "4", "ora", "1 h 05"
    val waitUnit: String,    // "min" oppure "" (per "ora" e per le ore)
    val clock: String,       // "08:17" oppure "domani 05:15"
    val unconfirmed: Boolean,
    val imminent: Boolean,   // entro 3 minuti: colore corallo
)

/**
 * Il "cervello" della schermata: prende orario e stazione e produce
 * [MainUiState]. Sopravvive alle rotazioni dello schermo (è un ViewModel).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val orariApp = app as OrariApp

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var provider: ScheduleProvider? = null
    /**
     * La stazione "vicina". Finché non c'è il GPS (fase 4) è l'ultima usata;
     * serve anche come riferimento per ordinare le altre schede.
     */
    private var stationId: String? = null
    private var favorites: Set<String> = emptySet()
    private var mode: PrimaryMode = PrimaryMode.NEAREST

    init {
        viewModelScope.launch {
            // Questi due caricamenti sono già partiti in OrariApp.onCreate:
            // qui aspettiamo solo che finiscano (di solito pochi millisecondi).
            val p = orariApp.schedule.await()
            val saved = orariApp.savedPrefs.await()
            provider = p
            val validIds = p.stations.map { it.id }.toSet()
            // Se una stazione salvata non esiste più (orario cambiato), la ignoriamo.
            stationId = saved.lastStationId?.takeIf { it in validIds } ?: p.stations.first().id
            favorites = saved.favorites intersect validIds
            mode = saved.mode
            refresh()
        }
    }

    /** Ricalcola schede e prossimi treni rispetto all'ora attuale. */
    fun refresh() {
        val p = provider ?: return
        val id = stationId ?: return
        val now = LocalDateTime.now(ROME)

        // Distanza "in fermate" dalla stazione di riferimento (fase 4: in metri dall'utente).
        val refIndex = p.stations.indexOfFirst { it.id == id }
        val byDistance = p.stations.withIndex()
            .sortedWith(compareBy({ abs(it.index - refIndex) }, { it.index }))
            .map { it.value }

        // In primo piano: la stazione vicina, oppure le preferite (in ordine di distanza).
        val primary = when (mode) {
            PrimaryMode.NEAREST -> byDistance.take(1)
            PrimaryMode.FAVORITES -> byDistance.filter { it.id in favorites }
        }
        val primaryIds = primary.map { it.id }.toSet()
        val others = byDistance.filter { it.id !in primaryIds }

        // 14 stazioni × 2 direzioni × 3 treni: calcolo di pochi microsecondi.
        _state.value = MainUiState(
            ready = true,
            mode = mode,
            primaryCards = primary.map { buildCard(p, it, now) },
            otherCards = others.map { buildCard(p, it, now) },
            highlightedIds = primaryIds,
            favorites = favorites,
            stations = p.stations,
            validFrom = p.validFrom.format(DATE),
            mapNight = SunTimes.isNight(ZonedDateTime.now(ROME)),
        )
        if (dayRequest != null) buildDay()  // aggiorna anche "passati / prossimo" nella giornata
    }

    /** Selettore "Vicina | Preferite". La scelta viene ricordata. */
    fun setMode(newMode: PrimaryMode) {
        mode = newMode
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.setMode(newMode) }
    }

    /** Stella su una scheda: aggiunge o toglie la stazione dalle preferite. */
    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        refresh()
        val snapshot = favorites
        viewModelScope.launch { orariApp.stationPrefs.setFavorites(snapshot) }
    }

    // ---- Schermata "giornata intera" ----

    private val _day = MutableStateFlow<DayUi?>(null)
    /** null = schermata chiusa. */
    val day: StateFlow<DayUi?> = _day.asStateFlow()

    private data class DayRequest(val stationId: String, val direction: Direction, val date: LocalDate)
    private var dayRequest: DayRequest? = null

    /** Apre gli orari di tutta la giornata di oggi, per una stazione e direzione. */
    fun openDay(stationId: String, direction: Direction) {
        dayRequest = DayRequest(stationId, direction, serviceToday())
        buildDay()
    }

    fun closeDay() {
        dayRequest = null
        _day.value = null
    }

    /** Giorno precedente (−1) o successivo (+1). */
    fun shiftDay(days: Long) {
        dayRequest = dayRequest?.let { it.copy(date = it.date.plusDays(days)) }
        buildDay()
    }

    fun setDayDirection(direction: Direction) {
        dayRequest = dayRequest?.copy(direction = direction)
        buildDay()
    }

    private fun buildDay() {
        val p = provider ?: return
        val req = dayRequest ?: return
        val now = LocalDateTime.now(ROME).truncatedTo(ChronoUnit.MINUTES)
        val departures = p.departuresForDay(req.stationId, req.direction, req.date)
        // Il "prossimo treno" ha senso solo per la giornata in corso.
        val next = if (req.date == serviceToday()) departures.firstOrNull { !it.time.isBefore(now) } else null

        // Raggruppa per ora "di servizio": le corse dopo mezzanotte (ora 24)
        // finiscono in fondo, etichettate "00".
        val start = req.date.atStartOfDay()
        val hours = departures
            .groupBy { Duration.between(start, it.time).toHours() }
            .toSortedMap()
            .map { (hour, list) ->
                HourRow(
                    label = "%02d".format(hour % 24),
                    cells = list.map { d ->
                        MinuteCell(
                            text = "%02d".format(d.time.minute),
                            unconfirmed = d.unconfirmed,
                            past = d.time.isBefore(now),
                            next = d == next,
                        )
                    },
                )
            }

        val today = serviceToday()
        _day.value = DayUi(
            stationName = p.stations.first { it.id == req.stationId }.name,
            relativeDay = when (req.date) {
                today -> "Oggi"
                today.plusDays(1) -> "Domani"
                today.minusDays(1) -> "Ieri"
                else -> null
            },
            dateLabel = req.date.format(DAY_LABEL),
            dayTypeLabel = when (p.dayTypeOf(req.date)) {
                DayType.WEEKDAY -> "Orario feriale"
                DayType.SATURDAY -> "Orario del sabato"
                DayType.HOLIDAY -> "Orario festivo"
            },
            directions = DIRECTIONS
                .filter { p.servesDirection(req.stationId, it.direction) }
                .map { DirectionTab(it.direction, it.title) },
            selectedDirection = req.direction,
            hours = hours,
            nextHourIndex = hours.indexOfFirst { h -> h.cells.any { it.next } }.coerceAtLeast(0),
        )
    }

    /**
     * La "giornata di servizio" in corso: fino alle 3 di notte valgono ancora
     * le corse del giorno prima (gli ultimi treni passano dopo mezzanotte).
     */
    private fun serviceToday(): LocalDate {
        val now = LocalDateTime.now(ROME)
        return if (now.hour < 3) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    private fun buildCard(p: ScheduleProvider, station: Station, now: LocalDateTime) = StationCardUi(
        stationId = station.id,
        name = station.name,
        favorite = station.id in favorites,
        blocks = DIRECTIONS
            .filter { p.servesDirection(station.id, it.direction) }  // ai capolinea una sola direzione
            .map { d ->
                DirectionBlock(
                    direction = d.direction,
                    title = d.title,
                    terminus = d.terminus,
                    rows = p.nextDepartures(station.id, d.direction, now, ROWS_PER_DIRECTION)
                        .map { toRow(it, now) },
                )
            },
    )

    private fun toRow(dep: Departure, now: LocalDateTime): DepartureRow {
        val minutes = Duration.between(now, dep.time).toMinutes()  // arrotondato per difetto
        val (wait, unit) = when {
            minutes < 1 -> "ora" to ""
            minutes < 60 -> minutes.toString() to "min"
            else -> "%d h %02d".format(minutes / 60, minutes % 60) to ""
        }
        val clock = dep.time.format(TIME)
        val tomorrow = dep.time.toLocalDate().isAfter(now.toLocalDate())
        return DepartureRow(
            wait = wait,
            waitUnit = unit,
            clock = if (tomorrow) "domani $clock" else clock,
            unconfirmed = dep.unconfirmed,
            imminent = minutes <= 3,
        )
    }

    private data class DirectionInfo(val direction: Direction, val title: String, val terminus: String)

    private companion object {
        // Gli orari sono quelli di Roma, anche se il telefono avesse un altro fuso.
        /** Treni mostrati per direzione in ogni scheda. */
        const val ROWS_PER_DIRECTION = 3
        val ROME: ZoneId = ZoneId.of("Europe/Rome")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
        /** Es. "giovedì 12 novembre" */
        val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
        val DIRECTIONS = listOf(
            DirectionInfo(Direction.TO_ROMA, "Verso Roma", "Porta San Paolo"),
            DirectionInfo(Direction.TO_OSTIA, "Verso Ostia", "Cristoforo Colombo"),
        )
    }
}
