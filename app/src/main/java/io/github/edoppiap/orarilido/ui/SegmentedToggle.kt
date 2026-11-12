package io.github.edoppiap.orarilido.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selettore a segmenti con una "pillola" blu che SCIVOLA sotto l'opzione scelta.
 * Usato per Vicina | Preferite, 🚶 | 🚲, Verso Roma | Verso Ostia.
 *
 * Come funziona: ogni segmento comunica la propria posizione e larghezza
 * (onGloballyPositioned); la pillola, disegnata dietro, anima offset e
 * larghezza verso quelli del segmento selezionato.
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
    val density = LocalDensity.current
    // Posizione (x) e larghezza di ogni segmento, in dp; altezza comune dei segmenti.
    val positions = remember { mutableStateMapOf<T, Pair<Dp, Dp>>() }
    var segmentHeight by remember { mutableStateOf(0.dp) }
    val target = positions[selected]
    val spec = tween<Dp>(durationMillis = 280, easing = FastOutSlowInEasing)
    val indicatorX by animateDpAsState(target?.first ?: 0.dp, spec, label = "toggleX")
    val indicatorW by animateDpAsState(target?.second ?: 0.dp, spec, label = "toggleW")

    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(3.dp),
    ) {
        // La pillola: dietro ai testi.
        if (target != null) {
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(indicatorW)
                    .height(segmentHeight)
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary)
            )
        }
        Row {
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
                        .onGloballyPositioned { c ->
                            with(density) {
                                positions[value] = c.positionInParent().x.toDp() to c.size.width.toDp()
                                segmentHeight = c.size.height.toDp()
                            }
                        }
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
        }
    }
}
