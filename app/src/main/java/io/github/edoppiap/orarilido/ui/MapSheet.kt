package io.github.edoppiap.orarilido.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Le tre altezze a cui la tendina si ferma. */
enum class SheetStop {
    /** Tutto schermo: solo orari. */
    EXPANDED,
    /** Apertura: circa metà schermo di orari, metà di mappa. */
    HALF,
    /** "Mappa piena": della tendina resta solo una striscia col riassunto. */
    COLLAPSED,
}

/**
 * Stato della tendina: dove si trova (in pixel) e verso quale fermata va.
 * La teniamo noi invece di usare quella di Material perché quella ha solo
 * due altezze.
 */
@Stable
class MapSheetState internal constructor(private val scope: CoroutineScope) {
    /** Distanza in px del bordo alto della tendina dal bordo alto dello schermo. */
    var offset by mutableFloatStateOf(Float.NaN)
        private set

    /** Fermata dove la tendina sta (o verso cui sta andando). */
    var stop by mutableStateOf(SheetStop.HALF)
        private set

    /** Posizione (px dall'alto) di ciascuna fermata: dipende dall'altezza dello schermo. */
    internal var tops: Map<SheetStop, Float> = emptyMap()
        private set

    private var job: Job? = null

    internal fun setTops(new: Map<SheetStop, Float>) {
        if (new == tops) return
        tops = new
        job?.cancel()
        offset = new.getValue(stop)  // schermo cambiato (es. rotazione): resta alla stessa fermata
    }

    private val minTop get() = tops.getValue(SheetStop.EXPANDED)
    private val maxTop get() = tops.getValue(SheetStop.COLLAPSED)

    /** true se la tendina è ferma esattamente su una fermata. */
    internal val atStop: Boolean get() = tops.values.any { abs(it - offset) < 0.5f }

    /** Sposta la tendina di [delta] px (positivo = giù). Restituisce di quanto si è mossa davvero. */
    internal fun moveBy(delta: Float): Float {
        job?.cancel()
        val old = offset
        offset = (old + delta).coerceIn(minTop, maxTop)
        return offset - old
    }

    /**
     * Dito sollevato: con uno slancio deciso va alla fermata successiva
     * in quella direzione, altrimenti alla più vicina.
     */
    internal fun settle(velocity: Float, threshold: Float) {
        val sorted = tops.entries.sortedBy { it.value }
        val target = when {
            velocity > threshold -> sorted.firstOrNull { it.value > offset + 1f }
            velocity < -threshold -> sorted.lastOrNull { it.value < offset - 1f }
            else -> null
        } ?: sorted.minBy { abs(it.value - offset) }
        animateTo(target.key, velocity)
    }

    /** Porta la tendina a [target] con un'animazione "a molla". */
    fun animateTo(target: SheetStop, velocity: Float = 0f) {
        stop = target
        val to = tops[target] ?: return
        job?.cancel()
        job = scope.launch {
            animate(offset, to, velocity, spring(dampingRatio = 1f, stiffness = 500f)) { value, _ ->
                offset = value.coerceIn(minTop, maxTop)
            }
        }
    }
}

@Composable
fun rememberMapSheetState(): MapSheetState {
    val scope = rememberCoroutineScope()
    return remember { MapSheetState(scope) }
}

/**
 * Mappa sullo sfondo e tendina sopra, con tre fermate (vedi [SheetStop]).
 *
 * Gesti:
 *  - trascinando la maniglia o la striscia, la tendina segue il dito;
 *  - trascinando la LISTA verso l'alto, prima la tendina sale fino in cima,
 *    poi scorre la lista;
 *  - trascinando la lista verso il basso, scorre la lista; la tendina scende
 *    solo se il dito si è appoggiato con la lista GIÀ in cima (così chi
 *    scorre le stazioni non se la ritrova abbassata per sbaglio).
 *
 * [listAtTop] dice se la lista è in cima; [peekHeight] è la parte visibile
 * a mappa piena (sopra la barra di navigazione); [halfFraction] è la parte
 * di schermo occupata all'apertura.
 */
@Composable
fun MapSheetLayout(
    state: MapSheetState,
    listAtTop: () -> Boolean,
    peekHeight: Dp,
    halfFraction: Float,
    background: @Composable () -> Unit,
    strip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = constraints.maxHeight.toFloat()
        val navBar = WindowInsets.navigationBars.getBottom(density).toFloat()
        val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
        val peek = with(density) { peekHeight.toPx() } + navBar
        state.setTops(
            mapOf(
                SheetStop.EXPANDED to 0f,
                SheetStop.HALF to screen * (1f - halfFraction),
                SheetStop.COLLAPSED to screen - peek,
            )
        )

        background()

        // Slancio minimo (px/s) per "saltare" alla fermata successiva.
        val threshold = with(density) { 300.dp.toPx() }
        // Il dito si è appoggiato con la lista già in cima? (non serve ridisegnare: basta una scatola)
        val startedAtTop = remember { booleanArrayOf(true) }
        val connection = remember(state) {
            object : NestedScrollConnection {
                // Prima che scorra la lista: se il dito sale, sale prima la tendina.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0f) {
                        Offset(0f, state.moveBy(available.y))
                    } else Offset.Zero

                // Dopo che la lista ha scorso: l'avanzo verso il basso abbassa la
                // tendina solo se il gesto è partito con la lista in cima; se no lo buttiamo.
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    when {
                        source != NestedScrollSource.UserInput || available.y <= 0f -> Offset.Zero
                        startedAtTop[0] -> Offset(0f, state.moveBy(available.y))
                        else -> Offset(0f, available.y)
                    }

                // Dito sollevato a metà strada: la tendina va a una fermata.
                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (!state.atStop) {
                        state.settle(available.y, threshold); available
                    } else Velocity.Zero

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = when {
                    !state.atStop -> { state.settle(available.y, threshold); available }
                    !startedAtTop[0] -> available  // lo slancio della lista non tocca la tendina
                    else -> Velocity.Zero
                }
            }
        }

        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val halfTop = state.tops.getValue(SheetStop.HALF)
        val collapsedTop = state.tops.getValue(SheetStop.COLLAPSED)
        // La striscia compare nell'ultima metà della discesa verso "mappa piena".
        val stripAlpha = {
            val progress = (state.offset - halfTop) / (collapsedTop - halfTop)
            ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
        }
        val stripVisible by remember { derivedStateOf { stripAlpha() > 0.01f } }

        Column(
            Modifier
                .fillMaxWidth()
                .height(maxHeight)
                .offset { IntOffset(0, state.offset.roundToInt()) }
                .shadow(8.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Pass "Initial" = lo vediamo prima della lista, senza consumarlo.
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        startedAtTop[0] = listAtTop()
                    }
                }
                .nestedScroll(connection)
                .draggable(
                    state = rememberDraggableState { delta -> state.moveBy(delta) },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> state.settle(velocity, threshold) },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A tendina tutta su, il contenuto scende sotto la barra di stato, gradualmente.
            Spacer(
                Modifier.layout { measurable, constraints ->
                    val h = (statusBar - state.offset).coerceIn(0f, statusBar).roundToInt()
                    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = 0))
                    layout(constraints.maxWidth, h) { p.place(0, 0) }
                }
            )
            DragHandle()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                content()
                if (stripVisible) {
                    // Sopra la lista: la copre TUTTA (anche dietro la barra di
                    // navigazione, dove altrimenti si intravedrebbe) e prende i tocchi al suo posto.
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = stripAlpha() }
                            .background(MaterialTheme.colorScheme.background)
                    ) { strip() }
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
