package io.github.edoppiap.orarilido.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selettore a segmenti con una "pillola" blu che SCIVOLA sotto l'opzione scelta.
 * Usato per Vicina | Preferite, 🚶 | 🚲, Ostia | Roma.
 *
 * Come funziona: un layout fatto a mano misura i segmenti e, nello STESSO
 * passaggio, mette la pillola dietro a quello scelto. La pillola si muove
 * seguendo un "indice animato" (es. da 0 a 1 = da Ostia a Roma): quando il
 * selettore compare è già sull'opzione giusta, e scivola solo quando la cambi.
 * (Prima la pillola partiva da sinistra a ogni comparsa: si vedeva quando la
 * striscia a mappa piena sostituisce la scheda che si trasforma.)
 */
@Composable
fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    fontSize: TextUnit = 13.sp,
    segmentPadding: Dp = 12.dp,
) {
    val colors = MaterialTheme.colorScheme
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    // Parte GIÀ dall'opzione scelta: alla comparsa niente scivolata.
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        position.animateTo(selectedIndex.toFloat(), tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }

    Layout(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(3.dp),
        content = {
            // La pillola: dietro ai testi (è il primo elemento, quindi disegnato per primo).
            Box(Modifier.clip(RoundedCornerShape(50)).background(colors.primary))
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val textColor by animateColorAsState(
                    if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                    tween(280),
                    label = "toggleText",
                )
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,  // niente "onda" grigia: l'effetto è la pillola che scivola
                        ) { onSelect(value) }
                        .padding(horizontal = segmentPadding, vertical = 6.dp),
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
            }
        },
    ) { measurables, constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val segments = measurables.drop(1).map { it.measure(free) }
        // Dove inizia ogni segmento (in px), uno dopo l'altro.
        val starts = segments.runningFold(0) { x, seg -> x + seg.width }
        val height = segments.maxOfOrNull { it.height } ?: 0

        // Pillola tra due segmenti vicini, secondo l'indice animato (es. 0,4 = 40% verso il secondo).
        val pos = position.value.coerceIn(0f, (segments.size - 1).coerceAtLeast(0).toFloat())
        val i = pos.toInt().coerceAtMost((segments.size - 2).coerceAtLeast(0))
        val f = if (segments.size > 1) pos - i else 0f
        fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt()
        val pillX = if (segments.size > 1) mix(starts[i], starts[i + 1]) else 0
        val pillW = if (segments.size > 1) mix(segments[i].width, segments[i + 1].width) else segments.firstOrNull()?.width ?: 0
        val pill = measurables.first().measure(Constraints.fixed(pillW.coerceAtLeast(0), height))

        layout(starts.last(), height) {
            pill.place(pillX, 0)
            segments.forEachIndexed { k, seg -> seg.place(starts[k], (height - seg.height) / 2) }
        }
    }
}
