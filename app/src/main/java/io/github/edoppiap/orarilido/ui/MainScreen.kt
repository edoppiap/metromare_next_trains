package io.github.edoppiap.orarilido.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.ui.theme.OrariTheme
import kotlinx.coroutines.launch
/**
 * Schermata principale, stile Citymapper: mappa a tutto schermo e sopra
 * una tendina con tre altezze (vedi [SheetStop]):
 *   - all'apertura occupa poco più di metà schermo: gli orari contano più della mappa;
 *   - tutta su: solo orari;
 *   - tutta giù ("mappa piena"): resta una striscia coi prossimi treni della
 *     stazione in primo piano, e sulla mappa compaiono i cerchi a piedi/in bici.
 * Nella tendina:
 *   - "In primo piano": la stazione più vicina oppure le preferite
 *   - "Altre stazioni": tutte le altre, per distanza
 * Tocco sugli orari di una scheda = orari di tutta la giornata.
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onOpenDay: (stationId: String, direction: Direction) -> Unit,
    onModeChange: (PrimaryMode) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onTravelMode: (TravelMode) -> Unit,
    onMenu: () -> Unit,
    /** C'è una pagina sopra (giornata, menu): conta il suo sfondo per la barra di stato. */
    overlayOpen: Boolean = false,
) {
    if (!state.ready) return  // dura pochi ms: la finestra resta sullo sfondo

    val sheet = rememberMapSheetState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val fullMap = sheet.stop == SheetStop.COLLAPSED
    // ---- Cerchi intorno all'utente ----
    // In automatico: visibili solo a mappa piena, tranne se ci sei arrivato
    // spostando la mappa col dito (stai cercando qualcosa: sarebbero una distrazione).
    // Il tocco sul pallino vince sull'automatico, a qualunque altezza della tendina;
    // quando la tendina cambia altezza si torna all'automatico.
    var circlesHeldBack by remember { mutableStateOf(false) }
    /** Scelta fatta toccando il pallino: null = decide l'automatico. */
    var circlesByTap by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(sheet.stop) {
        circlesByTap = null
        if (!fullMap) circlesHeldBack = false
    }
    val circlesOn = circlesByTap ?: (fullMap && !circlesHeldBack)
    // Direzione scelta a mano nella striscia a mappa piena, e per quale stazione.
    // Vale solo per quella stazione: se cambia, si torna alla direzione predefinita.
    var stripChoiceStation by rememberSaveable { mutableStateOf<String?>(null) }
    var stripChoiceDirection by rememberSaveable { mutableStateOf(Direction.TO_ROMA) }

    // "Indietro" con la tendina tutta su o tutta giù la riporta a metà invece di chiudere l'app.
    BackHandler(enabled = sheet.stop != SheetStop.HALF) { sheet.animateTo(SheetStop.HALF) }

    // ---- Icone della barra di stato (ora, batteria) ----
    // Devono contrastare con quello che c'è DIETRO, non col tema del telefono:
    // di giorno con il telefono in tema scuro, icone bianche sulla mappa chiara non si leggono.
    // Dietro c'è la mappa, salvo quando la tendina (o una pagina sopra) arriva in cima.
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    // La chiave statusBarPx serve: al primo fotogramma l'altezza della barra vale ancora 0,
    // e senza chiave la soglia resterebbe 0 per sempre (era il bug "a tendina aperta non cambia").
    // Si cambia colore quando il bordo della tendina entra sotto la barra.
    val sheetUnderStatusBar by remember(statusBarPx) { derivedStateOf { sheet.offset < statusBarPx } }
    val appIsLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val lightIcons = if (overlayOpen || sheetUnderStatusBar) !appIsLight else state.mapNight
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // "Appearance light" = sfondo chiaro, quindi icone SCURE.
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !lightIcons
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Parte di mappa coperta dalla tendina, quando è ferma: la mappa inquadra
        // le cose nello spazio che resta (e se cambia, riadatta lo zoom: vedi LineMap).
        val coveredHeight = if (fullMap) PEEK_HEIGHT + navBar else screenHeight * HALF_FRACTION
        // Pulsanti della mappa: seguono il bordo della tendina, ma non salgono oltre la metà.
        val halfTopPx = with(density) { (screenHeight * (1f - HALF_FRACTION)).toPx() }
        val screenPx = with(density) { screenHeight.toPx() }
        val controlsLift = { (screenPx - maxOf(sheet.offset, halfTopPx)).roundToInt() }

        MapSheetLayout(
            state = sheet,
            listAtTop = { !listState.canScrollBackward },
            peekHeight = PEEK_HEIGHT,
            halfFraction = HALF_FRACTION,
            background = {
                MapLayer(
                    state = state,
                    bottomPadding = coveredHeight,
                    controlsLift = controlsLift,
                    showCircles = circlesOn,
                    // Tocco sul pallino: inverte quello che vedi adesso.
                    onUserClick = { circlesByTap = !circlesOn },
                    // Chi sposta la mappa vuole vederla: la tendina scende a "mappa piena".
                    // Cerchi trattenuti, a meno che tu non li avessi appena accesi a mano.
                    onMapGesture = {
                        if (sheet.stop == SheetStop.HALF) {
                            circlesHeldBack = circlesByTap != true
                            sheet.animateTo(SheetStop.COLLAPSED)
                        }
                    },
                    onStationClick = { id ->
                        // Tocco su una stazione: la tendina (se serve) risale e scorre alla sua scheda.
                        if (fullMap) sheet.animateTo(SheetStop.HALF)
                        val index = sheetItems(state).indexOfFirst { it is SheetItem.Card && it.card.stationId == id }
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    },
                    onMenu = onMenu,
                    travelToggleVisible = state.userLocation != null && circlesOn,
                    onTravelMode = onTravelMode,
                )
            },
            strip = {
                val card = state.primaryCards.firstOrNull() ?: state.otherCards.firstOrNull()
                if (card != null) CollapsedStrip(
                    card = card,
                    direction = if (stripChoiceStation == card.stationId) stripChoiceDirection
                        else defaultStripDirection(card.stationId),
                    onDirection = {
                        stripChoiceStation = card.stationId
                        stripChoiceDirection = it
                    },
                    onClick = { sheet.animateTo(SheetStop.HALF) },
                )
            },
        ) {
            SheetContent(state, listState, onOpenDay, onModeChange, onToggleFavorite)
        }
    }
}

/** Mappa con i pulsanti sopra (menu, a piedi/in bici). */
@Composable
private fun MapLayer(
    state: MainUiState,
    bottomPadding: Dp,
    controlsLift: () -> Int,
    showCircles: Boolean,
    onUserClick: () -> Unit,
    onMapGesture: () -> Unit,
    onStationClick: (String) -> Unit,
    onMenu: () -> Unit,
    travelToggleVisible: Boolean,
    onTravelMode: (TravelMode) -> Unit,
) {
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
                bottomPadding = bottomPadding,
                controlsLift = controlsLift,
                userLocation = state.userLocation,
                nearestStation = state.nearestStation,
                travelMode = state.travelMode,
                showCircles = showCircles,
                onUserClick = onUserClick,
                onMapGesture = onMapGesture,
                onStationClick = onStationClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Pulsanti sopra la mappa: menu a sinistra, a piedi/in bici a destra.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapButton(onClick = onMenu) { MenuIcon(MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.weight(1f))
            // Il selettore serve solo quando ci sono i cerchi (mappa piena, posizione nota).
            AnimatedVisibility(visible = travelToggleVisible, enter = fadeIn(), exit = fadeOut()) {
                TravelToggle(state.travelMode, onTravelMode)
            }
        }
    }
}

/**
 * Striscia mostrata a mappa piena: la stazione in primo piano e i prossimi
 * tre treni in UNA direzione, scelta col selettore (di solito ne interessa una).
 * Tocco fuori dal selettore = la tendina torna a metà.
 *   Acilia Sud/Dragona                    1,8 km
 *   [Ostia | Roma]   3 min    18 min    33 min
 *                    08:03    08:18     08:33
 */
@Composable
private fun CollapsedStrip(
    card: StationCardUi,
    direction: Direction,
    onDirection: (Direction) -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Ai capolinea c'è una sola direzione: si mostra quella, senza selettore.
    val block = card.blocks.firstOrNull { it.direction == direction } ?: card.blocks.firstOrNull() ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.name,
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            card.distance?.let { Text(it, fontSize = 13.sp, color = colors.onSurfaceVariant) }
        }
        Spacer(Modifier.height(6.dp))
        // Selettore a sinistra, orari a destra, sulla stessa riga (come nelle schede).
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.blocks.size > 1) {
                SegmentedToggle(
                    options = listOf(Direction.TO_OSTIA to "Ostia", Direction.TO_ROMA to "Roma"),
                    selected = block.direction,
                    onSelect = onDirection,
                    segmentPadding = 10.dp,
                )
            } else {
                // Capolinea: una sola direzione, pillola fissa.
                DirectionPill(block.title.removePrefix("Verso "))
            }
            Spacer(Modifier.width(12.dp))
            // Cambiando direzione, i vecchi orari sfumano nei nuovi.
            androidx.compose.animation.Crossfade(
                targetState = block,
                animationSpec = tween(250),
                label = "stripTimes",
                modifier = Modifier.weight(1f),
            ) { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (b.rows.isEmpty()) {
                        Text("Nessun treno", fontSize = 15.sp, color = colors.onSurfaceVariant)
                    }
                    b.rows.forEachIndexed { i, row ->
                        Column(Modifier.weight(if (i == 0) 1.3f else 1f)) {
                            Text(
                                waitText(row),
                                fontSize = if (i == 0) 20.sp else 15.sp,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (row.imminent) colors.tertiary else colors.onBackground,
                                maxLines = 1,
                            )
                            Text(clockText(row), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Direzione mostrata di default nella striscia: verso Roma, tranne che dalle
 * stazioni già a Roma, da dove di solito si va verso Ostia.
 */
private fun defaultStripDirection(stationId: String): Direction =
    if (stationId in ROMA_SIDE_STATIONS) Direction.TO_OSTIA else Direction.TO_ROMA

private val ROMA_SIDE_STATIONS = setOf("porta_san_paolo", "basilica_san_paolo", "eur_magliana")

/** Parte di tendina visibile a mappa piena: maniglia + striscia (barra di navigazione esclusa). */
private val PEEK_HEIGHT = 116.dp
/** Parte di schermo occupata dalla tendina all'apertura. */
private const val HALF_FRACTION = 0.55f

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
            // In fondo, spazio per la barra di navigazione di Android.
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
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

/** Selettore a due segmenti: la pillola blu scivola sotto quello scelto. */
@Composable
private fun ModeToggle(mode: PrimaryMode, onModeChange: (PrimaryMode) -> Unit) {
    SegmentedToggle(
        options = listOf(PrimaryMode.NEAREST to "📍 Vicina", PrimaryMode.FAVORITES to "★ Preferite"),
        selected = mode,
        onSelect = onModeChange,
    )
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
private fun DirectionPill(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    Text(
        text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
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
            onOpenDay = { _, _ -> }, onModeChange = {}, onToggleFavorite = {}, onTravelMode = {}, onMenu = {},
        )
    }
}

/** Pulsante tondo sopra la mappa. */
@Composable
private fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Selettore 🚶 | 🚲: cambia la grandezza dei cerchi sulla mappa. */
@Composable
private fun TravelToggle(mode: TravelMode, onChange: (TravelMode) -> Unit) {
    SegmentedToggle(
        options = listOf(TravelMode.WALK to "🚶", TravelMode.BIKE to "🚲"),
        selected = mode,
        onSelect = onChange,
        fontSize = 18.sp,
    )
}
