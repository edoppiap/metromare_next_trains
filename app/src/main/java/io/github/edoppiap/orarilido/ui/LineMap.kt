package io.github.edoppiap.orarilido.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import io.github.edoppiap.orarilido.R
import io.github.edoppiap.orarilido.location.Geo
import io.github.edoppiap.orarilido.location.GeoPoint
import io.github.edoppiap.orarilido.ui.theme.Blue
import io.github.edoppiap.orarilido.ui.theme.BlueDark
import io.github.edoppiap.orarilido.ui.theme.Card
import io.github.edoppiap.orarilido.ui.theme.CardDark
import io.github.edoppiap.orarilido.schedule.Station
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

    GoogleMap(
        modifier = modifier,
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
                title = s.name,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = if (selected) 2f else 1f,
                onClick = {
                    onStationClick(s.id)
                    false  // false = mostra anche l'etichetta col nome
                },
            ) {
                StationDot(selected, night)
            }
        }

        // Posizione dell'utente: cerchi 5/10/15 minuti a piedi e pallino.
        userLocation?.let { user ->
            val center = LatLng(user.lat, user.lon)
            WALK_MINUTES.forEach { minutes ->
                val radius = minutes * Geo.WALK_METERS_PER_MINUTE
                Circle(
                    center = center,
                    radius = radius,
                    strokeColor = lineColor.copy(alpha = 0.55f),
                    strokeWidth = with(density) { 1.5.dp.toPx() },
                    fillColor = lineColor.copy(alpha = 0.04f),
                )
                // Etichetta "5 min" in cima al cerchio (1° di latitudine ≈ 111 km).
                MarkerComposable(
                    keys = arrayOf<Any>("walk", minutes, dark),
                    state = rememberUpdatedMarkerState(LatLng(user.lat + radius / 111_320.0, user.lon)),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = 0.5f,
                ) {
                    WalkLabel("$minutes min", night)
                }
            }
            MarkerComposable(
                keys = arrayOf<Any>("user", dark),
                state = rememberUpdatedMarkerState(center),
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 3f,
            ) {
                UserDot(night)
            }
        }
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

/** Cerchi intorno all'utente: minuti a piedi. */
private val WALK_MINUTES = listOf(5, 10, 15)
/** Oltre questa distanza dalla stazione più vicina, niente zoom automatico. */
private const val MAX_ZOOM_DISTANCE_M = 30_000.0
private const val MAX_ZOOM = 16f

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
