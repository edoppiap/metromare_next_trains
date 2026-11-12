package io.github.edoppiap.orarilido.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.edoppiap.orarilido.schedule.Station
import io.github.edoppiap.orarilido.ui.theme.OrariTheme
import kotlinx.coroutines.launch

/** Quanti treni per direzione nella scheda compatta (tendina a 1/3). */
private const val COMPACT_ROWS = 3

/**
 * Schermata principale, stile Citymapper: mappa a tutto schermo e sopra
 * una tendina trascinabile. All'apertura la tendina è alta 1/3 dello
 * schermo e mostra la scheda della stazione; tirata su mostra più treni
 * e l'elenco delle stazioni.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainUiState, onSelectStation: (String) -> Unit) {
    if (!state.ready) return  // dura pochi ms: la finestra resta sullo sfondo

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()

    // Con la tendina alzata, "indietro" la riporta a 1/3 invece di chiudere l'app.
    BackHandler(enabled = sheetState.currentValue == SheetValue.Expanded) {
        scope.launch { sheetState.partialExpand() }
    }

    // BoxWithConstraints ci dice quanto è alto lo schermo, per calcolare 1/3.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = maxHeight / 3,
            // Sfondo della tendina = sfondo dell'app; le schede sopra sono più chiare.
            sheetContainerColor = MaterialTheme.colorScheme.background,
            sheetContent = {
                SheetContent(
                    state = state,
                    onSelectStation = { id ->
                        onSelectStation(id)
                        scope.launch { sheetState.partialExpand() }  // torna a 1/3 sulla nuova stazione
                    },
                )
            },
        ) {
            // Il "corpo" dietro la tendina: qui andrà la mappa Google.
            MapPlaceholder(Modifier.padding(bottom = maxHeight / 3))
        }
    }
}

/** Sfondo provvisorio al posto della mappa, finché non c'è la chiave Google Maps. */
@Composable
private fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text("Mappa in arrivo", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

/**
 * Contenuto della tendina: una colonna scorrevole di schede.
 * La prima (stazione) è quella visibile con la tendina a 1/3;
 * le altre compaiono tirandola su.
 */
@Composable
private fun SheetContent(state: MainUiState, onSelectStation: (String) -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(),  // a tendina alzata occupa tutto lo schermo
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StationCard(state) }

        // ---- Visibile tirando su la tendina ----
        state.blocks.forEach { block ->
            item(key = "more_${block.title}") { DirectionCard(block) }
        }
        item(key = "stations") { StationsCard(state, onSelectStation) }
    }
}

/** Riquadro con angoli smussati, colore "scheda" sopra lo sfondo della tendina. */
@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

/** Titolo di una scheda (es. il nome della stazione). */
@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 12.dp),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** La scheda della stazione: nome come titolo, una riga per direzione. */
@Composable
private fun StationCard(state: MainUiState) {
    SheetCard {
        CardTitle(state.stationName)
        state.blocks.forEachIndexed { i, block ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            CompactDirectionRow(block)
        }
        Text(
            "Orari programmati, non in tempo reale · in vigore dal ${state.validFrom}",
            modifier = Modifier.padding(top = 12.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scheda "lunga" di una direzione: pillola + capolinea, poi tutti i treni. */
@Composable
private fun DirectionCard(block: DirectionBlock) {
    SheetCard {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectionPill(block.title)
            Text(
                "  " + block.terminus,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        block.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FullDepartureLine(row)
        }
    }
}

/** Scheda con l'elenco delle stazioni (nella fase 4: ordinate per distanza). */
@Composable
private fun StationsCard(state: MainUiState, onSelectStation: (String) -> Unit) {
    SheetCard {
        CardTitle("Stazioni")
        state.stations.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StationLine(s, selected = s.id == state.stationId, onClick = { onSelectStation(s.id) })
        }
    }
}

/** [Verso Roma]   3 min    18 min    33 min
 *                 08:03    08:18     08:33   */
@Composable
private fun CompactDirectionRow(block: DirectionBlock) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DirectionPill(block.title, Modifier.width(112.dp))
        Spacer(Modifier.width(12.dp))
        block.rows.take(COMPACT_ROWS).forEachIndexed { i, row ->
            Column(Modifier.weight(if (i == 0) 1.3f else 1f)) {
                Text(
                    waitText(row),
                    fontSize = if (i == 0) 22.sp else 16.sp,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (row.imminent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    clockText(row),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Riga della lista lunga: "tra 18 min .......... 08:18" */
@Composable
private fun FullDepartureLine(row: DepartureRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (row.waitUnit.isEmpty()) row.wait else "tra ${row.wait} ${row.waitUnit}",
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (row.imminent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
        Text(clockText(row), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StationLine(station: Station, selected: Boolean, onClick: () -> Unit) {
    Text(
        station.name,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DirectionPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        maxLines = 1,
    )
}

/** "3 min", "ora", "1 h 05" */
private fun waitText(row: DepartureRow): String =
    if (row.waitUnit.isEmpty()) row.wait else "${row.wait} ${row.waitUnit}"

/** "08:17", con "?" rosso ad apice se la corsa non è confermata. */
@Composable
private fun clockText(row: DepartureRow): AnnotatedString {
    val red = MaterialTheme.colorScheme.error
    return buildAnnotatedString {
        append(row.clock)
        if (row.unconfirmed) {
            withStyle(
                SpanStyle(
                    color = red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 0.8.em,
                    baselineShift = BaselineShift.Superscript,
                )
            ) { append("?") }
        }
    }
}

// Anteprima per Android Studio (pannello "Preview"), con dati finti.
@Preview(showBackground = true, heightDp = 780, widthDp = 390)
@Composable
private fun MainScreenPreview() {
    val roma = listOf(
        DepartureRow("2", "min", "08:03", unconfirmed = false, imminent = true),
        DepartureRow("9", "min", "08:10", unconfirmed = true, imminent = false),
        DepartureRow("17", "min", "08:18", unconfirmed = false, imminent = false),
    )
    val ostia = listOf(
        DepartureRow("5", "min", "08:06", unconfirmed = false, imminent = false),
        DepartureRow("20", "min", "08:21", unconfirmed = false, imminent = false),
        DepartureRow("35", "min", "08:36", unconfirmed = false, imminent = false),
    )
    OrariTheme {
        MainScreen(
            MainUiState(
                ready = true,
                stationId = "acilia",
                stationName = "Acilia",
                validFrom = "9/11/2026",
                blocks = listOf(
                    DirectionBlock("Verso Roma", "Porta San Paolo", roma),
                    DirectionBlock("Verso Ostia", "Cristoforo Colombo", ostia),
                ),
                stations = listOf(Station("acilia", "Acilia"), Station("vitinia", "Vitinia")),
            ),
            onSelectStation = {},
        )
    }
}
