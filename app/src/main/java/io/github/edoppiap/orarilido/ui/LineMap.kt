package io.github.edoppiap.orarilido.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import androidx.compose.ui.layout.onSizeChanged
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import io.github.edoppiap.orarilido.R
import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.location.rememberHeading
import io.github.edoppiap.orarilido.ui.theme.Blue
import io.github.edoppiap.orarilido.ui.theme.BlueDark
import io.github.edoppiap.orarilido.ui.theme.Card
import io.github.edoppiap.orarilido.ui.theme.Grey
import io.github.edoppiap.orarilido.ui.theme.GreyDark
import io.github.edoppiap.orarilido.walk.WalkGraph
import io.github.edoppiap.orarilido.places.Place
import io.github.edoppiap.orarilido.places.PlaceKind
import io.github.edoppiap.orarilido.places.Places
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.Marker
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.RoundCap
import io.github.edoppiap.orarilido.ui.theme.CardDark
import io.github.edoppiap.orarilido.ui.theme.BackgroundDark
import androidx.compose.foundation.layout.widthIn
import io.github.edoppiap.orarilido.ui.theme.Ink
import io.github.edoppiap.orarilido.ui.theme.InkDark
import io.github.edoppiap.orarilido.schedule.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.google.android.gms.maps.model.CameraPosition
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan
import kotlinx.coroutines.flow.first

/**
 * Mappa Google con la linea, le stazioni e (fase 4) la posizione dell'utente.
 *
 * [bottomPadding] = altezza della tendina: la mappa "sa" che quella parte è
 * coperta e centra la linea nello spazio visibile sopra.
 */
@OptIn(MapsComposeExperimentalApi::class)  // per MapEffect
@Composable
fun LineMap(
    stations: List<Station>,
    /** Stazioni da evidenziare (quelle "in primo piano"). */
    highlightedIds: Set<String>,
    /** true = stile notturno (scuro). Lo decide il ciclo giorno/notte, non il tema dell'app. */
    night: Boolean,
    /** Posizione dell'utente (null = sconosciuta) e stazione di riferimento (più vicina o preferita più vicina). */
    userLocation: GeoPoint?,
    nearestStation: Station?,
    /** A piedi o in bici: decide l'icona sulle etichette dei cerchi. */
    travelMode: TravelMode,
    /** Velocità (metri al minuto) del mezzo scelto, dalle Impostazioni: decide la grandezza dei cerchi. */
    metersPerMinute: Double,
    /** Cerchi visibili? Si accendono/spengono toccando il pallino dell'utente. */
    showCircles: Boolean,
    /**
     * Modalità Go: le prossime fermate davanti (vuota = Go spenta). L'inquadratura
     * "tu + stazione" diventa "tu + queste fermate" e segue il treno.
     */
    goStations: List<Station> = emptyList(),
    /** In Go, la fermata fissata col tocco (null = nessuna): fissandola, la mappa inquadra il tratto fino a lei. */
    goPinnedId: String? = null,
    /** Percorso suggerito verso la stazione più vicina (null = nessuno). */
    route: RouteUi? = null,
    /**
     * Inquadratura "tutta la linea" sì/no (i cerchi lì spariscono). Si avvisa solo
     * quando scegli un'inquadratura: spostando la mappa a mano resta l'ultimo valore.
     */
    onLineFraming: (Boolean) -> Unit = {},
    /** Il percorso è davvero disegnato? (serve a MainScreen per il selettore 🚶|🚲) */
    onRouteVisible: (Boolean) -> Unit = {},
    /** Punti di interesse (fontanelle, bar, ...) ammessi? Solo a mappa piena e mai in Go. */
    showPlaces: Boolean = false,
    /** Categorie di punti di interesse scelte nelle Impostazioni. */
    placeKinds: Set<io.github.edoppiap.orarilido.places.PlaceKind> = io.github.edoppiap.orarilido.places.PlaceKind.entries.toSet(),
    /**
     * Fermate dei bus sostitutivi: si vedono SEMPRE da vicino (zoom 15), a
     * qualunque ora e altezza della tendina, senza interruttori (deciso
     * dall'utente: sono utili e ricordano che il servizio ha ancora problemi).
     */
    busStops: List<BusStopUi> = emptyList(),
    /** false in modalità Go: lì la mappa mostra solo il viaggio. */
    showBusStops: Boolean = true,
    onUserClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    /**
     * Di quanti px vanno alzati i pulsanti (centra, bussola) per stare sopra la
     * tendina. È una funzione letta solo al momento di disegnare: così i pulsanti
     * seguono il dito senza ricalcolare tutta la mappa a ogni fotogramma.
     */
    controlsLift: () -> Int,
    /** L'utente ha iniziato a spostare la mappa col dito. */
    onMapGesture: () -> Unit,
    onStationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = night
    // Colori fissi legati allo stile della mappa (non al tema dell'app).
    val lineColor = if (night) BlueDark else Blue
    val density = LocalDensity.current

    // Il tracciato (595 punti) si legge da assets in background, una volta sola.
    val linePoints by produceState(initialValue = emptyList<LatLng>()) {
        value = withContext(Dispatchers.IO) { loadLine(context) }
    }

    // La mappa nasce GIÀ inquadrata sulla linea intera (niente partenza dal
    // mappamondo): centro e zoom si calcolano a mano dalla misura dello schermo,
    // perché l'inquadratura "automatica" di Google si può usare solo a mappa caricata.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cameraState = rememberCameraPositionState {
        position = fitCamera(
            points = stations.map { LatLng(it.lat, it.lon) },
            widthDp = configuration.screenWidthDp.toFloat(),
            heightDp = configuration.screenHeightDp - bottomPadding.value,
            marginDp = 48f,
        )
    }
    var mapLoaded by remember { mutableStateOf(false) }
    // Misura: quando il primo fotogramma con la mappa (ancora vuota) arriva a schermo.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        io.github.edoppiap.orarilido.logStartup("Mappa a schermo (vuota)")
    }
    // I nomi delle stazioni compaiono solo con uno zoom abbastanza vicino,
    // altrimenti con la linea intera si sovrapporrebbero.
    val showNames by remember { derivedStateOf { cameraState.position.zoom >= NAMES_MIN_ZOOM } }
    // Stazione toccata con lo zoom lontano: il suo nome compare per qualche secondo.
    var revealedId by remember { mutableStateOf<String?>(null) }
    // Ultima stazione rivelata: resta disegnata durante la dissolvenza in uscita.
    var lastRevealedId by remember { mutableStateOf<String?>(null) }
    // Dissolvenze: nomi per zoom (0,5 s) e nome rivelato (entra in 0,2 s, esce in 0,8 s).
    val namesAlpha by animateFloatAsState(if (showNames) 1f else 0f, tween(500), label = "names")
    val revealAlpha by animateFloatAsState(
        if (revealedId != null) 1f else 0f,
        tween(if (revealedId != null) 200 else 800),
        label = "reveal",
    )
    LaunchedEffect(revealedId) {
        if (revealedId != null) {
            delay(NAME_REVEAL_MS)
            revealedId = null
        }
    }

    // Animazione dei cerchi: "growth" va da 0 (tutti dentro il pallino) a 1 (misura piena).
    // Compaiono allargandosi dal pallino, spariscono rimpicciolendosi dietro di lui.
    val circlesVisible = showCircles && userLocation != null
    val growth = remember { Animatable(0f) }
    LaunchedEffect(circlesVisible) {
        growth.animateTo(
            targetValue = if (circlesVisible) 1f else 0f,
            animationSpec = tween(durationMillis = if (circlesVisible) 600 else 350, easing = FastOutSlowInEasing),
        )
    }
    // Anche il passaggio a piedi <-> in bici (o un cambio di velocità nelle Impostazioni)
    // fa crescere/restringere i cerchi invece di saltare.
    val circleMetersPerMinute by animateFloatAsState(
        targetValue = metersPerMinute.toFloat(),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "metersPerMinute",
    )

    // ---- Percorso suggerito: tratteggio grigio che "cresce" dal pallino alla stazione ----
    // drawn va da 0 (niente) a 1 (percorso intero). Si ridisegna da capo solo se
    // cambia la stazione o il mezzo; mentre cammini il percorso si aggiorna e basta.
    // Quando sparisce (es. entri in Go) si ritira verso di te.
    val drawn = remember { Animatable(0f) }
    var lastRoute by remember { mutableStateOf<RouteUi?>(null) }
    if (route != null && route != lastRoute) lastRoute = route
    val shownRoute = lastRoute
    // Lunghezze progressive dei punti, per tagliare il percorso alla frazione "drawn".
    val routeCumulative = remember(shownRoute) {
        val pts = shownRoute?.points.orEmpty()
        DoubleArray(pts.size).also { acc ->
            for (i in 1 until pts.size) acc[i] = acc[i - 1] + WalkGraph.distanceM(pts[i - 1], pts[i])
        }
    }

    // Inquadratura ATTUALE della mappa (null = l'utente l'ha spostata col dito).
    // All'apertura è la linea intera, perché la mappa nasce così.
    var framing by remember { mutableStateOf<RecenterTarget?>(RecenterTarget.LINE) }

    // Alla prima posizione: zoom che inquadra te e la stazione più vicina.
    // Una volta sola, così poi la mappa resta dove la sposti tu.
    var zoomedToUser by remember { mutableStateOf(false) }
    /** Lo zoom iniziale su di te è finito (o non ci sarà): il percorso può comparire. */
    var routeReady by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, userLocation != null) {
        val user = userLocation ?: return@LaunchedEffect
        val station = nearestStation ?: return@LaunchedEffect
        if (!mapLoaded || zoomedToUser) return@LaunchedEffect
        zoomedToUser = true
        // Il percorso suggerito può comparire solo da qui in poi (a zoom finito).
        try {
            // Se nel frattempo hai già spostato la mappa, non te la portiamo via.
            if (framing != RecenterTarget.LINE) return@LaunchedEffect
            // Se sei lontano dalla linea (es. in un'altra città) non ha senso zoomare.
            if (Geo.distanceMeters(user, station) > MAX_ZOOM_DISTANCE_M) return@LaunchedEffect
            framing = RecenterTarget.USER_AND_STATION
            val bounds = LatLngBounds.builder()
                .include(LatLng(user.lat, user.lon))
                .include(LatLng(station.lat, station.lon))
                .build()
            val marginPx = with(density) { 72.dp.roundToPx() }
            cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, marginPx), 800)
            // Se tu e la stazione siete vicinissimi, non zoomare troppo.
            if (cameraState.position.zoom > MAX_ZOOM) cameraState.animate(CameraUpdateFactory.zoomTo(MAX_ZOOM), 300)
        } finally {
            routeReady = true
        }
    }

    // ---- Quando si vede il percorso (regola decisa con l'utente il 13/11) ----
    // "Tu + stazione" decide quando COMPARE, non quando sparisce:
    //  - compare arrivando in "tu + stazione" (la prima volta dopo lo zoom iniziale);
    //  - RESTA se sposti o zoomi la mappa a mano (probabilmente lo stai guardando);
    //  - sparisce scegliendo "tu" o "tutta la linea" col pulsante, o toccando "🚶 12 min";
    //  - ricompare tornando a "tu + stazione", o se cambiano stazione o mezzo.
    //  Mai in Go.
    var routeOn by remember { mutableStateOf(false) }
    LaunchedEffect(framing) { framing?.let { onLineFraming(it == RecenterTarget.LINE) } }
    LaunchedEffect(framing, routeReady) {
        when (framing) {
            RecenterTarget.USER_AND_STATION -> if (routeReady) routeOn = true
            RecenterTarget.USER, RecenterTarget.LINE -> routeOn = false
            else -> Unit  // mappa spostata a mano (null) o solo stazione: resta com'è
        }
    }
    var lastRouteFor by remember { mutableStateOf<Pair<String, TravelMode>?>(null) }
    val routeFor = route?.let { it.stationId to it.mode }
    LaunchedEffect(routeFor) {
        // Cambio di stazione di riferimento o di mezzo (non il primo percorso): si rimostra.
        if (routeFor != null && lastRouteFor != null && routeFor != lastRouteFor && routeReady) routeOn = true
        if (routeFor != null) lastRouteFor = routeFor
    }
    val routeVisible = route != null && routeOn && goStations.isEmpty()
    LaunchedEffect(routeVisible) { onRouteVisible(routeVisible) }
    val routeKey = route?.takeIf { routeVisible }?.let { it.stationId to it.mode }
    // Per quale percorso (stazione + mezzo) vale l'animazione in corso. Quando arriva
    // un percorso NUOVO (es. passi da "Vicina" a "Preferite"), finché la sua animazione
    // non parte resta nascosto: prima si vedeva intero per un attimo (drawn era ancora 1),
    // poi spariva e ricresceva.
    var animatedRouteKey by remember { mutableStateOf<Pair<String, TravelMode>?>(null) }
    LaunchedEffect(routeKey) {
        if (routeKey != null) {
            // Cresce a mappa FERMA: durante uno zoom o uno spostamento non si vedrebbe.
            androidx.compose.runtime.snapshotFlow { cameraState.isMoving }.first { !it }
            drawn.snapTo(0f)
            animatedRouteKey = routeKey
            drawn.animateTo(1f, tween(ROUTE_DRAW_MS, easing = FastOutSlowInEasing))
        } else {
            drawn.animateTo(0f, tween(300))
        }
    }

    // ---- Punti di interesse (fontanelle, bar, ciclofficine, ...) ----
    // Si leggono da assets solo dopo che la mappa è caricata (non servono all'apertura).
    val places by produceState(initialValue = emptyList<Place>(), mapLoaded) {
        if (mapLoaded && value.isEmpty()) value = withContext(Dispatchers.IO) { loadPlaces(context) }
    }
    // Compaiono solo da vicino: con lo zoom lontano sarebbero migliaia di puntini.
    val placesZoomOk by remember { derivedStateOf { cameraState.position.zoom >= PLACES_MIN_ZOOM } }
    val placesOn = showPlaces && placesZoomOk && places.isNotEmpty()
    val placesAlpha by animateFloatAsState(if (placesOn) 1f else 0f, tween(400), label = "places")
    // Quali disegnare: quelli nello schermo (con un po' di margine), i più vicini al
    // centro, al massimo PLACES_MAX. Si ricalcola quando la mappa si ferma.
    var shownPlaces by remember { mutableStateOf(emptyList<Place>()) }
    LaunchedEffect(places, cameraState.isMoving, placesOn, placeKinds) {
        if (cameraState.isMoving || !placesOn) return@LaunchedEffect
        val b = cameraState.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
        val dLat = (b.northeast.latitude - b.southwest.latitude) * 0.25
        val dLon = (b.northeast.longitude - b.southwest.longitude) * 0.25
        val c = b.center
        shownPlaces = places
            .filter {
                it.kind in placeKinds &&
                it.point.lat in (b.southwest.latitude - dLat)..(b.northeast.latitude + dLat) &&
                    it.point.lon in (b.southwest.longitude - dLon)..(b.northeast.longitude + dLon)
            }
            .sortedBy { (it.point.lat - c.latitude).let { d -> d * d } + (it.point.lon - c.longitude).let { d -> d * d * 0.55 } }
            .take(PLACES_MAX)
    }
    // Punto toccato: il suo nome compare per qualche secondo (come per le stazioni).
    var tappedPlace by remember { mutableStateOf<Place?>(null) }
    var lastTappedPlace by remember { mutableStateOf<Place?>(null) }
    val tappedAlpha by animateFloatAsState(
        if (tappedPlace != null && placesOn) 1f else 0f,
        tween(if (tappedPlace != null) 200 else 600),
        label = "tappedPlace",
    )
    LaunchedEffect(tappedPlace) {
        if (tappedPlace != null) {
            delay(NAME_REVEAL_MS)
            tappedPlace = null
        }
    }

    // ---- Fermate dei bus sostitutivi ----
    // Stessa soglia di zoom dei punti di interesse, ma non contano nel loro limite
    // e non dipendono dal loro interruttore. Sono al massimo 24.
    val busOn = showBusStops && placesZoomOk && busStops.isNotEmpty()
    val busAlpha by animateFloatAsState(if (busOn) 1f else 0f, tween(400), label = "busStops")
    val busBigZoom by remember { derivedStateOf { cameraState.position.zoom >= BUS_BIG_ZOOM } }
    // 0 = cartello piccolo, 1 = grande con "→ Ostia": si passa in dissolvenza.
    val busBig by animateFloatAsState(if (busBigZoom) 1f else 0f, tween(300), label = "busBig")
    var tappedBus by remember { mutableStateOf<BusStopUi?>(null) }
    var lastTappedBus by remember { mutableStateOf<BusStopUi?>(null) }
    val tappedBusAlpha by animateFloatAsState(
        if (tappedBus != null && busOn) 1f else 0f,
        tween(if (tappedBus != null) 200 else 600),
        label = "tappedBus",
    )
    LaunchedEffect(tappedBus) {
        if (tappedBus != null) {
            delay(NAME_REVEAL_MS)
            tappedBus = null
        }
    }

    // ---- Etichette dei cerchi ("🚶 5 min", "10 min", "15 min") ----
    // Stanno tutte e tre sulla stessa linea che parte da te: di solito verso nord
    // (in cima ai cerchi). Se una finirebbe sotto l'etichetta del percorso, la linea
    // RUOTA attorno a te fino al primo angolo libero, e le etichette scivolano lungo
    // i cerchi (animazione). Si ricalcola a mappa ferma, così non ballano durante lo zoom.
    val labelAngle = remember { Animatable(0f) }  // gradi dal nord, in senso orario
    LaunchedEffect(cameraState.isMoving, userLocation, shownRoute, routeVisible, metersPerMinute, mapLoaded) {
        if (cameraState.isMoving || !mapLoaded) return@LaunchedEffect
        val user = userLocation ?: return@LaunchedEffect
        val routeMid = shownRoute?.takeIf { routeVisible && it.points.size >= 2 }?.let { r ->
            routePrefix(r.points, routeCumulative, routeCumulative.last() / 2).last()
        }
        val target = circleLabelAngle(
            user = user,
            radiiM = WALK_MINUTES.map { it * metersPerMinute },
            routeLabel = routeMid,
            zoom = cameraState.position.zoom,
            bearing = cameraState.position.bearing,
        )
        // Per la via più breve (da 350° a 10° si passa per lo 0, non all'indietro).
        val delta = ((target - labelAngle.value) % 360f + 540f) % 360f - 180f
        if (growth.value < 0.01f) labelAngle.snapTo(target)  // cerchi nascosti: nessuna animazione
        else labelAngle.animateTo(labelAngle.value + delta, tween(450, easing = FastOutSlowInEasing))
    }

    // Direzione in cui punta il telefono (null = sensore assente o posizione ignota).
    val heading by rememberHeading(userLocation)

    // ---- Pulsante "centra": ogni tocco passa all'inquadratura successiva ----
    // Se l'utente sposta la mappa col dito, non c'è più un'inquadratura "attuale".
    LaunchedEffect(cameraState.isMoving) {
        if (cameraState.isMoving && cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            framing = null
            onMapGesture()
        }
    }
    val scope = rememberCoroutineScope()
    // La stazione di riferimento è UNA sola: la più vicina, o in modalità Preferite
    // la preferita più vicina (con più preferite lo zoom si allargherebbe troppo).
    // Senza GPS: la prima in primo piano (l'ultima usata).
    val focusStation = nearestStation ?: stations.firstOrNull { it.id in highlightedIds }
    // Il giro delle inquadrature: stazione → tu → linea → stazione → ...
    // (senza GPS: stazione → linea → ...)
    val cycle = buildList {
        if (focusStation != null) add(if (userLocation != null) RecenterTarget.USER_AND_STATION else RecenterTarget.STATION)
        if (userLocation != null) add(RecenterTarget.USER)
        add(RecenterTarget.LINE)
    }
    // Cosa farà il PROSSIMO tocco (l'icona del pulsante lo mostra): il passo
    // dopo quello attuale; se hai spostato la mappa, si riparte dal primo del giro,
    // cioè "tu + stazione" (deciso 13/11: prima era "tu", ma faceva sparire il percorso).
    val nextTarget = when (val current = framing) {
        null -> cycle.first()
        else -> cycle[(cycle.indexOf(current) + 1) % cycle.size]  // indexOf = −1 → il primo
    }
    // Spazio coperto dalla tendina ATTUALMENTE applicato alla mappa (vedi MapEffect più giù).
    var appliedPadding by remember { mutableStateOf(bottomPadding) }
    // Misura della mappa in px, per sapere dove cade il centro dello spazio visibile.
    var mapSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    /**
     * Porta la mappa sull'inquadratura [target], nello spazio visibile sopra la
     * tendina. La usano il pulsante "centra", il cambio di altezza della tendina
     * e lo spostamento dell'utente (vedi sotto).
     */
    suspend fun applyFraming(target: RecenterTarget, durationMs: Int) {
        val user = userLocation?.let { LatLng(it.lat, it.lon) }
        val station = focusStation?.let { LatLng(it.lat, it.lon) }
        val points = when (target) {
            RecenterTarget.USER -> listOfNotNull(user)
            RecenterTarget.USER_AND_STATION ->
                if (goStations.isNotEmpty()) listOfNotNull(user) + goStations.map { LatLng(it.lat, it.lon) }
                else listOfNotNull(station, user)
            RecenterTarget.STATION -> listOfNotNull(station)
            RecenterTarget.LINE -> stations.map { LatLng(it.lat, it.lon) }
        }
        when (points.size) {
            0 -> return  // niente da inquadrare (es. GPS perso nel frattempo)
            1 -> cameraState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), USER_ZOOM), durationMs)
            else -> {
                // Centro e zoom calcolati PRIMA di muoversi (con lo zoom già limitato
                // a MAX_ZOOM), poi un'unica animazione. Prima si faceva "inquadra i
                // punti" e poi "se è troppo vicino, allontana": con te quasi sopra la
                // stazione la mappa zoomava a fondo e poi tornava indietro, a ogni
                // nuova posizione (il "dentro-fuori" visto all'inizio della simulazione).
                if (mapSize.height == 0) return  // mappa non ancora misurata
                val camera = with(density) {
                    fitCamera(
                        points = points,
                        widthDp = mapSize.width.toDp().value,
                        heightDp = mapSize.height.toDp().value - appliedPadding.value,
                        marginDp = 72f,
                    )
                }
                cameraState.animate(CameraUpdateFactory.newCameraPosition(camera), durationMs)
            }
        }
    }

    val onRecenter: () -> Unit = {
        val target = nextTarget
        framing = target
        scope.launch { applyFraming(target, 600) }
    }

    // Modalità Go: entrando, inquadratura "tu + prossime fermate". Anche fissando
    // (o sbloccando) una fermata si torna lì, pure se avevi spostato la mappa:
    // vuoi vedere il tratto fino a lì. Uscendo da Go la mappa resta dov'è.
    val goActive = goStations.isNotEmpty()
    // Ultima situazione Go vista (attiva? quale fermata fissata?), per accorgersi dei cambi.
    var lastGoKey by remember { mutableStateOf<Pair<Boolean, String?>>(false to null) }

    // Modalità "tu" o "tu + stazione" attiva: la mappa ti segue quando ti sposti
    // (e in Go anche quando cambiano le fermate davanti: lo zoom si stringe man mano).
    // UN SOLO effetto per tutto: due effetti separati facevano partire due
    // animazioni insieme, che si contendevano la camera.
    // (Anche quando cambia la stazione di riferimento, es. passando a Preferite.)
    LaunchedEffect(userLocation, goStations, goPinnedId, mapLoaded, nearestStation?.id) {
        if (!mapLoaded) return@LaunchedEffect
        val goKey = goActive to goPinnedId
        if (goActive && goKey != lastGoKey) framing = RecenterTarget.USER_AND_STATION
        lastGoKey = goKey
        val current = framing
        if (mapLoaded && (current == RecenterTarget.USER || current == RecenterTarget.USER_AND_STATION)) {
            // In Go la posizione cambia ogni secondo: un movimento appena più
            // lungo (1,1 s) viene raggiunto dal successivo prima di rallentare,
            // così la mappa scorre invece di andare a scatti (muovi, fermo, muovi).
            applyFraming(current, if (goActive) 1100 else 500)
        }
    }


    Box(modifier) {
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { mapSize = it },
            cameraPositionState = cameraState,
            contentPadding = PaddingValues(bottom = appliedPadding),
            properties = remember(dark) {
                MapProperties(
                    mapStyleOptions = MapStyleOptions.loadRawResourceStyle(
                        context,
                        if (dark) R.raw.map_style_dark else R.raw.map_style_light,
                    ),
                )
            },
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
                tiltGesturesEnabled = false,
            ),
            onMapLoaded = {
                mapLoaded = true
                io.github.edoppiap.orarilido.logStartup("Mappa caricata")
            },
        ) {
            // La tendina ha cambiato altezza: cambia lo spazio visibile della mappa.
            // Google centra le cose nello spazio NON coperto; se cambiassimo solo
            // quello, la mappa farebbe un salto. Quindi, nello stesso istante:
            //  1. prendiamo il punto della mappa che oggi sta dove cadrà il nuovo centro,
            //  2. cambiamo lo spazio coperto e mettiamo la camera su quel punto
            //     (a occhio non si muove niente);
            //  3. se c'è un'inquadratura attiva, la rifacciamo con un'animazione,
            //     così lo zoom si adatta allo spazio nuovo.
            MapEffect(bottomPadding) { map ->
                if (bottomPadding == appliedPadding) return@MapEffect
                // Se il dito sta ancora spostando la mappa, aspettiamo che finisca:
                // spostare la camera durante il gesto lo farebbe "scattare".
                androidx.compose.runtime.snapshotFlow { cameraState.isMoving }.first { !it }
                val newPx = with(density) { bottomPadding.roundToPx() }
                if (mapLoaded && mapSize.height > 0) {
                    val keep = map.projection.fromScreenLocation(
                        android.graphics.Point(mapSize.width / 2, (mapSize.height - newPx) / 2)
                    )
                    map.setPadding(0, 0, 0, newPx)
                    map.moveCamera(CameraUpdateFactory.newLatLng(keep))
                }
                appliedPadding = bottomPadding  // ora anche Compose sa del nuovo spazio
                framing?.let { applyFraming(it, 500) }
            }
            if (linePoints.isNotEmpty()) {
                Polyline(points = linePoints, color = lineColor, width = with(density) { 5.dp.toPx() })
            }
            // Punti di interesse: icone preparate una volta per tipo (bitmap), così
            // anche 150 marker restano leggeri.
            if (placesAlpha > 0.01f) {
                val icons = remember(dark) { PlaceKind.entries.associateWith { placeIcon(it, night, density.density) } }
                shownPlaces.forEach { place ->
                    key(place) {
                        Marker(
                            state = rememberUpdatedMarkerState(LatLng(place.point.lat, place.point.lon)),
                            icon = icons.getValue(place.kind),
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            alpha = placesAlpha,
                            zIndex = 0.3f,
                            onClick = {
                                tappedPlace = place
                                lastTappedPlace = place
                                true  // niente fumetto e niente centratura di Google
                            },
                        )
                    }
                }
                val tp = lastTappedPlace
                if (tp != null && tappedAlpha > 0.01f) {
                    MarkerComposable(
                        keys = arrayOf<Any>("place", tp, dark),
                        state = rememberUpdatedMarkerState(LatLng(tp.point.lat, tp.point.lon)),
                        // Sopra l'icona: il bordo basso dell'etichetta un po' più su del punto.
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.9f),
                        zIndex = 2.8f,
                        alpha = tappedAlpha * placesAlpha,
                        onClick = { true },
                    ) {
                        StationName(tp.name.ifEmpty { tp.kind.label }, selected = false, night = night, startPadding = 0)
                    }
                }
            }
            // Fermate dei bus sostitutivi: cartello blu col bus (bitmap, uno solo per giorno/notte).
            if (busAlpha > 0.01f) {
                // Due misure: piccolo (zoom 15–17, dove arrivano anche le inquadrature
                // automatiche: si vede che c'è, senza ingombrare) e grande con "→ Ostia"
                // da zoom 17, dove arrivi solo allargando con le dita.
                fun badge(sizeDp: Float) = BitmapDescriptorFactory.fromBitmap(
                    busBadgeBitmap(
                        sizeDp = sizeDp,
                        density = density.density,
                        bg = (if (night) BlueDark else Blue).toArgb(),
                        fg = (if (night) BackgroundDark else Card).toArgb(),
                        border = (if (night) CardDark else Card).toArgb(),
                    )
                )
                val smallIcon = remember(dark) { badge(BUS_SMALL_DP) }
                val bigIcon = remember(dark) { badge(BUS_BIG_DP) }
                val smallAlpha = busAlpha * (1f - busBig)
                val bigAlpha = busAlpha * busBig
                busStops.forEach { stop ->
                    key(stop) {
                        val onTap = {
                            tappedBus = stop
                            lastTappedBus = stop
                            true  // niente fumetto e niente centratura di Google
                        }
                        // Si disegna solo la misura visibile (un marker trasparente ruberebbe i tocchi).
                        if (smallAlpha > 0.01f) Marker(
                            state = rememberUpdatedMarkerState(LatLng(stop.point.lat, stop.point.lon)),
                            icon = smallIcon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            alpha = smallAlpha,
                            zIndex = 0.6f,  // sopra i punti di interesse, sotto stazioni e pallino
                            onClick = { onTap() },
                        )
                        if (bigAlpha > 0.01f) Marker(
                            state = rememberUpdatedMarkerState(LatLng(stop.point.lat, stop.point.lon)),
                            icon = bigIcon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            alpha = bigAlpha,
                            zIndex = 0.6f,
                            onClick = { onTap() },
                        )
                        // "→ Ostia" accanto al cartello grande: il bordo sinistro sta sul cartello,
                        // il testo parte più a destra (padding), così non lo copre.
                        if (bigAlpha > 0.01f) stop.dirTag?.let { tag ->
                            MarkerComposable(
                                keys = arrayOf<Any>("busTag", stop, dark),
                                state = rememberUpdatedMarkerState(LatLng(stop.point.lat, stop.point.lon)),
                                anchor = androidx.compose.ui.geometry.Offset(0f, 0.5f),
                                alpha = bigAlpha,
                                zIndex = 0.55f,
                                onClick = {
                                    tappedBus = stop
                                    lastTappedBus = stop
                                    true
                                },
                            ) {
                                BusDirTag(tag, night)
                            }
                        }
                    }
                }
                val tb = lastTappedBus
                if (tb != null && tappedBusAlpha > 0.01f) {
                    MarkerComposable(
                        keys = arrayOf<Any>("bus", tb, dark),
                        state = rememberUpdatedMarkerState(LatLng(tb.point.lat, tb.point.lon)),
                        // Sopra il cartello: un po' più su quando è grande.
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.3f + 0.15f * busBig),
                        zIndex = 2.8f,
                        alpha = tappedBusAlpha * busAlpha,
                        onClick = { true },
                    ) {
                        BusStopLabel(tb, night)
                    }
                }
            }
            // Percorso suggerito: puntini grigi (non è un binario), sotto stazioni e pallino.
            val f = if (routeKey != null && routeKey != animatedRouteKey) 0f else drawn.value
            if (shownRoute != null && f > 0.001f && shownRoute.points.size >= 2) {
                val total = routeCumulative.last()
                val partial = routePrefix(shownRoute.points, routeCumulative, total * f)
                val grey = if (night) GreyDark else Grey
                // "Salto" iniziale (sei lontano dalle strade conosciute, es. stradina
                // privata): trattini sottili in linea d'aria, la strada la sai tu.
                // Il percorso vero a puntini parte dalla prima strada conosciuta.
                val known = if (shownRoute.gap && partial.size >= 2) {
                    Polyline(
                        points = partial.take(2),
                        color = grey.copy(alpha = 0.7f),
                        width = with(density) { 2.dp.toPx() },
                        pattern = listOf(Dash(with(density) { 4.dp.toPx() }), Gap(with(density) { 5.dp.toPx() })),
                        zIndex = 0.4f,
                    )
                    // Anello dove inizia il percorso calcolato (variante "A + C", 16/11):
                    // compare quando i trattini ci arrivano.
                    if (partial.size >= 3 || f >= 0.999f) MarkerComposable(
                        keys = arrayOf<Any>("routeStart", dark),
                        state = rememberUpdatedMarkerState(partial[1]),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        zIndex = 0.45f,
                        onClick = { true },
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(if (night) CardDark else Card, CircleShape)
                                .border(2.5.dp, grey, CircleShape)
                        )
                    }
                    partial.drop(1)
                } else partial
                if (known.size >= 2) Polyline(
                    points = known,
                    color = grey,
                    width = with(density) { 5.dp.toPx() },
                    pattern = listOf(Dot(), Gap(with(density) { 7.dp.toPx() })),
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    zIndex = 0.4f,
                )
                // Etichetta col tempo a metà strada: compare quando la linea ci arriva.
                val labelAlpha = ((f - 0.5f) * 4f).coerceIn(0f, 1f)
                if (labelAlpha > 0.01f) {
                    val mid = routePrefix(shownRoute.points, routeCumulative, total / 2).last()
                    val emoji = if (shownRoute.mode == TravelMode.BIKE) "🚲" else "🚶"
                    MarkerComposable(
                        keys = arrayOf<Any>("route", shownRoute.minutes, shownRoute.mode, dark),
                        state = rememberUpdatedMarkerState(mid),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        zIndex = 0.7f,
                        alpha = labelAlpha,
                        onClick = { routeOn = false; true },  // tocco sul tempo = "ho capito, toglilo"
                    ) {
                        RouteLabel(emoji + " " + shownRoute.minutes + " min", night)
                    }
                }
            }
            // Un pallino per stazione: bianco bordato di blu, pieno di blu se selezionata.
            stations.forEach { s ->
                val selected = s.id in highlightedIds
                MarkerComposable(
                    keys = arrayOf<Any>(s.id, selected, dark),
                    state = rememberUpdatedMarkerState(LatLng(s.lat, s.lon)),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = if (selected) 2f else 1f,
                    onClick = {
                        onStationClick(s.id)
                        revealedId = s.id
                    lastRevealedId = s.id
                        true  // true = niente fumetto di Google: usiamo la nostra etichetta
                    },
                ) {
                    StationDot(selected, night)
                }
                val nameAlpha = maxOf(namesAlpha, if (s.id == lastRevealedId) revealAlpha else 0f)
                if (nameAlpha > 0.01f) {
                    // Etichetta col nome: il bordo sinistro sta sul pallino, il testo parte
                    // un po' più a destra (padding), così non lo copre.
                    MarkerComposable(
                        keys = arrayOf<Any>("name", s.id, selected, dark),
                        state = rememberUpdatedMarkerState(LatLng(s.lat, s.lon)),
                        anchor = androidx.compose.ui.geometry.Offset(0f, 0.5f),
                        zIndex = 0.8f,
                        alpha = nameAlpha,
                        onClick = { onStationClick(s.id); true },
                    ) {
                        StationName(s.name, selected, night)
                    }
                }
            }
    
            // Posizione dell'utente: cerchi 5/10/15 minuti (a piedi o in bici) e pallino.
            userLocation?.let { user ->
                val center = LatLng(user.lat, user.lon)
                val emoji = if (travelMode == TravelMode.BIKE) "🚲" else "🚶"
                val g = growth.value
                if (g > 0.01f) WALK_MINUTES.forEach { minutes ->
                    val radius = minutes * circleMetersPerMinute.toDouble() * g
                    Circle(
                        center = center,
                        radius = radius,
                        // Leggeri (feedback 13/11: la mappa si riempiva troppo): bordo sottile, niente riempimento.
                        strokeColor = lineColor.copy(alpha = 0.30f),
                        strokeWidth = with(density) { 1.dp.toPx() },
                        fillColor = androidx.compose.ui.graphics.Color.Transparent,
                    )
                    // Etichetta "5 min" sul cerchio, nella direzione scelta da labelAngle
                    // (1° di latitudine ≈ 111 km; quello di longitudine si accorcia con cos(lat)).
                    val angle = Math.toRadians(labelAngle.value.toDouble())
                    val labelPos = LatLng(
                        user.lat + radius * kotlin.math.cos(angle) / M_PER_DEG,
                        user.lon + radius * kotlin.math.sin(angle) / (M_PER_DEG * kotlin.math.cos(Math.toRadians(user.lat))),
                    )
                    MarkerComposable(
                        keys = arrayOf<Any>("walk", minutes, dark, travelMode),
                        state = rememberUpdatedMarkerState(labelPos),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        zIndex = 0.5f,
                        alpha = g,  // le etichette sfumano insieme ai cerchi
                        onClick = { true },  // tocco sull'etichetta: niente centratura di Google
                    ) {
                        WalkLabel("$emoji $minutes min", night)
                    }
                }
                UserMarkers(user, heading, night, dark, onUserClick)
            }
        }
        // Bussola: compare solo se la mappa è ruotata; tocco = nord in alto.
        val bearing = cameraState.position.bearing
        // Compare subito, sparisce con calma: attesa di 0,8 s e dissolvenza di 0,6 s.
        AnimatedVisibility(
            visible = bearing != 0f,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(durationMillis = 600, delayMillis = 800)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, -controlsLift()) }
                .padding(end = 12.dp, bottom = 12.dp + 44.dp + 10.dp),
        ) {
            CompassButton(
                bearing = bearing,
                onClick = {
                    scope.launch {
                        val pos = cameraState.position
                        cameraState.animate(
                            CameraUpdateFactory.newCameraPosition(
                                com.google.android.gms.maps.model.CameraPosition(pos.target, pos.zoom, 0f, 0f)
                            ),
                            400,
                        )
                    }
                },
            )
        }
        // In basso a destra, appena sopra la tendina.
        RecenterButton(
            next = nextTarget,
            // Blu = c'è un'inquadratura attiva (la mappa la mantiene); grigio = mappa spostata a mano.
            active = framing != null,
            onClick = onRecenter,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, -controlsLift()) }
                .padding(end = 12.dp, bottom = 12.dp),
        )
    }
}

/**
 * Pallino dell'utente e cono di orientamento. Il pallino non salta da una
 * posizione all'altra: SCIVOLA in un secondo (come in Google Maps), così in
 * Go, con una posizione al secondo, si muove in modo continuo sui binari.
 * È una funzione a parte perché l'animazione ridisegna solo lei, non tutta la mappa.
 */
@Composable
private fun UserMarkers(
    user: GeoPoint,
    heading: Float?,
    night: Boolean,
    dark: Boolean,
    onUserClick: () -> Unit,
) {
    val glided = rememberGlidingPoint(user)
    val center = LatLng(glided.lat, glided.lon)
    // Cono: dove punta il telefono. Marker "flat" = sta disteso sulla
    // mappa, quindi "rotation" è in gradi dal nord e gira con la mappa.
    heading?.let { degrees ->
        MarkerComposable(
            keys = arrayOf<Any>("cone", dark),
            state = rememberUpdatedMarkerState(center),
            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
            flat = true,
            rotation = degrees,
            zIndex = 2.5f,  // sotto il pallino, sopra le stazioni
            // Il cono è più grande del pallino: un tocco "quasi sul pallino"
            // cade qui. Stesso effetto del pallino, e true = Google non
            // centra la mappa (comportamento predefinito dei marker).
            onClick = { onUserClick(); true },
        ) {
            HeadingCone(night)
        }
    }
    MarkerComposable(
        keys = arrayOf<Any>("user", dark),
        state = rememberUpdatedMarkerState(center),
        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        zIndex = 3f,
        onClick = { onUserClick(); true },  // true = niente etichetta
    ) {
        UserDot(night)
    }
}

/**
 * Il punto mostrato mentre scivola verso [target]: a ogni posizione nuova
 * riparte da dove è ADESSO e ci arriva in [GLIDE_MS], a velocità costante.
 * Salti grandi (prima posizione, GPS ritrovato) non scivolano: il punto ci va subito.
 */
@Composable
private fun rememberGlidingPoint(target: GeoPoint): GeoPoint {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    fun shown() = GeoPoint(
        from.lat + (to.lat - from.lat) * progress.value,
        from.lon + (to.lon - from.lon) * progress.value,
    )
    LaunchedEffect(target) {
        val start = shown()
        from = start
        to = target
        if (Geo.distanceMeters(start, target) > GLIDE_MAX_M) {
            progress.snapTo(1f)
            from = target
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(GLIDE_MS, easing = androidx.compose.animation.core.LinearEasing))
    }
    return shown()
}

/** Durata della scivolata del pallino: come l'intervallo del GPS in Go (1 s). */
private const val GLIDE_MS = 1000
/** Oltre questa distanza il pallino non scivola, salta. */
private const val GLIDE_MAX_M = 500.0

@Composable
private fun StationDot(selected: Boolean, night: Boolean) {
    val blue = if (night) BlueDark else Blue
    val fill = if (night) CardDark else Card
    val size = if (selected) 18.dp else 12.dp
    Box(
        Modifier
            .size(size)
            .background(if (selected) blue else fill, CircleShape)
            .border(3.dp, blue, CircleShape)
    )
}

/** Legge assets/line.json: {"points": [[lat, lon], ...]} */
private fun loadLine(context: Context): List<LatLng> {
    val text = context.assets.open("line.json").bufferedReader().use { it.readText() }
    val points = JSONObject(text).getJSONArray("points")
    return List(points.length()) { i ->
        val p = points.getJSONArray(i)
        LatLng(p.getDouble(0), p.getDouble(1))
    }
}

/** Legge assets/pois.bin (punti di interesse da OpenStreetMap). */
private fun loadPlaces(context: Context): List<Place> =
    Places.parse(context.assets.open("pois.bin").use { it.readBytes() })

/**
 * Icona di un punto di interesse, SENZA sfondo (scelta dell'utente: niente tondino):
 * disegni piatti fatti a mano in PlaceIcons.kt, col bordino del colore delle schede.
 * Disegnata una volta sola per tipo.
 */
private fun placeIcon(kind: PlaceKind, night: Boolean, density: Float): BitmapDescriptor =
    BitmapDescriptorFactory.fromBitmap(
        drawPlaceIcon(kind, night, (if (night) CardDark else Card).toArgb(), density)
    )

/** Da questo zoom in su compaiono i punti di interesse (15 ≈ isolato). */
private const val PLACES_MIN_ZOOM = 15f
/**
 * Fermate dei bus: da questo zoom il cartello è grande e ha "→ Ostia". Le
 * inquadrature automatiche arrivano al massimo a 16 (MAX_ZOOM): a 17 ci arrivi
 * solo allargando con le dita, cioè quando stai cercando dove salire.
 */
private const val BUS_BIG_ZOOM = 17f
private const val BUS_SMALL_DP = 14f
private const val BUS_BIG_DP = 24f
/** Al massimo tanti punti di interesse disegnati insieme (i più vicini al centro). */
private const val PLACES_MAX = 150

/** Durata dell'animazione del percorso che cresce dal pallino alla stazione. */
private const val ROUTE_DRAW_MS = 800

/** Cerchi intorno all'utente: minuti a piedi o in bici. */
private val WALK_MINUTES = listOf(5, 10, 15)
/** Metri in un grado di latitudine. */
private const val M_PER_DEG = 111_320.0
/** Misure approssimate (dp) delle etichette "🚶 15 min" e "🚶 12 min", più un po' di aria. */
private const val CIRCLE_LABEL_W = 54f
private const val CIRCLE_LABEL_H = 16f
private const val ROUTE_LABEL_W = 74f
private const val ROUTE_LABEL_H = 28f
private const val LABEL_GAP = 6f

/**
 * Angolo (gradi dal nord, senso orario) della linea su cui mettere le tre
 * etichette dei cerchi. Si prova prima il nord (0°), poi 20°, −20°, 40°, −40°…
 * e si sceglie il primo in cui nessuna delle tre si sovrappone all'etichetta del
 * percorso [routeLabel]. Il confronto si fa in dp sullo schermo: dipende dallo
 * zoom e dalla rotazione della mappa ([bearing]), perché le etichette restano
 * sempre dritte sullo schermo mentre la mappa gira.
 * Inoltre la linea non deve mettere le tre etichette una sull'altra (succede se
 * è quasi orizzontale con lo zoom lontano), a meno che non succeda già col nord.
 */
private fun circleLabelAngle(user: GeoPoint, radiiM: List<Double>, routeLabel: LatLng?, zoom: Float, bearing: Float): Float {
    if (routeLabel == null) return 0f
    // Metri per dp a questo zoom (le mappe Google: 256 dp per il mondo intero a zoom 0).
    val cosLat = kotlin.math.cos(Math.toRadians(user.lat))
    val metersPerDp = 156_543.034 * cosLat / Math.pow(2.0, zoom.toDouble())
    val b = Math.toRadians(bearing.toDouble())
    // Da (est, nord) in metri a (x a destra, y in su) in dp sullo schermo.
    fun toScreen(east: Double, north: Double): Pair<Double, Double> =
        Pair((east * kotlin.math.cos(b) - north * kotlin.math.sin(b)) / metersPerDp,
             (east * kotlin.math.sin(b) + north * kotlin.math.cos(b)) / metersPerDp)
    val route = toScreen((routeLabel.longitude - user.lon) * M_PER_DEG * cosLat, (routeLabel.latitude - user.lat) * M_PER_DEG)

    fun labels(deg: Double): List<Pair<Double, Double>> {
        val a = Math.toRadians(deg)
        return radiiM.map { r -> toScreen(r * kotlin.math.sin(a), r * kotlin.math.cos(a)) }
    }
    fun overlap(p: Pair<Double, Double>, q: Pair<Double, Double>, w: Float, h: Float) =
        kotlin.math.abs(p.first - q.first) < w + LABEL_GAP && kotlin.math.abs(p.second - q.second) < h + LABEL_GAP
    fun hitsRoute(deg: Double) = labels(deg).any {
        overlap(it, route, (CIRCLE_LABEL_W + ROUTE_LABEL_W) / 2, (CIRCLE_LABEL_H + ROUTE_LABEL_H) / 2)
    }
    fun stacked(deg: Double) = labels(deg).zipWithNext().any { (p, q) -> overlap(p, q, CIRCLE_LABEL_W, CIRCLE_LABEL_H) }

    val stackedAtNorth = stacked(0.0)
    val candidates = listOf(0.0) + (1..9).flatMap { listOf(it * 20.0, -it * 20.0) }
    val best = candidates.firstOrNull { !hitsRoute(it) && (stackedAtNorth || !stacked(it)) } ?: 0.0
    return ((best + 360.0) % 360.0).toFloat()
}
/** Oltre questa distanza dalla stazione più vicina, niente zoom automatico. */
private const val MAX_ZOOM_DISTANCE_M = 30_000.0
private const val MAX_ZOOM = 16f
/** Per quanto resta visibile il nome di una stazione toccata da lontano. */
private const val NAME_REVEAL_MS = 4_000L
/** Da questo zoom in su compaiono i nomi delle stazioni (13 ≈ quartiere). */
private const val NAMES_MIN_ZOOM = 13f

/** Pallino blu con bordo bianco: "tu sei qui". */
@Composable
private fun UserDot(night: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .background(if (night) BlueDark else Blue, CircleShape)
            .border(3.dp, androidx.compose.ui.graphics.Color.White, CircleShape)
    )
}

/** Etichetta piccola "5 min" sul bordo di un cerchio. */
@Composable
private fun WalkLabel(text: String, night: Boolean) {
    androidx.compose.material3.Text(
        text,
        modifier = Modifier
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 5.dp, vertical = 0.dp),
        fontSize = 10.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        color = (if (night) BlueDark else Blue).copy(alpha = 0.8f),
    )
}

/** Etichetta del percorso suggerito: come quelle dei cerchi, ma grigia come il tratteggio. */
@Composable
private fun RouteLabel(text: String, night: Boolean) {
    androidx.compose.material3.Text(
        text,
        modifier = Modifier
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (night) GreyDark else Grey,
    )
}

/**
 * I primi [meters] metri del percorso [points] ([cumulative] = metri fino a ogni
 * punto): i punti interi più un ultimo punto interpolato a metà tratto.
 */
private fun routePrefix(points: List<GeoPoint>, cumulative: DoubleArray, meters: Double): List<LatLng> {
    val out = ArrayList<LatLng>()
    out.add(LatLng(points[0].lat, points[0].lon))
    for (i in 1 until points.size) {
        if (cumulative[i] <= meters) {
            out.add(LatLng(points[i].lat, points[i].lon))
        } else {
            val seg = cumulative[i] - cumulative[i - 1]
            val t = if (seg > 0) (meters - cumulative[i - 1]) / seg else 0.0
            val a = points[i - 1]
            val b = points[i]
            out.add(LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            break
        }
    }
    return out
}

/** Nome della stazione accanto al pallino, su fondino arrotondato. */
@Composable
private fun StationName(name: String, selected: Boolean, night: Boolean, startPadding: Int = 14) {
    androidx.compose.material3.Text(
        name,
        modifier = Modifier
            .padding(start = startPadding.dp)
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 12.sp,
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (night) InkDark else Ink,
    )
}

/** "→ Ostia" / "→ Roma" accanto al cartello del bus, su fondino arrotondato. */
@Composable
private fun BusDirTag(text: String, night: Boolean) {
    androidx.compose.material3.Text(
        text,
        modifier = Modifier
            .padding(start = 15.dp)  // metà cartello (12 dp) + un po' d'aria
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (night) GreyDark else Grey,
    )
}

/** Etichetta della fermata del bus toccata: "Bus sostitutivo verso Ostia" + palina e orari. */
@Composable
private fun BusStopLabel(stop: BusStopUi, night: Boolean) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .widthIn(max = 230.dp)
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        androidx.compose.material3.Text(
            stop.title,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = if (night) InkDark else Ink,
        )
        androidx.compose.material3.Text(stop.detail, fontSize = 11.sp, color = if (night) GreyDark else Grey)
    }
}

/**
 * Le inquadrature del pulsante "centra": tu, tu + stazione più vicina,
 * solo la stazione (senza GPS), tutta la linea.
 */
private enum class RecenterTarget { USER, USER_AND_STATION, STATION, LINE }

/**
 * Centro e zoom che fanno stare [points] in un riquadro di [widthDp] × [heightDp].
 * Stesso conto che fa Google: a zoom z il mondo intero è largo 256 · 2^z dp,
 * e in verticale si usa la proiezione di Mercatore (le latitudini si "allungano").
 */
private fun fitCamera(points: List<LatLng>, widthDp: Float, heightDp: Float, marginDp: Float): CameraPosition {
    if (points.isEmpty()) return CameraPosition.fromLatLngZoom(LatLng(41.78, 12.40), 10f)
    fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    // Frazione del mondo occupata dai punti, in orizzontale e in verticale.
    val fracX = (maxLon - minLon) / 360.0
    val fracY = (mercatorY(maxLat) - mercatorY(minLat)) / (2 * PI)
    val w = (widthDp - 2 * marginDp).coerceAtLeast(1f)
    val h = (heightDp - 2 * marginDp).coerceAtLeast(1f)
    val zoom = minOf(log2(w / (256 * fracX)), log2(h / (256 * fracY))).toFloat()
    // Centro: a metà in longitudine, e a metà "in Mercatore" in latitudine.
    val midY = (mercatorY(minLat) + mercatorY(maxLat)) / 2
    val centerLat = Math.toDegrees(2 * atan(exp(midY)) - PI / 2)
    return CameraPosition.fromLatLngZoom(LatLng(centerLat, (minLon + maxLon) / 2), zoom.coerceIn(2f, MAX_ZOOM))
}

/** Zoom "di quartiere" per centrare su di te o su una stazione. */
private const val USER_ZOOM = 16f

/**
 * Pulsante "centra". L'icona mostra cosa succede al prossimo tocco:
 *   mirino = su di te · due punti = tu + stazione · un punto = stazione · linea = tutta la linea.
 * Blu quando la mappa è centrata su di te.
 */
@Composable
private fun RecenterButton(
    next: RecenterTarget,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Colori dal tema dell'app, non dalla mappa (vedi MapButtonStyle.kt).
    val dark = io.github.edoppiap.orarilido.ui.theme.LocalDarkTheme.current
    val blue = if (dark) BlueDark else Blue
    Box(
        modifier
            .size(44.dp)
            .mapButtonSurface()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (active) blue else if (dark) InkDark else Ink
        // Crossfade: quando cambia, la vecchia icona sfuma nella nuova.
        Crossfade(targetState = next, animationSpec = tween(250), label = "recenterIcon") { target ->
            if (target == RecenterTarget.USER) RecenterIcon(color) else FitIcon(target, color)
        }
    }
}

/** Icona "mirino": anello, puntino centrale e quattro tacche. */
@Composable
private fun RecenterIcon(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val c = center
        val r = size.minDimension * 0.30f
        drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawCircle(color, radius = size.minDimension * 0.11f, center = c)
        val inner = r
        val outer = size.minDimension * 0.48f
        listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(c.x + dx * inner, c.y + dy * inner),
                end = androidx.compose.ui.geometry.Offset(c.x + dx * outer, c.y + dy * outer),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/** Bussola: l'ago (punta rossa = nord) ruota con la mappa. */
@Composable
private fun CompassButton(bearing: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = io.github.edoppiap.orarilido.ui.theme.LocalDarkTheme.current
    Box(
        modifier
            .size(44.dp)
            .mapButtonSurface()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val grey = if (dark) InkDark else Ink
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            // La mappa ruotata di "bearing" gradi: il nord sta a −bearing.
            rotate(-bearing) {
                val w = size.width
                val h = size.height
                val north = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, 0f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close()
                }
                val south = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, h); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close()
                }
                drawPath(north, androidx.compose.ui.graphics.Color(0xFFE03A3E))
                drawPath(south, grey.copy(alpha = 0.6f))
            }
        }
    }
}

/** Ventaglio sfumato che parte dal centro e punta in alto (verso il nord del marker). */
@Composable
private fun HeadingCone(night: Boolean) {
    val blue = if (night) BlueDark else Blue
    androidx.compose.foundation.Canvas(Modifier.size(72.dp)) {
        drawArc(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(blue.copy(alpha = 0.55f), blue.copy(alpha = 0f)),
                center = center,
                radius = size.minDimension / 2,
            ),
            startAngle = -90f - 30f,  // −90° = verso l'alto; apertura di 60°
            sweepAngle = 60f,
            useCenter = true,
        )
    }
}

/**
 * Icone "inquadra": quattro angoli di cornice e, dentro, cosa verrà inquadrato
 * (tu + stazione, la stazione, oppure un tratto di linea).
 */
@Composable
private fun FitIcon(target: RecenterTarget, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = 2.dp.toPx()
        val k = s * 0.28f  // lunghezza dei tratti degli angoli
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = stroke) = drawLine(
            color, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), w, cap,
        )
        // Cornice: quattro angoli
        line(0f, 0f, k, 0f); line(0f, 0f, 0f, k)
        line(s, 0f, s - k, 0f); line(s, 0f, s, k)
        line(0f, s, k, s); line(0f, s, 0f, s - k)
        line(s, s, s - k, s); line(s, s, s, s - k)
        val ring = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 0.9f)
        when (target) {
            RecenterTarget.USER_AND_STATION -> {
                drawCircle(color, s * 0.10f, androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.64f))  // tu
                drawCircle(color, s * 0.11f, androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.36f), style = ring)  // stazione
            }
            RecenterTarget.STATION ->
                drawCircle(color, s * 0.14f, center, style = ring)
            RecenterTarget.LINE -> {
                line(s * 0.28f, s * 0.72f, s * 0.72f, s * 0.28f, stroke * 0.9f)
                listOf(0.28f, 0.5f, 0.72f).forEach { t ->
                    drawCircle(color, s * 0.07f, androidx.compose.ui.geometry.Offset(s * t, s * (1f - t)))
                }
            }
            RecenterTarget.USER -> Unit
        }
    }
}
