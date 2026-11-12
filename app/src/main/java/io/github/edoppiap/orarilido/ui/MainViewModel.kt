package io.github.edoppiap.orarilido.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edoppiap.orarilido.OrariApp
import io.github.edoppiap.orarilido.schedule.Departure
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.ScheduleProvider
import io.github.edoppiap.orarilido.schedule.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- Stato della schermata: tutto ciò che la UI deve disegnare, già "masticato" ----

data class MainUiState(
    val ready: Boolean = false,
    val stationId: String = "",
    val stationName: String = "",
    val blocks: List<DirectionBlock> = emptyList(),
    val stations: List<Station> = emptyList(),
    /** Es. "9/11/2026": data di inizio validità dell'orario in uso. */
    val validFrom: String = "",
)

/** Un riquadro "Verso Roma" / "Verso Ostia". */
data class DirectionBlock(
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
    private var stationId: String? = null

    init {
        viewModelScope.launch {
            // Questi due caricamenti sono già partiti in OrariApp.onCreate:
            // qui aspettiamo solo che finiscano (di solito pochi millisecondi).
            val p = orariApp.schedule.await()
            val saved = orariApp.savedStationId.await()
            provider = p
            // Se la stazione salvata non esiste più (orario cambiato), si usa la prima.
            stationId = saved?.takeIf { id -> p.stations.any { it.id == id } }
                ?: p.stations.first().id
            refresh()
        }
    }

    /** Ricalcola i prossimi treni rispetto all'ora attuale. */
    fun refresh() {
        val p = provider ?: return
        val id = stationId ?: return
        val now = LocalDateTime.now(ROME)
        val station = p.stations.first { it.id == id }

        val blocks = DIRECTIONS
            .filter { p.servesDirection(id, it.direction) }
            .map { d ->
                DirectionBlock(
                    title = d.title,
                    terminus = d.terminus,
                    rows = p.nextDepartures(id, d.direction, now, ROWS_PER_DIRECTION).map { toRow(it, now) },
                )
            }

        _state.value = MainUiState(
            ready = true,
            stationId = id,
            stationName = station.name,
            blocks = blocks,
            stations = p.stations,
            validFrom = p.validFrom.format(DATE),
        )
    }

    /** Scelta manuale di una stazione: la mostriamo e la ricordiamo per il prossimo avvio. */
    fun selectStation(id: String) {
        stationId = id
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.setLastStationId(id) }
    }

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
        /** Treni calcolati per direzione: i primi 3 nella scheda, tutti a tendina alzata. */
        const val ROWS_PER_DIRECTION = 8
        val ROME: ZoneId = ZoneId.of("Europe/Rome")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
        val DIRECTIONS = listOf(
            DirectionInfo(Direction.TO_ROMA, "Verso Roma", "Porta San Paolo"),
            DirectionInfo(Direction.TO_OSTIA, "Verso Ostia", "Cristoforo Colombo"),
        )
    }
}
