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
import io.github.edoppiap.orarilido.schedule.BusPass
import io.github.edoppiap.orarilido.schedule.BusStop
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
import io.github.edoppiap.orarilido.alerts.StrikeDay
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
    /** Pulsante "Vai" visibile: GPS acceso, Go spenta, sei vicino a una stazione o ai binari. */
    val canGo: Boolean = false,
    /** Simulazione di viaggio in corso (solo build di prova). */
    val simulating: Boolean = false,
    /** Percorso suggerito verso la stazione più vicina (null = nessuno: senza GPS, in Go, fuori zona). */
    val route: RouteUi? = null,
    /** Le scelte della pagina Impostazioni. */
    val settings: AppSettings = AppSettings(),
    /** Es. "9 novembre 2026", per la pagina Impostazioni. */
    val validFromLong: String = "",
    /** Fermate dei bus sostitutivi, sempre sulla mappa da vicino (mai in Go). */
    val busStops: List<BusStopUi> = emptyList(),
    /** Sciopero oggi: riga in cima alla tendina (null = giorno normale). */
    val strike: StrikeDay? = null,
)

/** Una fermata del bus sostitutivo sulla mappa; il tocco mostra [title] e [detail]. */
data class BusStopUi(
    val point: GeoPoint,
    val title: String,   // "Bus sostitutivo verso Ostia"
    val detail: String,  // "Romagnoli/Staz.ne Acilia · lun–ven, parte 23:00 da Porta San Paolo"
    /** "→ Ostia" / "→ Roma" accanto al cartello; null se la fermata serve entrambi i versi. */
    val dirTag: String?,
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
    /** Il primo tratto è un "salto" in linea d'aria fino alla prima strada conosciuta. */
    val gap: Boolean = false,
)

/** Modalità Go, già pronta da disegnare. */
data class GoUi(
    /** Verso dove va il treno (selettore Ostia | Roma nell'intestazione). */
    val direction: Direction,
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
    /**
     * Finiti treni e bus della giornata: "primo treno 05:36" (sotto a
     * "Nessun treno fino a domani"). null = ci sono ancora passaggi.
     */
    val firstTomorrow: String? = null,
)

/** Una riga: "tra 4 min · 08:17". */
data class DepartureRow(
    val wait: String,        // "4", "ora", "1 h 05", oppure "Bus"
    val waitUnit: String,    // "min" oppure "" (per "ora" e per le ore)
    val clock: String,       // "08:17", oppure per il bus "parte 23:00" / "23:00 · bus"
    val unconfirmed: Boolean,
    val imminent: Boolean,   // entro 3 minuti: colore corallo
    /** Non fai in tempo ad arrivare in stazione (Impostazioni → treni sbiaditi). */
    val missed: Boolean = false,
    /** Bus sostitutivo (non un treno): la UI mette il cartello del bus davanti. */
    val bus: Boolean = false,
    /** Giorno di sciopero: treno a rischio, pallino ambra accanto all'orario. */
    val atRisk: Boolean = false,
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
    /** Build di prova: "Vai" sempre visibile, per vederlo anche da casa. */
    private val debugBuild = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Ultima posizione vera dal GPS (solo in memoria): serve al pulsante "Vai". */
    private var lastSample: GoSample? = null
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
            // Go parte solo col pulsante "Vai" (autoStart = false): il controllo
            // dell'orario serviva al riconoscimento automatico, qui non conta.
            detector = GoDetector(track, p.stations, p::minutesFromTerminus, autoStart = false)

            // La rete di strade per il percorso suggerito (~0,7 MB): ancora dopo,
            // e su un altro thread. Il percorso compare quando è pronta.
            walkGraph = withContext(Dispatchers.Default) {
                WalkGraph.parse(app.assets.open("walk.bin").use { it.readBytes() })
            }
            io.github.edoppiap.orarilido.logStartup("Rete strade per i percorsi pronta")
            refresh()
        }
    }

    /** Sciopero di oggi, dagli avvisi Cotral (lo passa MainActivity da AlertsViewModel). */
    private var strike: StrikeDay? = null

    fun setStrike(s: StrikeDay?) {
        if (s == strike) return
        strike = s
        refresh()
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
        // Riaprendo l'app dopo tanto (GPS spento in background) il viaggio può
        // essere già finito: in quel caso Go si chiude prima di disegnare.
        if (detector?.expireIfStale(now) == true) pinnedStopId = null
        val onTrain = detector?.state as? GoState.OnTrain
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
        val go = onTrain?.let { buildGo(it, now) }
        _fastGps.value = go != null && simulation == null
        // Il pallino in Go: dove dovresti essere ADESSO secondo l'orario, partendo
        // dall'ultima posizione (il GPS in treno aggiorna a scatti). Così si muove
        // in modo fluido lungo i binari invece di stare fermo e poi saltare.
        val goPoint = onTrain?.let { s -> detector?.estimateAlong(now)?.let { track?.pointAt(it) } ?: s.pointOnLine }

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
            userLocation = goPoint ?: user,
            nearestStation = onTrain?.ahead?.firstOrNull()?.station ?: focus,
            travelMode = travelMode,
            go = go,
            canGo = go == null && (debugBuild || (user != null && nearLine(user))),
            simulating = simulation != null,
            route = route,
            settings = settings,
            validFromLong = p.validFrom.format(DATE_LONG),
            busStops = busStopsUi(p),
            // Lo sciopero vale per il giorno di oggi: se è finito (o non è oggi), niente riga.
            strike = strike?.takeIf { it.date == now.toLocalDate() && !now.isAfter(it.end) },
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
            direction = s.direction,
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
        val sample = GoSample(fix.point, now().minusNanos(fix.ageMillis * 1_000_000), fix.accuracy?.toDouble())
        // In treno il GPS a volte ripete identica la posizione di prima: non è
        // una posizione nuova (sembrerebbe "fermo"), quindi Go non la riceve.
        val repeated = lastSample?.point == sample.point
        lastSample = sample
        onSample(sample, feedDetector = !repeated)
    }

    private fun onSample(sample: GoSample, feedDetector: Boolean = true) {
        userLocation = sample.point
        // Il rilevatore riceve TUTTE le posizioni, anche imprecise: in Go pesa
        // ciascuna secondo la sua precisione (vedi GoDetector.updateOnTrain).
        if (feedDetector) detector?.update(sample)
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
            route = found?.let { RouteUi(stationId, mode, it.points, scaledMinutes(it.minutes, mode), it.minutes, it.gap) }
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

    /** Pulsante X: esci dalla modalità Go. */
    fun exitGo() {
        detector?.stop()
        pinnedStopId = null
        refresh()
    }

    /**
     * Pulsante "Vai": entra in Go dalla tua posizione. La direzione non si
     * chiede: si sceglie quella più probabile ([likelyDirection]) e si può
     * cambiare col selettore; se il treno va dall'altra parte si gira da sola.
     */
    fun startGo() {
        val p = provider ?: return
        val d = detector ?: return
        val t = track ?: return
        val sample = lastSample ?: return
        // Lontano dalla linea non fa niente (succede solo nella build di prova,
        // dove il pulsante si vede sempre).
        if (!nearLine(sample.point)) return
        val now = now()
        val nearest = t.stationAlong.indices.minBy { abs(t.stationAlong[it] - t.project(sample.point).alongMeters) }
        d.start(likelyDirection(p, p.stations[nearest].id, now), sample.copy(time = now))
        pinnedStopId = null
        refresh()
    }

    /** Selettore Ostia | Roma in Go: corregge la direzione scelta da "Vai". */
    fun setGoDirection(direction: Direction) {
        detector?.setDirection(direction)
        goDefaultApplied = false  // nuova direzione: vale la sua fermata preferita
        pinnedStopId = null
        refresh()
    }

    /**
     * La direzione in cui stai probabilmente andando premendo "Vai" vicino a
     * [stationId]: ai capolinea è una sola; altrimenti quella del treno appena
     * passato (entro 2 minuti: ci sei appena salito), se no quella del treno
     * che arriva prima (lo stai aspettando).
     */
    private fun likelyDirection(p: ScheduleProvider, stationId: String, now: LocalDateTime): Direction {
        val served = Direction.entries.filter { p.servesDirection(stationId, it) }
        if (served.size == 1) return served.first()
        val justPassed = served.firstOrNull { dir ->
            p.nextDepartures(stationId, dir, now.minusMinutes(JUST_BOARDED_MIN), 1)
                .firstOrNull()?.time?.isBefore(now) == true
        }
        return justPassed ?: served.minBy { dir ->
            p.nextDepartures(stationId, dir, now, 1).firstOrNull()?.time ?: LocalDateTime.MAX
        }
    }

    /** Vicino a una stazione o ai binari: lì ha senso il pulsante "Vai". */
    private fun nearLine(point: GeoPoint): Boolean {
        val t = track ?: return false
        val p = provider ?: return false
        return t.project(point).offsetMeters <= VAI_TRACK_M ||
            p.stations.any { Geo.distanceMeters(point, GeoPoint(it.lat, it.lon)) <= VAI_STATION_M }
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
        pinnedStopId = null
        // LAZY: parte solo dopo essere stato salvato in "simulation", così il
        // "finally" sa riconoscere se è ancora lui la simulazione in corso.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                for ((i, s) in samples.withIndex()) {
                    val waitMs = Duration.between(now(), s.time).toMillis() / SIM_SPEED
                    if (waitMs > 0) delay(waitMs)
                    // Alla prima posizione: come premere "Vai" alla stazione di partenza.
                    if (i == 0) d.start(direction, s)
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

    /**
     * La pagina mostra un altro giorno (scorrendo col dito o con ‹ ›): la
     * giornata ricorda quale, così l'aggiornamento al minuto resta lì.
     */
    fun selectDay(date: LocalDate) {
        if (dayRequest?.date == date) return
        dayRequest = dayRequest?.copy(date = date)
        buildDay()
    }

    fun setDayDirection(direction: Direction) {
        dayRequest = dayRequest?.copy(direction = direction)
        buildDay()
    }

    /**
     * Intestazione della giornata (stazione, direzioni). Gli orari dei singoli
     * giorni li prepara [dayPage], chiamata dalla pagina per il giorno che si
     * vede e per quelli accanto (che entrano scorrendo).
     */
    private fun buildDay() {
        val p = provider ?: return
        val req = dayRequest ?: return
        val old = _day.value
        _day.value = DayUi(
            stationName = p.stations.first { it.id == req.stationId }.name,
            // Nel selettore: prima Ostia poi Roma, come sulla mappa (Ostia a sinistra, Roma a destra).
            directions = DIRECTIONS.reversed()
                .filter { p.servesDirection(req.stationId, it.direction) }
                .map { DirectionTab(it.direction, it.title) },
            selectedDirection = req.direction,
            // Il giorno da cui si conta lo scorrimento resta quello d'apertura.
            openedOn = old?.openedOn ?: req.date,
            selectedDate = req.date,
            // Cambia ogni minuto: la pagina ricalcola "passati / prossimo".
            minute = now().truncatedTo(ChronoUnit.MINUTES),
        )
    }

    /** Orari di tutta la giornata [date] per la stazione e la direzione aperte. */
    fun dayPage(date: LocalDate): DayPage? {
        val p = provider ?: return null
        val req = dayRequest ?: return null
        val now = now().truncatedTo(ChronoUnit.MINUTES)
        val buses = p.busesForDay(req.stationId, req.direction, date)
        // Al capolinea il bus ha un orario esatto: sta in tabella come i treni.
        val departures = (p.departuresForDay(req.stationId, req.direction, date).map { it to false } +
            buses.filter { it.atTerminus }.map { Departure(it.departure) to true })
            .sortedBy { it.first.time }
        // Il "prossimo" ha senso solo per la giornata in corso.
        val today = serviceToday()
        val next = if (date == today) departures.firstOrNull { !it.first.time.isBefore(now) } else null

        // Raggruppa per ora "di servizio": le corse dopo mezzanotte (ora 24)
        // finiscono in fondo, etichettate "00".
        val start = date.atStartOfDay()
        val hours = departures
            .groupBy { Duration.between(start, it.first.time).toHours() }
            .toSortedMap()
            .map { (hour, list) ->
                HourRow(
                    label = "%02d".format(hour % 24),
                    cells = list.map { (d, isBus) ->
                        MinuteCell(
                            text = "%02d".format(d.time.minute),
                            unconfirmed = d.unconfirmed,
                            past = d.time.isBefore(now),
                            next = next?.first == d,
                            bus = isBus,
                        )
                    },
                )
            }

        // Riga in fondo: il bus che parte dall'altro capolinea (qui il passaggio non è
        // pubblicato), oppure "niente bus qui" nelle stazioni dove non ferma (Acilia Sud).
        val origin = if (req.direction == Direction.TO_OSTIA) p.stations.first() else p.stations.last()
        val busNote = when {
            buses.any { it.atTerminus } -> null
            buses.isNotEmpty() -> {
                val b = buses.first()
                BusNote(
                    title = "Bus sostitutivo",
                    text = "Parte da ${origin.name} alle ${b.departure.format(TIME)}. " +
                        "L'orario di passaggio qui non è pubblicato.",
                    past = date.isBefore(today) || (date == today && now.isAfter(b.visibleUntil)),
                )
            }
            p.hasBuses(p.dayTypeOf(date)) && p.busStop(req.stationId, req.direction) == null -> {
                val here = p.stations.first { it.id == req.stationId }
                val nearest = p.busStops.filter { it.direction == req.direction }
                    .minByOrNull { Geo.distanceMeters(GeoPoint(it.lat, it.lon), here) }
                BusNote(
                    title = "Niente bus sostitutivo qui",
                    text = nearest?.let { s ->
                        val st = p.stations.first { it.id == s.stationId }
                        "La fermata più vicina è a ${st.name} (${Geo.formatDistance(Geo.distanceMeters(GeoPoint(here.lat, here.lon), st))})."
                    } ?: "",
                    past = false,
                )
            }
            else -> null
        }

        return DayPage(
            relativeDay = when (date) {
                today -> "Oggi"
                today.plusDays(1) -> "Domani"
                today.minusDays(1) -> "Ieri"
                else -> null
            },
            dateLabel = date.format(DAY_LABEL),
            dayTypeLabel = when (p.dayTypeOf(date)) {
                DayType.WEEKDAY -> "Orario feriale"
                DayType.SATURDAY -> "Orario del sabato"
                DayType.HOLIDAY -> "Orario festivo"
            },
            hours = hours,
            nextHourIndex = hours.indexOfFirst { h -> h.cells.any { it.next } }.coerceAtLeast(0),
            busNote = busNote,
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
            .map { d -> buildBlock(p, station, d, now, reach) }
        )
    }

    /**
     * La riga di una direzione: i prossimi treni della giornata di servizio in
     * corso e, la sera, il bus sostitutivo dopo l'ultimo treno. I treni del
     * giorno dopo NON si mostrano (dalle 3 di notte sì: è il giorno nuovo):
     * se mancano, le caselle restano vuote; se non c'è più niente, neanche
     * il bus, c'è "Nessun treno fino a domani · primo treno 05:36".
     */
    private fun buildBlock(
        p: ScheduleProvider, station: Station, d: DirectionInfo, now: LocalDateTime, reach: Long?,
    ): DirectionBlock {
        // Fine della giornata di servizio in corso: le 3 di notte.
        val serviceEnd = (if (now.hour < 3) now.toLocalDate() else now.toLocalDate().plusDays(1)).atTime(3, 0)
        // Un treno resta in lista con "ora" fino a 2 minuti DOPO l'orario: di
        // solito arriva con 1–2 minuti di ritardo (esperienza dell'utente).
        val all = p.nextDepartures(station.id, d.direction, now.minusMinutes(LATE_GRACE_MIN), ROWS_PER_DIRECTION)
        val trains = all.filter { it.time.isBefore(serviceEnd) }.map { it.time to toRow(it, now, reach) }
        val bus = p.nextBus(station.id, d.direction, now)
            ?.takeIf { it.departure.isBefore(serviceEnd) }
            ?.let { it.sortTime to busRow(it, now) }
        val rows = (trains + listOfNotNull(bus)).sortedBy { it.first }.take(ROWS_PER_DIRECTION).map { it.second }
        return DirectionBlock(
            direction = d.direction,
            title = d.title,
            terminus = d.terminus,
            rows = rows,
            firstTomorrow = if (rows.isEmpty()) all.firstOrNull()?.let { "primo treno " + it.time.format(TIME) } else null,
        )
    }

    /** Le fermate dei bus per la mappa: non cambiano mai, si preparano una volta sola. */
    private var busStopsCache: List<BusStopUi>? = null

    private fun busStopsUi(p: ScheduleProvider): List<BusStopUi> = busStopsCache ?: run {
        // Orari di partenza dal capolinea, presi dal primo giorno con i bus.
        val busDay = (0L..7L).map { p.validFrom.plusDays(it) }.firstOrNull { p.hasBuses(p.dayTypeOf(it)) }
        val days = DayType.entries.filter { p.hasBuses(it) }.joinToString(", ") {
            when (it) { DayType.WEEKDAY -> "lun–ven"; DayType.SATURDAY -> "sabato"; DayType.HOLIDAY -> "festivi" }
        }
        /** "parte 23:00 da Porta San Paolo" per una fermata e il suo verso. */
        fun departs(s: BusStop): String? {
            val origin = if (s.direction == Direction.TO_OSTIA) p.stations.first() else p.stations.last()
            val times = busDay?.let { day -> p.busesForDay(s.stationId, s.direction, day).joinToString(", ") { it.departure.format(TIME) } }
            return if (times.isNullOrEmpty()) null else "parte $times da ${origin.name}"
        }
        fun towards(dir: Direction) = if (dir == Direction.TO_OSTIA) "Ostia" else "Roma"
        // Una palina usata nei due versi (Castel Fusano, Lido Centro) è UN solo cartello,
        // senza freccia, che dice entrambe le partenze.
        p.busStops.groupBy { it.stationId to it.code }.values.map { group ->
            val s = group.first()
            if (group.size == 1) {
                BusStopUi(
                    point = GeoPoint(s.lat, s.lon),
                    title = "Bus sostitutivo verso " + towards(s.direction),
                    detail = s.name + (departs(s)?.let { " · $days, $it" } ?: ""),
                    dirTag = "→ " + towards(s.direction),
                )
            } else {
                // Prima Ostia poi Roma, come nei selettori.
                val parts = group.sortedBy { it.direction != Direction.TO_OSTIA }
                    .mapNotNull { g -> departs(g)?.let { "verso ${towards(g.direction)} $it" } }
                BusStopUi(
                    point = GeoPoint(s.lat, s.lon),
                    title = "Bus sostitutivo, entrambi i versi",
                    detail = s.name + if (parts.isEmpty()) "" else " · $days: " + parts.joinToString("; "),
                    dirTag = null,
                )
            }
        }.also { busStopsCache = it }
    }

    /**
     * Il bus sostitutivo nella riga. Al capolinea l'orario è esatto: "20 min ·
     * 23:00 · bus". Altrove il passaggio non è pubblicato: "Bus · parte 23:00"
     * (dal capolinea), poi "partito 23:00".
     */
    private fun busRow(bus: BusPass, now: LocalDateTime): DepartureRow {
        val clock = bus.departure.format(TIME)
        if (bus.atTerminus) {
            return toRow(Departure(bus.departure), now).copy(clock = "$clock · bus", bus = true)
        }
        val departed = now.truncatedTo(ChronoUnit.MINUTES).isAfter(bus.departure)
        return DepartureRow(
            wait = "Bus",
            waitUnit = "",
            clock = (if (departed) "partito " else "parte ") + clock,
            unconfirmed = false,
            imminent = false,
            bus = true,
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
            minutes < 1 -> "ora" to ""  // anche già passato da poco (vedi LATE_GRACE_MIN)
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
            atRisk = strike?.atRisk(dep.origin ?: dep.time) == true,
        )
    }

    private data class DirectionInfo(val direction: Direction, val title: String, val terminus: String)

    private companion object {
        // Gli orari sono quelli di Roma, anche se il telefono avesse un altro fuso.
        /** Treni mostrati per direzione in ogni scheda. */
        const val ROWS_PER_DIRECTION = 3
        /** Minuti dopo l'orario in cui un treno resta in lista con "ora" (spesso è in ritardo). */
        const val LATE_GRACE_MIN = 2L
        /** Pulsante "Vai": compare entro tanto da una stazione (metri)… */
        const val VAI_STATION_M = 300.0
        /** …o entro tanto dai binari (per chi lo preme a treno già partito). */
        const val VAI_TRACK_M = 80.0
        /** "Vai" premuto entro 2 minuti dal passaggio di un treno: sei salito su quello. */
        const val JUST_BOARDED_MIN = 2L
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
