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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import io.github.edoppiap.orarilido.data.PrimaryMode
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.ui.theme.OrariTheme
import kotlinx.coroutines.launch

/**
 * Schermata principale, stile Citymapper: mappa a tutto schermo e sopra
 * una tendina trascinabile, alta 1/3 all'apertura. Nella tendina:
 *   - "In primo piano": la stazione più vicina oppure le preferite
 *   - "Altre stazioni": tutte le altre, per distanza
 * Tocco sugli orari di una scheda = orari di tutta la giornata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onOpenDay: (stationId: String, direction: Direction) -> Unit,
    onModeChange: (PrimaryMode) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (!state.ready) return  // dura pochi ms: la finestra resta sullo sfondo

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
            sheetDragHandle = { DragHandle() },
            sheetContent = {
                // A tendina alzata del tutto, lascia spazio alla barra di stato in alto.
                val expanded = sheetState.targetValue == SheetValue.Expanded
                SheetContent(state, listState, expanded, onOpenDay, onModeChange, onToggleFavorite)
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
                        highlightedIds = state.highlightedIds,
                        night = state.mapNight,
                        bottomPadding = sheetHeight,
                        userLocation = state.userLocation,
                        nearestStation = state.nearestStation,
                        // Tocco su una stazione della mappa: la tendina scorre alla sua scheda.
                        onStationClick = { id ->
                            val index = sheetItems(state).indexOfFirst { it is SheetItem.Card && it.card.stationId == id }
                            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Maniglia della tendina, più bassa di quella standard per lasciare spazio alle schede. */
@Composable
private fun DragHandle() {
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
    )
}

// ---- Elementi della tendina, nell'ordine in cui compaiono ----

private sealed interface SheetItem {
    val key: String
    data object PrimaryHeader : SheetItem { override val key = "hdr_primary" }
    data object FavoritesHint : SheetItem { override val key = "hint" }
    data object OthersHeader : SheetItem { override val key = "hdr_others" }
    data class Card(val card: StationCardUi, val primary: Boolean) : SheetItem {
        override val key = (if (primary) "p_" else "o_") + card.stationId
    }
}

/** La lista degli elementi della tendina: serve sia per disegnarla sia per trovare una scheda. */
private fun sheetItems(state: MainUiState): List<SheetItem> = buildList {
    add(SheetItem.PrimaryHeader)
    if (state.primaryCards.isEmpty()) add(SheetItem.FavoritesHint)
    state.primaryCards.forEach { add(SheetItem.Card(it, primary = true)) }
    add(SheetItem.OthersHeader)
    state.otherCards.forEach { add(SheetItem.Card(it, primary = false)) }
}

@Composable
private fun SheetContent(
    state: MainUiState,
    listState: LazyListState,
    expanded: Boolean,
    onOpenDay: (String, Direction) -> Unit,
    onModeChange: (PrimaryMode) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // Quando cambia la modalità, torna in cima alla lista.
    LaunchedEffect(state.mode) { listState.scrollToItem(0) }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(),  // a tendina alzata occupa tutto lo schermo
        state = listState,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = if (expanded) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sheetItems(state), key = { it.key }) { item ->
            when (item) {
                SheetItem.PrimaryHeader -> PrimaryHeader(state.mode, onModeChange)
                SheetItem.FavoritesHint -> FavoritesHint()
                SheetItem.OthersHeader -> OthersHeader(state.validFrom)
                is SheetItem.Card -> StationCard(
                    card = item.card,
                    onOpenDay = { direction -> onOpenDay(item.card.stationId, direction) },
                    onToggleFavorite = { onToggleFavorite(item.card.stationId) },
                )
            }
        }
    }
}

/** "In primo piano          [📍 Vicina | ⭐ Preferite]" */
@Composable
private fun PrimaryHeader(mode: PrimaryMode, onModeChange: (PrimaryMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "In primo piano",
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        ModeToggle(mode, onModeChange)
    }
}

/** Selettore a due segmenti, quello attivo pieno di blu. */
@Composable
private fun ModeToggle(mode: PrimaryMode, onModeChange: (PrimaryMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .padding(3.dp),
    ) {
        listOf(PrimaryMode.NEAREST to "📍 Vicina", PrimaryMode.FAVORITES to "★ Preferite").forEach { (m, label) ->
            val selected = m == mode
            Text(
                label,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(if (selected) Modifier.background(colors.primary) else Modifier)
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

/** Mostrato in "Preferite" quando non ce n'è ancora nessuna. */
@Composable
private fun FavoritesHint() {
    SheetCard {
        Text(
            "Nessuna stazione preferita.\nTocca ☆ su una stazione qui sotto per aggiungerla.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Altre stazioni ───── orari programmati · dal 9/11/2026" */
@Composable
private fun OthersHeader(validFrom: String) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "Altre stazioni",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Orari programmati · in vigore dal $validFrom",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Riquadro con angoli smussati, colore "scheda" sopra lo sfondo della tendina. */
@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
    ) { content() }
}

/** Scheda di una stazione: nome e stella, poi una riga per direzione. */
@Composable
private fun StationCard(
    card: StationCardUi,
    onOpenDay: (Direction) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.name,
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            // Distanza dall'utente in linea d'aria (solo se la posizione è nota).
            card.distance?.let {
                Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
            }
            // Stella: piena e blu se preferita, vuota e grigia altrimenti.
            Text(
                if (card.favorite) "★" else "☆",
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 24.sp,
                color = if (card.favorite) colors.primary else colors.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(end = 8.dp)) {
            card.blocks.forEachIndexed { i, block ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)
                CompactDirectionRow(block, onClick = { onOpenDay(block.direction) })
            }
        }
    }
}

/** [Verso Roma]   3 min    18 min    33 min
 *                 08:03    08:18     08:33   */
@Composable
private fun CompactDirectionRow(block: DirectionBlock, onClick: () -> Unit) {
    // Tutta la riga è toccabile: apre gli orari della giornata per questa direzione.
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    fun card(id: String, name: String, fav: Boolean) = StationCardUi(
        id, name, fav, "350 m", listOf(
            DirectionBlock(Direction.TO_ROMA, "Verso Roma", "Porta San Paolo", roma),
            DirectionBlock(Direction.TO_OSTIA, "Verso Ostia", "Cristoforo Colombo", ostia),
        )
    )
    OrariTheme {
        MainScreen(
            MainUiState(
                ready = true,
                validFrom = "9/11/2026",
                primaryCards = listOf(card("acilia", "Acilia", true)),
                otherCards = listOf(card("vitinia", "Vitinia", false)),
            ),
            onOpenDay = { _, _ -> }, onModeChange = {}, onToggleFavorite = {},
        )
    }
}
