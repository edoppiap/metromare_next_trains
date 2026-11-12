package io.github.edoppiap.orarilido.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import io.github.edoppiap.orarilido.R
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
    selectedStationId: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onStationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lineColor = MaterialTheme.colorScheme.primary
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
            val selected = s.id == selectedStationId
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
                StationDot(selected)
            }
        }
    }
}

@Composable
private fun StationDot(selected: Boolean) {
    val blue = MaterialTheme.colorScheme.primary
    val size = if (selected) 18.dp else 12.dp
    Box(
        Modifier
            .size(size)
            .background(if (selected) blue else MaterialTheme.colorScheme.surface, CircleShape)
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
