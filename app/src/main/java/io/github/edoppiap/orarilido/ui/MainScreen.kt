package io.github.edoppiap.orarilido.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
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
    /** Pulsante X: esci dalla modalità Go. */
    onExitGo: () -> Unit = {},
    /** Tocco sulla scheda di una fermata in Go: la fissa nella striscia. */
    onPinStop: (String) -> Unit = {},
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
    // Modalità Go attiva (sul treno, non solo "sto riconoscendo"): i cerchi
    // a piedi/in bici non servono, al loro posto in alto c'è la X.
    val goActive = state.go?.recognizing == false
    // Inquadratura "tutta la linea": i cerchi lì non servono (deciso 13/11). Tornano
    // in "tu" e "tu + stazione". Cambiando inquadratura si torna all'automatico.
    var lineFraming by remember { mutableStateOf(true) }  // la mappa nasce sulla linea intera
    LaunchedEffect(lineFraming) { circlesByTap = null }
    val circlesOn = !goActive && (circlesByTap ?: (fullMap && !circlesHeldBack && !lineFraming))
    /** Il percorso suggerito è disegnato sulla mappa (solo in "tu + stazione", lo decide LineMap). */
    var routeOnMap by remember { mutableStateOf(false) }
    // Direzione scelta a mano nella striscia a mappa piena, e per quale stazione.
    // Vale solo per quella stazione: se cambia, si torna alla direzione predefinita.
    var stripChoiceStation by rememberSaveable { mutableStateOf<String?>(null) }
    var stripChoiceDirection by rememberSaveable { mutableStateOf(Direction.TO_ROMA) }

    // Quando la tendina inizia a scendere verso la mappa piena, la lista torna
    // in cima: è la prima scheda (con l'intestazione, in Go) a diventare la striscia.
    LaunchedEffect(Unit) {
        snapshotFlow { sheet.collapseProgress > 0.02f }.collect { if (it) listState.animateScrollToItem(0) }
    }

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
                        val index = sheetItems(state).indexOfFirst {
                            (it is SheetItem.Card && it.card.stationId == id) || (it is SheetItem.GoStop && it.stop.stationId == id)
                        }
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    },
                    onMenu = onMenu,
                    // Il selettore cambia cerchi E percorso: serve se c'è almeno uno dei due.
                    travelToggleVisible = state.userLocation != null && (circlesOn || routeOnMap),
                    onRouteVisible = { routeOnMap = it },
                    onLineFraming = { lineFraming = it },
                    // Punti di interesse: solo a mappa piena e mai in Go (non "inquinano" la schermata).
                    showPlaces = fullMap && state.go == null,
                    onTravelMode = onTravelMode,
                    goActive = goActive,
                    onExitGo = onExitGo,
                )
            },
            // La striscia compare solo a mappa piena: mentre la tendina scende è la
            // prima scheda della lista a trasformarsi fino a diventare identica a lei
            // (vedi StationCard). In Go niente striscia: resta la scheda rimpicciolita.
            stripAtEndOnly = true,
            strip = if (goActive) null else { {
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
            } },
        ) {
            val stripCard = state.primaryCards.firstOrNull() ?: state.otherCards.firstOrNull()
            SheetContent(
                state, listState, onOpenDay, onModeChange, onToggleFavorite, onPinStop,
                collapse = { sheet.collapseProgress },
                stripDirection = stripCard?.let {
                    if (stripChoiceStation == it.stationId) stripChoiceDirection else defaultStripDirection(it.stationId)
                },
            )
            // In Go a mappa piena: un tocco (anche sulla scheda) riporta la tendina
            // a metà, come succede con la striscia normale.
            if (goActive && fullMap) {
                Box(Modifier.fillMaxSize().clickable { sheet.animateTo(SheetStop.HALF) })
            }
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
    onRouteVisible: (Boolean) -> Unit,
    onLineFraming: (Boolean) -> Unit,
    showPlaces: Boolean,
    onTravelMode: (TravelMode) -> Unit,
    goActive: Boolean,
    onExitGo: () -> Unit,
) {
    // Velocità: creare la mappa Google costa centinaia di ms. Per non
    // ritardare gli orari, al primo fotogramma c'è solo lo sfondo;
    // la mappa viene creata subito DOPO che gli orari sono a schermo.
    var showMap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }  // aspetta che il primo fotogramma sia disegnato
        showMap = true
        io.github.edoppiap.orarilido.logStartup("Creazione mappa iniziata")
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
                goStations = if (goActive) state.go?.focusStations.orEmpty() else emptyList(),
                goPinnedId = state.go?.stripStop?.takeIf { it.pinned }?.stationId,
                route = state.route,
                onRouteVisible = onRouteVisible,
                onLineFraming = onLineFraming,
                showPlaces = showPlaces,
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
            // Il selettore serve quando ci sono i cerchi o il percorso suggerito.
            // In Go al suo posto c'è la X per uscire (i cerchi in treno non servono).
            Box(contentAlignment = Alignment.CenterEnd) {
                androidx.compose.animation.AnimatedVisibility(visible = travelToggleVisible && !goActive, enter = fadeIn(), exit = fadeOut()) {
                    TravelToggle(state.travelMode, onTravelMode)
                }
                androidx.compose.animation.AnimatedVisibility(visible = goActive, enter = fadeIn(), exit = fadeOut()) {
                    MapButton(onClick = onExitGo) { CloseIcon(MaterialTheme.colorScheme.onSurface) }
                }
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
    // Modalità Go
    data object GoRecognizing : SheetItem { override val key = "go_recognizing" }
    data class GoHeader(val directionTitle: String, val showHint: Boolean, val showNextLabel: Boolean) : SheetItem {
        override val key = "go_header"
    }
    data object GoAheadHeader : SheetItem { override val key = "go_ahead_header" }
    /** [morph] = è la prima scheda: a mappa piena resta, rimpicciolita (le altre svaniscono). */
    data class GoStop(val stop: GoStopUi, val next: Boolean, val morph: Boolean = false) : SheetItem {
        override val key = "go_" + stop.stationId
    }
}

/** La lista degli elementi della tendina: serve sia per disegnarla sia per trovare una scheda. */
private fun sheetItems(state: MainUiState): List<SheetItem> = buildList {
    val go = state.go
    // In treno: solo le fermate davanti, al posto di tutte le schede.
    if (go != null && !go.recognizing) {
        val pinned = go.stops.firstOrNull { it.pinned }
        val next = go.stops.firstOrNull()
        val first = pinned ?: next
        add(SheetItem.GoHeader(go.directionTitle, showHint = pinned == null, showNextLabel = first == next && pinned == null))
        // La fermata fissata va "in primo piano", in cima; le altre sotto, in ordine.
        // Stessa chiave della scheda: così sale in cima scivolando invece di sparire e ricomparire.
        if (pinned != null) {
            add(SheetItem.GoStop(pinned, next = pinned == next, morph = true))
            add(SheetItem.GoAheadHeader)
        }
        go.stops.filter { it != pinned }.forEach {
            add(SheetItem.GoStop(it, next = it == next, morph = pinned == null && it == next))
        }
        return@buildList
    }
    // Sembra un treno: una riga in cima, le schede normali restano sotto.
    if (go != null) add(SheetItem.GoRecognizing)
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
    onPinStop: (String) -> Unit,
    /** Quanto è scesa la tendina verso la mappa piena (0–1): la prima scheda si trasforma. */
    collapse: () -> Float = { 0f },
    /** Direzione mostrata nella striscia: la riga che resta nella scheda che si trasforma. */
    stripDirection: Direction? = null,
) {
    // Quando cambia la modalità (o si entra/esce da Go), torna in cima alla lista.
    val goActive = state.go?.recognizing == false
    LaunchedEffect(state.mode, goActive) { listState.scrollToItem(0) }
    // Fissando una fermata in Go, la lista torna in cima: lì c'è la scheda appena fissata.
    val pinnedId = state.go?.stops?.firstOrNull { it.pinned }?.stationId
    LaunchedEffect(pinnedId) { if (pinnedId != null) listState.animateScrollToItem(0) }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(),  // a tendina alzata occupa tutto lo schermo
        state = listState,
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            // In fondo, spazio per la barra di navigazione di Android.
            bottom = 14.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        // Niente spazio automatico tra gli elementi: ognuno ha il suo sotto di sé
        // (vedi il Box qui sotto), così chi si chiude porta via anche il proprio spazio.
    ) {
        val items = sheetItems(state)
        // La scheda che a mappa piena diventa la striscia: in Go quella segnata,
        // altrimenti la prima scheda di stazione (in primo piano, o la prima delle altre).
        val morphIndex = items.indexOfFirst { it is SheetItem.Card || (it is SheetItem.GoStop && it.morph) }
        itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
            val isGo = item is SheetItem.GoHeader || item is SheetItem.GoAheadHeader || item is SheetItem.GoStop
            val role = when {
                index == morphIndex || item is SheetItem.GoHeader -> Modifier       // resta (e si trasforma)
                index < morphIndex -> Modifier.collapseHeight(collapse)            // sopra: si chiude
                else -> Modifier.fadeOnCollapse(collapse)                          // sotto: svanisce
            }
            // In Go le schede scivolano al nuovo posto quando ne fissi una.
            Box((if (isGo) Modifier.animateItem() else Modifier).then(role).padding(bottom = 10.dp)) {
            when (item) {
                SheetItem.PrimaryHeader -> PrimaryHeader(state.mode, onModeChange)
                SheetItem.FavoritesHint -> FavoritesHint()
                SheetItem.OthersHeader -> OthersHeader(state.validFrom)
                is SheetItem.Card -> StationCard(
                    card = item.card,
                    onOpenDay = { direction -> onOpenDay(item.card.stationId, direction) },
                    onToggleFavorite = { onToggleFavorite(item.card.stationId) },
                    morph = if (index == morphIndex) collapse else null,
                    keptDirection = stripDirection,
                )
                SheetItem.GoRecognizing -> GoRecognizingRow()
                is SheetItem.GoHeader -> GoHeader(item.directionTitle, item.showHint, item.showNextLabel, collapse)
                SheetItem.GoAheadHeader -> GoAheadHeader()
                is SheetItem.GoStop -> GoStopCard(
                    item.stop,
                    item.next,
                    onClick = { onPinStop(item.stop.stationId) },
                    collapse = if (item.morph) collapse else null,
                )
            }
            }
        }
    }
}

// ---- Modalità Go ----

/** "⟳ Sei in treno? Sto riconoscendo la corsa…": compare per 15–30 s prima di Go. */
@Composable
private fun GoRecognizingRow() {
    SheetCard {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Sei in treno? Sto riconoscendo la corsa…",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * In treno  [Verso Ostia]
 * Arrivi stimati da dove sei, coi tempi dell'orario programmato.
 */
@Composable
private fun GoHeader(directionTitle: String, showHint: Boolean, showNextLabel: Boolean, collapse: () -> Float) {
    Column(Modifier.padding(start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "In treno",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(10.dp))
            DirectionPill(directionTitle)
            Spacer(Modifier.weight(1f))
            // A mappa piena "Prossima fermata" sta qui (nella scheda non c'è più posto).
            if (showNextLabel) {
                Text(
                    "Prossima fermata",
                    modifier = Modifier.padding(end = 4.dp).graphicsLayer { alpha = collapse() },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Arrivi stimati da dove sei, coi tempi dell'orario programmato." +
                if (showHint) "\nTocca la tua fermata per tenerla in vista." else "",
            modifier = Modifier.collapseHeight(collapse).padding(top = 6.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scendendo verso la mappa piena svanisce (a metà discesa è già sparito). */
private fun Modifier.fadeOnCollapse(collapse: () -> Float) =
    graphicsLayer { alpha = (1f - collapse() * 2f).coerceIn(0f, 1f) }

/** Scendendo verso la mappa piena si chiude in altezza e svanisce. */
private fun Modifier.collapseHeight(collapse: () -> Float) = this
    .graphicsLayer { alpha = (1f - collapse() * 2f).coerceIn(0f, 1f) }
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val h = (placeable.height * (1f - collapse())).toInt()
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * Scheda di una fermata davanti:
 *   Lido Centro                 12 min
 *   Prossima fermata             08:29
 * (bordo azzurro = fermata fissata)
 * Tocco = la fissa nella striscia a mappa piena (di nuovo = la sblocca).
 */
@Composable
private fun GoStopCard(
    stop: GoStopUi,
    next: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Solo per la prima scheda: quanto rimpicciolirla (0 = normale, 1 = striscia a mappa piena). */
    collapse: (() -> Float)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Letto qui: a ogni fotogramma della discesa si ridisegna solo questa scheda.
    val p = collapse?.invoke() ?: 0f
    val shape = RoundedCornerShape(lerp(20.dp, 16.dp, p))
    // Bordo blu sulla fermata fissata (compare in dissolvenza).
    val border by androidx.compose.animation.animateColorAsState(
        if (stop.pinned) colors.primary else colors.surface, label = "pinBorder",
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = lerp(16.dp, 14.dp, p), vertical = lerp(12.dp, 6.dp, p)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stop.name,
                fontSize = lerp(20.sp, 18.sp, p),
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // "La tua fermata" non serve scriverlo: lo dice il bordo azzurro.
            if (next) {
                Text(
                    "Prossima fermata",
                    // a mappa piena si chiude: la scritta passa nell'intestazione
                    modifier = if (collapse != null) Modifier.collapseHeight(collapse) else Modifier,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        WaitAndClock(
            wait = {
                Text(
                    waitText(stop.row),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (stop.row.imminent) colors.tertiary else colors.onSurface,
                    maxLines = 1,
                )
            },
            dot = { Text(" · ", fontSize = lerp(13.sp, 14.sp, p), color = colors.onSurfaceVariant, maxLines = 1) },
            clock = { Text(stop.row.clock, fontSize = lerp(13.sp, 14.sp, p), color = colors.onSurfaceVariant, maxLines = 1) },
            inline = p,
        )
    }
}

/** Divisorio "Fermate davanti", sotto la fermata fissata. */
@Composable
private fun GoAheadHeader(modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Fermate davanti",
            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
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

/**
 * Scheda di una stazione: nome e stella, poi una riga per direzione.
 *
 * Con [morph] (solo la scheda che a mappa piena diventa la striscia) la scheda
 * si trasforma mentre la tendina scende: 0 = scheda normale, 1 = IDENTICA alla
 * striscia ([CollapsedStrip]), che a quel punto le si sostituisce senza che si
 * veda. Gli stili delle due restano quelli: qui si passa dall'uno all'altro.
 *   - sfondo della scheda che svanisce, margini che diventano quelli della striscia;
 *   - nome e orari che si rimpiccioliscono, stella che si chiude;
 *   - la riga dell'altra direzione che si chiude ([keptDirection] = quella della striscia);
 *   - la pillola "Verso Roma" che sfuma nel selettore [Ostia | Roma].
 */
@Composable
private fun StationCard(
    card: StationCardUi,
    onOpenDay: (Direction) -> Unit,
    onToggleFavorite: () -> Unit,
    morph: (() -> Float)? = null,
    keptDirection: Direction? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Letto qui: a ogni fotogramma della discesa si ridisegna solo questa scheda.
    val p = morph?.invoke() ?: 0f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface.copy(alpha = colors.surface.alpha * (1f - p)))
            .padding(
                start = lerp(16.dp, 8.dp, p),   // + 12 dp della lista = 20 dp della striscia
                end = lerp(8.dp, 0.dp, p),      // + 12 dp della lista = 12 dp della striscia
                top = lerp(8.dp, 2.dp, p),
                bottom = lerp(14.dp, 4.dp, p),
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.name,
                modifier = Modifier.weight(1f),
                fontSize = lerp(20.sp, 17.sp, p),
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.lerp(colors.onSurface, colors.onBackground, p),
                // La striscia ha una riga sola; la scheda ferma va a capo come sempre.
                maxLines = if (p > 0f) 1 else Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Distanza dall'utente in linea d'aria (solo se la posizione è nota).
            card.distance?.let {
                Text(
                    it,
                    fontSize = lerp(14.sp, 13.sp, p),
                    fontWeight = androidx.compose.ui.text.font.lerp(FontWeight.SemiBold, FontWeight.Normal, p),
                    color = colors.onSurfaceVariant,
                )
            }
            // Stella: piena e blu se preferita, vuota e grigia altrimenti. Nella striscia non c'è: si chiude.
            Text(
                if (card.favorite) "★" else "☆",
                modifier = Modifier
                    .collapseBoth(p)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 24.sp,
                color = if (card.favorite) colors.primary else colors.onSurfaceVariant,
            )
        }
        // Spazio tra nome e orari: nella striscia c'è, nella scheda lo fa la stella.
        Spacer(Modifier.height(lerp(0.dp, 6.dp, p)))
        Column(Modifier.padding(end = lerp(8.dp, 0.dp, p))) {
            card.blocks.forEachIndexed { i, block ->
                // La riga che resta nella striscia; ai capolinea c'è solo quella.
                val kept = card.blocks.size == 1 || block.direction == keptDirection
                if (i > 0) {
                    HorizontalDivider(
                        Modifier.collapseHeight { p }.padding(vertical = 8.dp),
                        color = colors.outlineVariant,
                    )
                }
                CompactDirectionRow(
                    block,
                    onClick = { onOpenDay(block.direction) },
                    modifier = if (kept) Modifier else Modifier.collapseHeight { p },
                    morph = if (kept) p else 0f,
                    stripToggle = card.blocks.size > 1,
                )
            }
        }
    }
}

/** [Verso Roma]   3 min    18 min    33 min
 *                 08:03    08:18     08:33
 * Con [morph] > 0 diventa la riga della striscia: la pillola sfuma nel selettore
 * [Ostia | Roma] (o, ai capolinea, nella pillola corta), caratteri un po' più piccoli. */
@Composable
private fun CompactDirectionRow(
    block: DirectionBlock,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    morph: Float = 0f,
    stripToggle: Boolean = false,
) {
    val p = morph
    val colors = MaterialTheme.colorScheme
    // Tutta la riga è toccabile: apre gli orari della giornata per questa direzione.
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = lerp(2.dp, 0.dp, p)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillToToggle(
            p = p,
            pill = { DirectionPill(block.title, Modifier.width(112.dp)) },
            target = {
                // Lo stesso aspetto della striscia (lì è vero e toccabile, qui solo disegnato).
                if (stripToggle) {
                    SegmentedToggle(
                        options = listOf(Direction.TO_OSTIA to "Ostia", Direction.TO_ROMA to "Roma"),
                        selected = block.direction,
                        onSelect = {},
                        segmentPadding = 10.dp,
                    )
                } else {
                    DirectionPill(block.title.removePrefix("Verso "))
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        block.rows.forEachIndexed { i, row ->
            Column(Modifier.weight(if (i == 0) 1.3f else 1f)) {
                Text(
                    waitText(row),
                    fontSize = if (i == 0) lerp(22.sp, 20.sp, p) else lerp(16.sp, 15.sp, p),
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (row.imminent) colors.tertiary
                        else androidx.compose.ui.graphics.lerp(colors.onSurface, colors.onBackground, p),
                    maxLines = 1,
                )
                Text(
                    clockText(row),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * La pillola della scheda che diventa il selettore della striscia: occupano lo
 * stesso posto, la larghezza passa dall'una all'altro e intanto sfumano.
 * Ferma (p = 0 o 1) c'è solo una delle due: così il selettore disegnato non
 * ruba i tocchi alla riga.
 */
@Composable
private fun PillToToggle(p: Float, pill: @Composable () -> Unit, target: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = {
            if (p < 1f) Box(Modifier.graphicsLayer { alpha = 1f - p }) { pill() }
            if (p > 0f) Box(Modifier.graphicsLayer { alpha = p }) { target() }
        },
    ) { measurables, constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(free) }
        val a = if (p < 1f) placeables.first() else null
        val b = if (p > 0f) placeables.last() else null
        fun mix(x: Int?, y: Int?) = when {
            x == null -> y!!
            y == null -> x
            else -> (x + (y - x) * p).toInt()
        }
        val width = mix(a?.width, b?.width)
        val height = mix(a?.height, b?.height)
        layout(width, height) {
            a?.place(0, (height - a.height) / 2)
            b?.place(0, (height - b.height) / 2)
        }
    }
}

/** Si chiude in larghezza e in altezza (e svanisce): la stella che nella striscia non c'è. */
private fun Modifier.collapseBoth(p: Float) = this
    .graphicsLayer { alpha = (1f - p * 2f).coerceIn(0f, 1f) }
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout((placeable.width * (1f - p)).toInt(), (placeable.height * (1f - p)).toInt()) { placeable.place(0, 0) }
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

/**
 * Minuti e orario d'arrivo di una scheda Go, che cambiano disposizione con [inline]:
 *   0 (tendina a metà)        1 (mappa piena)
 *        12 min               12 min · 08:29
 *         08:29
 * Per i valori in mezzo (tendina che scende) l'orario scivola da sotto a
 * fianco e il "·" compare in dissolvenza: niente salti.
 */
@Composable
private fun WaitAndClock(
    wait: @Composable () -> Unit,
    dot: @Composable () -> Unit,
    clock: @Composable () -> Unit,
    inline: Float,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(wait, { Box(Modifier.graphicsLayer { alpha = inline }) { dot() } }, clock),
    ) { (waitM, dotM, clockM), constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val w = waitM.first().measure(free)
        val d = dotM.first().measure(free)
        val c = clockM.first().measure(free)
        fun lerpInt(a: Int, b: Int) = (a + (b - a) * inline).toInt()
        val first = androidx.compose.ui.layout.FirstBaseline

        // Impilati: tutto allineato a destra, orario sotto i minuti.
        val stackedWidth = maxOf(w.width, c.width)
        val stackedHeight = w.height + c.height
        // In linea: minuti, "·", orario, sulla stessa linea di base del testo.
        val inlineWidth = w.width + d.width + c.width
        val inlineHeight = w.height
        val width = lerpInt(stackedWidth, inlineWidth)
        val height = lerpInt(stackedHeight, inlineHeight)

        layout(width, height) {
            // I minuti restano attaccati al bordo destro meno quello che serve all'orario in linea.
            val waitX = lerpInt(width - w.width, 0)
            w.place(waitX, 0)
            val dotX = waitX + w.width
            d.place(dotX, w[first] - d[first])
            val clockX = lerpInt(width - c.width, dotX + d.width)
            val clockY = lerpInt(w.height, w[first] - c[first])
            c.place(clockX, clockY)
        }
    }
}
