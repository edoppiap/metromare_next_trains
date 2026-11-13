package io.github.edoppiap.orarilido.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edoppiap.orarilido.OrariApp
import io.github.edoppiap.orarilido.data.AppSettings
import io.github.edoppiap.orarilido.data.PrimaryMode
import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.schedule.Departure
import io.github.edoppiap.orarilido.schedule.DayType
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.schedule.ScheduleProvider
import io.github.edoppiap.orarilido.schedule.Station
import io.github.edoppiap.orarilido.schedule.SunTimes
import io.github.edoppiap.orarilido.go.GoDetector
import io.github.edoppiap.orarilido.go.GoSample
import io.github.edoppiap.orarilido.go.GoState
import io.github.edoppiap.orarilido.go.LineTrack
import io.github.edoppiap.orarilido.go.RideSimulator
import io.github.edoppiap.orarilido.location.LocationFix
import io.github.edoppiap.orarilido.walk.WalkGraph
import io.github.edoppiap.orarilido.walk.WalkRouter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** Posizione dell'utente (solo in memoria), null se non disponibile. */
    val userLocation: GeoPoint? = null,
    /**
     * La stazione di riferimento per inquadrature e percorso: la più vicina,
     * oppure (in modalità Preferite) la preferita più vicina. null senza GPS.
     */
    val nearestStation: Station? = null,
    /** A piedi o in bici: grandezza dei cerchi sulla mappa. */
    val travelMode: TravelMode = TravelMode.WALK,
    /** Modalità Go: null = spenta (non sei sul treno). */
    val go: GoUi? = null,
    /** Simulazione di viaggio in corso (solo build di prova). */
    val simulating: Boolean = false,
    /** Percorso suggerito verso la stazione più vicina (null = nessuno: senza GPS, in Go, fuori zona). */
    val route: RouteUi? = null,
    /** Le scelte della pagina Impostazioni. */
    val settings: AppSettings = AppSettings(),
    /** Es. "9 novembre 2026", per la pagina Impostazioni. */
    val validFromLong: String = "",
)

/** Il percorso da disegnare sulla mappa: punti (da te alla stazione) e "12 min". */
data class RouteUi(
    val stationId: String,
    val mode: TravelMode,
    val points: List<GeoPoint>,
    /** Minuti mostrati, già alla velocità scelta nelle Impostazioni. */
    val minutes: Int,
    /** Minuti alla velocità "normale" (quella della rete di strade): servono per riscalare. */
    val baseMinutes: Double,
)

/**
 * Modalità Go, già pronta da disegnare.
 * Con [recognizing] = true sembra un treno ma non è ancora confermato:
 * la tendina mostra solo la riga "Sei in treno? Sto riconoscendo la corsa…".
 */
data class GoUi(
    val recognizing: Boolean,
    /** "Verso Ostia" / "Verso Roma" (vuoto durante il riconoscimento). */
    val directionTitle: String = "",
    /** Le fermate davanti, dalla prossima al capolinea. */
    val stops: List<GoStopUi> = emptyList(),
    /** Cosa mostra la striscia a mappa piena: la fermata fissata col tocco, o la prossima. */
    val stripStop: GoStopUi? = null,
    /** Le prossime fermate da inquadrare sulla mappa insieme a te. */
    val focusStations: List<Station> = emptyList(),
)

/** Una fermata davanti: "Lido Centro · tra 12 min · 08:29". */
data class GoStopUi(
    val stationId: String,
    val name: String,
    val row: DepartureRow,
    /** Fissata col tocco ("la mia fermata"): la striscia mostra questa. */
    val pinned: Boolean,
)

/** La scheda di una stazione: nome e una riga per ogni direzione servita. */
data class StationCardUi(
    val stationId: String,
    val name: String,
    val favorite: Boolean,
    /** Distanza dall'utente, es. "350 m"; null senza posizione. */
    val distance: String?,
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
    /** Non fai in tempo ad arrivare in stazione (Impostazioni → treni sbiaditi). */
    val missed: Boolean = false,
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
    private var travelMode: TravelMode = TravelMode.WALK
    private var settings = AppSettings()
    /** Ultima posizione nota: SOLO in memoria, mai salvata (privacy). */
    private var userLocation: GeoPoint? = null

    // ---- Modalità Go ----
    /** Il riconoscitore del treno: pronto quando sono caricati orario e tracciato. */
    private var detector: GoDetector? = null
    /** Il tracciato come "righello" (serve anche al simulatore). */
    private var track: LineTrack? = null
    /**
     * L'utente ha chiuso Go con la X: non si riaccende sullo stesso viaggio
     * (sei ancora sul treno, la riconoscerebbe subito). Torna possibile quando
     * il riconoscitore esce da solo (sei sceso, ti sei fermato).
     */
    private var goDismissed = false
    /** Fermata fissata toccando la sua scheda (la striscia mostra quella). */
    private var pinnedStopId: String? = null
    /**
     * L'ultima fermata fissata in ciascuna direzione (salvata): a ogni nuovo
     * viaggio in quella direzione viene fissata da sola, se è davanti a te.
     */
    private var goDefaults: Map<Direction, String> = emptyMap()
    /** Il viaggio in corso ha già avuto la sua fermata predefinita (si applica una volta sola). */
    private var goDefaultApplied = false

    // ---- Percorso suggerito (a piedi / in bici) ----
    /** Rete di strade attorno alle stazioni: si carica DOPO gli orari (vedi init). */
    private var walkGraph: WalkGraph? = null
    /** Ultimo percorso calcolato (null = nessuno). */
    private var route: RouteUi? = null
    /** Per cosa è stato chiesto l'ultimo calcolo: stazione, mezzo, posizione. */
    private var routeRequest: Triple<String, TravelMode, GeoPoint>? = null
    private var routeJob: Job? = null

    private val _fastGps = MutableStateFlow(false)
    /** true = la modalità Go vuole una posizione ogni 5 s (vedi LocationSource). */
    val fastGps: StateFlow<Boolean> = _fastGps.asStateFlow()

    // ---- Simulatore (solo build di prova) ----
    private var simulation: Job? = null
    /** Durante una simulazione l'orologio dell'app va più veloce; null = ora vera. */
    private var simClock: (() -> LocalDateTime)? = null

    /** L'ora di Roma (o quella finta della simulazione). */
    private fun now(): LocalDateTime = simClock?.invoke() ?: LocalDateTime.now(ROME)

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
            travelMode = saved.travelMode
            settings = saved.settings
            goDefaults = saved.goStops.filterValues { it in validIds }
            // Se il GPS ha già risposto mentre caricavamo, usiamo subito la posizione.
            userLocation?.let { updateNearest(it) }
            refresh()

            // Il tracciato serve solo alla modalità Go: si legge DOPO, per non
            // ritardare gli orari all'apertura.
            val track = withContext(Dispatchers.IO) {
                LineTrack(LineTrack.parsePoints(app.assets.open("line.json").bufferedReader().use { it.readText() }), p.stations)
            }
            this@MainViewModel.track = track
            detector = GoDetector(track, p.stations, p::minutesFromTerminus)

            // La rete di strade per il percorso suggerito (~0,7 MB): ancora dopo,
            // e su un altro thread. Il percorso compare quando è pronta.
            walkGraph = withContext(Dispatchers.Default) {
                WalkGraph.parse(app.assets.open("walk.bin").use { it.readBytes() })
            }
            io.github.edoppiap.orarilido.logStartup("Rete strade per i percorsi pronta")
            refresh()
        }
    }

    /** Ricalcola schede e prossimi treni rispetto all'ora attuale. */
    fun refresh() {
        val p = provider ?: return
        val id = stationId ?: return
        val now = now()

        val user = userLocation
        // Ordine per distanza: in metri dall'utente se la posizione è nota,
        // altrimenti "in fermate" dalla stazione di riferimento (l'ultima usata).
        val byDistance = if (user != null) {
            Geo.sortByDistance(p.stations, user)
        } else {
            val refIndex = p.stations.indexOfFirst { it.id == id }
            p.stations.withIndex()
                .sortedWith(compareBy({ abs(it.index - refIndex) }, { it.index }))
                .map { it.value }
        }

        // In primo piano: la stazione vicina, oppure le preferite (in ordine di distanza).
        val primary = when (mode) {
            PrimaryMode.NEAREST -> byDistance.take(1)
            PrimaryMode.FAVORITES -> byDistance.filter { it.id in favorites }
        }
        val primaryIds = primary.map { it.id }.toSet()
        val others = byDistance.filter { it.id !in primaryIds }

        // Modalità Go: se sei sul treno, la mappa ti mostra "agganciato" ai binari,
        // con le fermate davanti evidenziate e la prossima come riferimento.
        // Modalità treno spenta nelle Impostazioni: come se il riconoscitore non ci fosse
        // (la simulazione della build di prova funziona comunque).
        val goState = detector?.state?.takeIf { settings.goOn || simulation != null }
        if (goState == GoState.Off) goDismissed = false  // sceso dal treno: la X non vale più
        val onTrain = (goState as? GoState.OnTrain)?.takeUnless { goDismissed }
        // Inizio di un viaggio: si fissa da sola la fermata scelta l'ultima volta
        // in questa direzione (se è ancora davanti a te).
        if (onTrain == null) {
            goDefaultApplied = false
            pinnedStopId = null  // viaggio finito: la fermata fissata vale solo per quello
        } else if (!goDefaultApplied) {
            goDefaultApplied = true
            val default = goDefaults[onTrain.direction]
            if (default != null && onTrain.ahead.any { it.station.id == default }) pinnedStopId = default
        }
        val go = when {
            goDismissed -> null
            onTrain != null -> buildGo(onTrain, now)
            goState is GoState.Detecting -> GoUi(recognizing = true)
            else -> null
        }
        _fastGps.value = go != null && simulation == null

        // Stazione di riferimento: la più vicina, ma in modalità Preferite la preferita
        // più vicina (es. vai a Stella Polare per i posti a sedere, o ad Acilia Sud
        // in bici perché Acilia è in salita: la scegli tu mettendola tra le preferite).
        val focus = if (user == null) null else primary.firstOrNull() ?: byDistance.first()
        // Percorso suggerito: solo con il GPS, solo verso la stazione di riferimento, mai in Go,
        // e solo se non è stato spento nelle Impostazioni.
        if (settings.routeOn && go == null && user != null && focus != null) updateRoute(user, focus.id) else clearRoute()
        // Se è cambiata la velocità nelle Impostazioni, i minuti del percorso si riscalano.
        route = route?.let { it.copy(minutes = scaledMinutes(it.baseMinutes, it.mode)) }

        // 14 stazioni × 2 direzioni × 3 treni: calcolo di pochi microsecondi.
        _state.value = MainUiState(
            ready = true,
            mode = mode,
            primaryCards = primary.map { buildCard(p, it, now, go == null) },
            otherCards = others.map { buildCard(p, it, now, go == null) },
            highlightedIds = if (onTrain != null) onTrain.ahead.map { it.station.id }.toSet() else primaryIds,
            favorites = favorites,
            stations = p.stations,
            validFrom = p.validFrom.format(DATE),
            mapNight = SunTimes.isNight(now.atZone(ROME)),
            userLocation = onTrain?.pointOnLine ?: user,
            nearestStation = onTrain?.ahead?.firstOrNull()?.station ?: focus,
            travelMode = travelMode,
            go = go,
            simulating = simulation != null,
            route = route,
            settings = settings,
            validFromLong = p.validFrom.format(DATE_LONG),
        )
        if (dayRequest != null) buildDay()  // aggiorna anche "passati / prossimo" nella giornata
    }

    /** Le schede delle fermate davanti, con "tra 12 min · 08:29". */
    private fun buildGo(s: GoState.OnTrain, now: LocalDateTime): GoUi {
        // Una fermata appena passata senza nuove posizioni (GPS perso un attimo)
        // resta "ora" per un minuto, poi sparisce.
        val stops = s.ahead
            .filter { !it.time.isBefore(now.minusMinutes(1)) }
            .map { a ->
                GoStopUi(
                    stationId = a.station.id,
                    name = a.station.name,
                    row = toRow(Departure(a.time), now),
                    pinned = a.station.id == pinnedStopId,
                )
            }
        if (stops.none { it.pinned }) pinnedStopId = null  // fermata fissata ormai passata
        return GoUi(
            recognizing = false,
            directionTitle = DIRECTIONS.first { it.direction == s.direction }.title,
            stops = stops,
            stripStop = stops.firstOrNull { it.pinned } ?: stops.firstOrNull(),
            // Senza fermata fissata: tu + le prossime due, abbastanza per orientarsi al buio.
            // Con la fermata fissata: tu + tutto il tratto fino a lei (lo zoom si
            // stringe da solo man mano che ti avvicini).
            focusStations = s.ahead.map { it.station }.let { ahead ->
                val pinnedIndex = ahead.indexOfFirst { it.id == pinnedStopId }
                if (pinnedIndex >= 0) ahead.take(pinnedIndex + 1) else ahead.take(2)
            },
        )
    }

    /**
     * Nuova posizione dal GPS. Aggiorna la stazione "vicina" e, se è cambiata,
     * ne salva l'id (solo l'id, mai le coordinate) per il prossimo avvio.
     */
    fun onLocation(fix: LocationFix) {
        if (simulation != null) return  // durante la simulazione conta solo la posizione finta
        // Il riconoscitore vuole l'ora in cui la posizione è stata PRESA
        // (l'ultima nota, all'apertura, può avere qualche minuto).
        val sample = GoSample(fix.point, now().minusNanos(fix.ageMillis * 1_000_000))
        // Posizioni troppo imprecise confonderebbero il riconoscimento del treno.
        onSample(sample, feedDetector = (fix.accuracy ?: 0f) <= MAX_GO_ACCURACY_M)
    }

    private fun onSample(sample: GoSample, feedDetector: Boolean = true) {
        userLocation = sample.point
        // Modalità treno spenta: il riconoscitore non riceve posizioni (salvo in simulazione).
        if (feedDetector && (settings.goOn || simulation != null)) detector?.update(sample)
        if (provider == null) return  // orario non ancora caricato: ci pensa init
        updateNearest(sample.point)
        refresh()
    }

    /** Aggiorna la stazione "vicina" e ne salva l'id (mai le coordinate) per il prossimo avvio. */
    private fun updateNearest(point: GeoPoint) {
        val p = provider ?: return
        val nearest = Geo.sortByDistance(p.stations, point).first().id
        if (nearest != stationId) {
            stationId = nearest
            viewModelScope.launch { orariApp.stationPrefs.setLastStationId(nearest) }
        }
    }

    /**
     * Ricalcola il percorso verso [stationId] se serve: è cambiata la stazione o
     * il mezzo, oppure ti sei spostato di almeno [ROUTE_MIN_MOVE_M]. Il calcolo
     * (A*, qualche decina di ms) gira su un altro thread; a percorso pronto
     * si aggiorna solo quel campo dello stato.
     */
    private fun updateRoute(user: GeoPoint, stationId: String) {
        val g = walkGraph ?: return  // rete non ancora caricata: si riprova al prossimo refresh
        val mode = travelMode
        val last = routeRequest
        if (last != null && last.first == stationId && last.second == mode &&
            Geo.distanceMeters(last.third, user) < ROUTE_MIN_MOVE_M
        ) return
        routeRequest = Triple(stationId, mode, user)
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            val maxMinutes = if (mode == TravelMode.BIKE) ROUTE_MAX_BIKE_MIN else ROUTE_MAX_WALK_MIN
            val t0 = System.nanoTime()
            val found = withContext(Dispatchers.Default) { WalkRouter.route(g, user, stationId, mode, maxMinutes) }
            // Misura (solo log, come OrariAvvio): quanto costa il calcolo sul telefono.
            android.util.Log.i("OrariAvvio", "Percorso $mode verso $stationId: ${(System.nanoTime() - t0) / 1_000_000} ms" +
                (found?.let { ", ${it.minutes.roundToInt()} min, ${it.meters.roundToInt()} m" } ?: ", nessuno"))
            route = found?.let { RouteUi(stationId, mode, it.points, scaledMinutes(it.minutes, mode), it.minutes) }
            // Con i treni sbiaditi le schede usano i minuti del percorso: si ricalcola tutto.
            if (settings.fadeMissed) refresh() else _state.value = _state.value.copy(route = route)
        }
    }

    /**
     * La rete di strade conta i minuti alla velocità "normale": qui si riportano
     * alla velocità scelta nelle Impostazioni (lepre = meno minuti).
     */
    private fun scaledMinutes(baseMinutes: Double, mode: TravelMode): Int {
        val normal = if (mode == TravelMode.BIKE) Geo.BIKE_METERS_PER_MINUTE else Geo.WALK_METERS_PER_MINUTE
        return (baseMinutes * normal / settings.metersPerMinute(mode)).roundToInt().coerceAtLeast(1)
    }

    /** Pagina Impostazioni: ogni cambio si applica subito e viene salvato. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val new = change(settings)
        if (new == settings) return
        // Modalità treno spenta: si dimentica anche un riconoscimento a metà.
        if (!new.goOn && settings.goOn) {
            detector?.reset()
            pinnedStopId = null
        }
        settings = new
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.setSettings(new) }
    }

    /** Impostazioni → "Cancella preferite e fermate salvate". */
    fun clearSavedChoices() {
        favorites = emptySet()
        goDefaults = emptyMap()
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.clearFavoritesAndGoStops() }
    }

    private fun clearRoute() {
        routeJob?.cancel()
        routeRequest = null
        route = null
    }

    /** Pulsante X: esci dalla modalità Go per questo viaggio. */
    fun exitGo() {
        goDismissed = true
        pinnedStopId = null
        refresh()
    }

    /** Tocco sulla scheda di una fermata in Go: la fissa nella striscia (o la sblocca). */
    fun togglePinnedStop(id: String) {
        pinnedStopId = if (pinnedStopId == id) null else id
        // La scelta diventa quella predefinita per questa direzione (sbloccare = nessuna).
        val direction = (detector?.state as? GoState.OnTrain)?.direction
        if (direction != null) {
            val chosen = pinnedStopId
            goDefaults = if (chosen != null) goDefaults + (direction to chosen) else goDefaults - direction
            viewModelScope.launch { orariApp.stationPrefs.setGoStop(direction, chosen) }
        }
        refresh()
    }

    /**
     * SOLO BUILD DI PROVA: un viaggio finto verso [direction], con l'orologio
     * dell'app accelerato ×[SIM_SPEED]. Verso Ostia da Vitinia, verso Roma da
     * Lido Centro, fino al capolinea; poi qualche minuto fermo, per vedere
     * l'uscita automatica. Il GPS vero viene ignorato finché dura.
     */
    fun simulateRide(direction: Direction) {
        val p = provider ?: return
        val d = detector ?: return
        val track = track ?: return
        stopSimulation()
        val ids = p.stations.map { it.id }
        val (from, to) = when (direction) {
            Direction.TO_OSTIA -> ids.indexOf("vitinia") to ids.lastIndex
            Direction.TO_ROMA -> ids.indexOf("lido_centro") to 0
        }
        // Orologio finto: parte dall'ora vera e corre SIM_SPEED volte più veloce.
        val start = LocalDateTime.now(ROME)
        val t0 = System.nanoTime()
        simClock = { start.plusNanos((System.nanoTime() - t0) * SIM_SPEED) }
        val samples = RideSimulator.ride(track, p.minutesFromTerminus(direction), from, to, start)
        d.reset()
        goDismissed = false
        pinnedStopId = null
        // LAZY: parte solo dopo essere stato salvato in "simulation", così il
        // "finally" sa riconoscere se è ancora lui la simulazione in corso.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (s in samples) {
                    val waitMs = Duration.between(now(), s.time).toMillis() / SIM_SPEED
                    if (waitMs > 0) delay(waitMs)
                    onSample(s)
                }
            } finally {
                // Fine (o interruzione): si torna all'ora e al GPS veri. Se nel
                // frattempo è partita un'altra simulazione, quella va lasciata stare.
                if (simulation === coroutineContext[Job]) {
                    simClock = null
                    simulation = null
                    d.reset()
                    refresh()
                }
            }
        }
        simulation = job
        job.start()
        refresh()
    }

    fun stopSimulation() {
        simulation?.cancel()
    }

    /** Selettore "Vicina | Preferite". La scelta viene ricordata. */
    fun setMode(newMode: PrimaryMode) {
        mode = newMode
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.setMode(newMode) }
    }

    /** Selettore 🚶 | 🚲 sulla mappa. La scelta viene ricordata. */
    fun setTravelMode(newMode: TravelMode) {
        travelMode = newMode
        refresh()
        viewModelScope.launch { orariApp.stationPrefs.setTravelMode(newMode) }
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
        val now = now().truncatedTo(ChronoUnit.MINUTES)
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
            // Nel selettore: prima Ostia poi Roma, come sulla mappa (Ostia a sinistra, Roma a destra).
            directions = DIRECTIONS.reversed()
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
        val now = now()
        return if (now.hour < 3) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    private fun buildCard(p: ScheduleProvider, station: Station, now: LocalDateTime, fadeAllowed: Boolean): StationCardUi {
        val reach = if (fadeAllowed) minutesToReach(station) else null
        return StationCardUi(
        stationId = station.id,
        name = station.name,
        favorite = station.id in favorites,
        distance = userLocation?.let { Geo.formatDistance(Geo.distanceMeters(it, station)) },
        blocks = DIRECTIONS
            .filter { p.servesDirection(station.id, it.direction) }  // ai capolinea una sola direzione
            .map { d ->
                DirectionBlock(
                    direction = d.direction,
                    title = d.title,
                    terminus = d.terminus,
                    rows = p.nextDepartures(station.id, d.direction, now, ROWS_PER_DIRECTION)
                        .map { toRow(it, now, reach) },
                )
            },
        )
    }

    /**
     * Impostazioni → treni sbiaditi: quanti minuti ti servono per arrivare alla
     * stazione col mezzo scelto (🚶|🚲) e alla velocità impostata. null = non si
     * sbiadisce niente (impostazione spenta o posizione sconosciuta).
     * Se c'è il percorso vero verso questa stazione si usa quello; altrimenti la
     * linea d'aria allungata di [DETOUR] (le strade non vanno mai dritte).
     */
    private fun minutesToReach(station: Station): Long? {
        if (!settings.fadeMissed) return null
        val user = userLocation ?: return null
        route?.takeIf { it.stationId == station.id && it.mode == travelMode }?.let { return it.minutes.toLong() }
        val meters = Geo.distanceMeters(user, station) * DETOUR
        return kotlin.math.ceil(meters / settings.metersPerMinute(travelMode)).toLong()
    }

    /** [reachMinutes] = minuti per arrivare in stazione: i treni che partono prima sono "persi". */
    private fun toRow(dep: Departure, now: LocalDateTime, reachMinutes: Long? = null): DepartureRow {
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
            missed = reachMinutes != null && minutes < reachMinutes,
        )
    }

    private data class DirectionInfo(val direction: Direction, val title: String, val terminus: String)

    private companion object {
        // Gli orari sono quelli di Roma, anche se il telefono avesse un altro fuso.
        /** Treni mostrati per direzione in ogni scheda. */
        const val ROWS_PER_DIRECTION = 3
        /** Posizioni meno precise di così non vengono date al riconoscitore del treno. */
        const val MAX_GO_ACCURACY_M = 50f
        /** Percorso: si ricalcola solo se ti sei spostato almeno di tanto (metri). */
        const val ROUTE_MIN_MOVE_M = 15.0
        /** Limiti delle zone coperte da walk.bin (vedi tools/osm_graph.py --write). */
        const val ROUTE_MAX_WALK_MIN = 60.0
        const val ROUTE_MAX_BIKE_MIN = 25.0
        /** Simulazione: l'orologio corre 5 volte più veloce (un viaggio di 30 min dura 6 min). */
        const val SIM_SPEED = 5L
        /** Treni sbiaditi senza percorso: le strade allungano la linea d'aria di circa un terzo. */
        const val DETOUR = 1.3
        val ROME: ZoneId = ZoneId.of("Europe/Rome")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
        val DATE_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
        /** Es. "giovedì 12 novembre" */
        val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
        val DIRECTIONS = listOf(
            DirectionInfo(Direction.TO_ROMA, "Verso Roma", "Porta San Paolo"),
            DirectionInfo(Direction.TO_OSTIA, "Verso Ostia", "Cristoforo Colombo"),
        )
    }
}
