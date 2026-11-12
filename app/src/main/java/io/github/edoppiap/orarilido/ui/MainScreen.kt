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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        val sheetHeight = maxHeight / 3  // altezza della tendina a 1/3
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = sheetHeight,
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
            // Il "corpo" dietro la tendina: la mappa, a TUTTO schermo, così si
            // vede anche dietro gli angoli smussati della tendina.
            //
            // Velocità: creare la mappa Google costa centinaia di ms. Per non
            // ritardare gli orari, al primo fotogramma c'è solo lo sfondo;
            // la mappa viene creata subito DOPO che gli orari sono a schermo.
            var showMap by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }  // aspetta che il primo fotogramma sia disegnato
                showMap = true
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (showMap) {
                    LineMap(
                        stations = state.stations,
                        selectedStationId = state.stationId,
                        night = state.mapNight,
                        bottomPadding = sheetHeight,
                        onStationClick = onSelectStation,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Contenuto della tendina: una scheda per stazione, una sotto l'altra.
 * La prima è quella in primo piano (visibile con la tendina a 1/3);
 * per vedere le altre basta scorrere. Tocco su una scheda = la porta in primo piano.
 */
@Composable
private fun SheetContent(state: MainUiState, onSelectStation: (String) -> Unit) {
    val listState = rememberLazyListState()
    // Quando cambia la stazione in primo piano, torna in cima alla lista.
    LaunchedEffect(state.stationId) { listState.scrollToItem(0) }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(),  // a tendina alzata occupa tutto lo schermo
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(state.cards, key = { _, card -> card.stationId }) { i, card ->
            StationCard(
                card = card,
                primary = i == 0,
                validFrom = state.validFrom,
                onClick = { onSelectStation(card.stationId) },
            )
        }
    }
}

/** Riquadro con angoli smussati, colore "scheda" sopra lo sfondo della tendina. */
@Composable
private fun SheetCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) { content() }
}

/** Scheda di una stazione: nome come titolo, una riga per direzione. */
@Composable
private fun StationCard(card: StationCardUi, primary: Boolean, validFrom: String, onClick: () -> Unit) {
    SheetCard(if (primary) Modifier else Modifier.clickable(onClick = onClick)) {
        Text(
            card.name,
            modifier = Modifier.padding(bottom = 12.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        card.blocks.forEachIndexed { i, block ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            CompactDirectionRow(block)
        }
        if (primary) {
            Text(
                "Orari programmati, non in tempo reale · in vigore dal $validFrom",
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        block.rows.forEachIndexed { i, row ->
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
                validFrom = "9/11/2026",
                cards = listOf("acilia" to "Acilia", "vitinia" to "Vitinia").map { (id, name) ->
                    StationCardUi(
                        id, name, listOf(
                            DirectionBlock("Verso Roma", "Porta San Paolo", roma),
                            DirectionBlock("Verso Ostia", "Cristoforo Colombo", ostia),
                        )
                    )
                },
                stations = listOf(Station("acilia", "Acilia", 0.0, 0.0), Station("vitinia", "Vitinia", 0.0, 0.0)),
            ),
            onSelectStation = {},
        )
    }
}
