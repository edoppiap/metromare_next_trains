package io.github.edoppiap.orarilido.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.edoppiap.orarilido.ui.theme.CardDark
import io.github.edoppiap.orarilido.ui.theme.Ink
import io.github.edoppiap.orarilido.ui.theme.InkDark
import io.github.edoppiap.orarilido.schedule.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Mappa Google con la linea, le stazioni e (fase 4) la posizione dell'utente.
 *
 * [bottomPadding] = altezza della tendina: la mappa "sa" che quella parte è
 * coperta e centra la linea nello spazio visibile sopra.
 */
@Composable
fun LineMap(
    stations: List<Station>,
    /** Stazioni da evidenziare (quelle "in primo piano"). */
    highlightedIds: Set<String>,
    /** true = stile notturno (scuro). Lo decide il ciclo giorno/notte, non il tema dell'app. */
    night: Boolean,
    /** Posizione dell'utente (null = sconosciuta) e stazione più vicina, per lo zoom. */
    userLocation: GeoPoint?,
    nearestStation: Station?,
    /** A piedi o in bici: decide la grandezza dei cerchi. */
    travelMode: TravelMode,
    /** Cerchi visibili? Si accendono/spengono toccando il pallino dell'utente. */
    showCircles: Boolean,
    onUserClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
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

    val cameraState = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
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
    // Anche il passaggio a piedi <-> in bici fa crescere/restringere i cerchi invece di saltare.
    val metersPerMinute by animateFloatAsState(
        targetValue = (if (travelMode == TravelMode.BIKE) Geo.BIKE_METERS_PER_MINUTE else Geo.WALK_METERS_PER_MINUTE).toFloat(),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "metersPerMinute",
    )

    // Primo caricamento: inquadra tutta la linea (tutte le stazioni).
    LaunchedEffect(mapLoaded) {
        if (!mapLoaded || stations.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply {
            stations.forEach { include(LatLng(it.lat, it.lon)) }
        }.build()
        val marginPx = with(density) { 48.dp.roundToPx() }
        cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, marginPx))
    }

    // Alla prima posizione: zoom che inquadra te e la stazione più vicina.
    // Una volta sola, così poi la mappa resta dove la sposti tu.
    var zoomedToUser by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, userLocation != null) {
        val user = userLocation ?: return@LaunchedEffect
        val station = nearestStation ?: return@LaunchedEffect
        if (!mapLoaded || zoomedToUser) return@LaunchedEffect
        zoomedToUser = true
        // Se sei lontano dalla linea (es. in un'altra città) non ha senso zoomare.
        if (Geo.distanceMeters(user, station) > MAX_ZOOM_DISTANCE_M) return@LaunchedEffect
        val bounds = LatLngBounds.builder()
            .include(LatLng(user.lat, user.lon))
            .include(LatLng(station.lat, station.lon))
            .build()
        val marginPx = with(density) { 72.dp.roundToPx() }
        cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, marginPx), 800)
        // Se tu e la stazione siete vicinissimi, non zoomare troppo.
        if (cameraState.position.zoom > MAX_ZOOM) cameraState.animate(CameraUpdateFactory.zoomTo(MAX_ZOOM), 300)
    }

    // Direzione in cui punta il telefono (null = sensore assente o posizione ignota).
    val heading by rememberHeading(userLocation)

    // ---- Pulsante "centra": ogni tocco passa all'inquadratura successiva ----
    var recenterStep by remember { mutableIntStateOf(0) }
    var followingUser by remember { mutableStateOf(false) }
    // Se l'utente sposta la mappa col dito, il giro riparte dal primo passo.
    LaunchedEffect(cameraState.isMoving) {
        if (cameraState.isMoving && cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            recenterStep = 0
            followingUser = false
        }
    }
    val scope = rememberCoroutineScope()
    val primary = stations.filter { it.id in highlightedIds }
    // Le inquadrature disponibili dipendono da cosa sappiamo.
    val targets = buildList {
        if (userLocation != null) add(RecenterTarget.USER)
        if (primary.isNotEmpty()) add(if (userLocation != null) RecenterTarget.USER_AND_PRIMARY else RecenterTarget.PRIMARY)
        add(RecenterTarget.LINE)
    }
    // Cosa farà il PROSSIMO tocco: l'icona del pulsante lo mostra.
    val nextTarget = targets[recenterStep % targets.size]
    val onRecenter: () -> Unit = {
        val target = nextTarget
        recenterStep++
        followingUser = target == RecenterTarget.USER
        val margin = with(density) { 72.dp.roundToPx() }
        scope.launch {
            val points = when (target) {
                RecenterTarget.USER -> emptyList()
                RecenterTarget.USER_AND_PRIMARY ->
                    primary.map { LatLng(it.lat, it.lon) } + LatLng(userLocation!!.lat, userLocation.lon)
                RecenterTarget.PRIMARY -> primary.map { LatLng(it.lat, it.lon) }
                RecenterTarget.LINE -> stations.map { LatLng(it.lat, it.lon) }
            }
            when {
                target == RecenterTarget.USER ->
                    cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(userLocation!!.lat, userLocation.lon), USER_ZOOM), 600)
                points.size == 1 ->
                    cameraState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), USER_ZOOM), 600)
                else -> {
                    val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
                    cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, margin), 600)
                    if (target != RecenterTarget.LINE && cameraState.position.zoom > MAX_ZOOM) {
                        cameraState.animate(CameraUpdateFactory.zoomTo(MAX_ZOOM), 300)
                    }
                }
            }
        }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            contentPadding = PaddingValues(bottom = bottomPadding),
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
            onMapLoaded = { mapLoaded = true },
        ) {
            if (linePoints.isNotEmpty()) {
                Polyline(points = linePoints, color = lineColor, width = with(density) { 5.dp.toPx() })
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
                    val radius = minutes * metersPerMinute.toDouble() * g
                    Circle(
                        center = center,
                        radius = radius,
                        strokeColor = lineColor.copy(alpha = 0.55f),
                        strokeWidth = with(density) { 1.5.dp.toPx() },
                        fillColor = lineColor.copy(alpha = 0.04f),
                    )
                    // Etichetta "5 min" in cima al cerchio (1° di latitudine ≈ 111 km).
                    MarkerComposable(
                        keys = arrayOf<Any>("walk", minutes, dark, travelMode),
                        state = rememberUpdatedMarkerState(LatLng(user.lat + radius / 111_320.0, user.lon)),
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        zIndex = 0.5f,
                        alpha = g,  // le etichette sfumano insieme ai cerchi
                    ) {
                        WalkLabel("$emoji $minutes min", night)
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
                .padding(end = 12.dp, bottom = bottomPadding + 12.dp + 44.dp + 10.dp),
        ) {
            CompassButton(
                bearing = bearing,
                night = night,
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
            active = followingUser,
            night = night,
            onClick = onRecenter,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = bottomPadding + 12.dp),
        )
    }
}

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

/** Cerchi intorno all'utente: minuti a piedi o in bici. */
private val WALK_MINUTES = listOf(5, 10, 15)
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
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (night) BlueDark else Blue,
    )
}

/** Nome della stazione accanto al pallino, su fondino arrotondato. */
@Composable
private fun StationName(name: String, selected: Boolean, night: Boolean) {
    androidx.compose.material3.Text(
        name,
        modifier = Modifier
            .padding(start = 14.dp)
            .background(if (night) CardDark else Card, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 12.sp,
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (night) InkDark else Ink,
    )
}

/** Le inquadrature del pulsante "centra", nell'ordine in cui si susseguono. */
private enum class RecenterTarget { USER, USER_AND_PRIMARY, PRIMARY, LINE }

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
    night: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blue = if (night) BlueDark else Blue
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (night) CardDark else Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (active) blue else if (night) InkDark else Ink
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
private fun CompassButton(bearing: Float, night: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (night) CardDark else Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val grey = if (night) InkDark else Ink
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
            RecenterTarget.USER_AND_PRIMARY -> {
                drawCircle(color, s * 0.10f, androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.64f))  // tu
                drawCircle(color, s * 0.11f, androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.36f), style = ring)  // stazione
            }
            RecenterTarget.PRIMARY ->
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
