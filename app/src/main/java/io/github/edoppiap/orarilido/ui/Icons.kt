package io.github.edoppiap.orarilido.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

// Icone semplici disegnate a mano: evitano di aggiungere una libreria di icone.

/** Icona "<" o ">" disegnata a mano (due tratti arrotondati): indietro e cambio giorno. */
@Composable
fun Chevron(color: Color, pointsLeft: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.5.dp.toPx()
        val path = Path().apply {
            val tip = if (pointsLeft) 0.30f else 0.70f    // la punta
            val ends = if (pointsLeft) 0.65f else 0.35f   // le estremità
            moveTo(size.width * ends, size.height * 0.15f)
            lineTo(size.width * tip, size.height * 0.50f)
            lineTo(size.width * ends, size.height * 0.85f)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Icona "☰" del menu: tre tratti orizzontali arrotondati. */
@Composable
fun MenuIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.5.dp.toPx()
        listOf(0.2f, 0.5f, 0.8f).forEach { y ->
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * y),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Icona "✕" (chiudi): due tratti arrotondati incrociati. Esce dalla modalità Go. */
@Composable
fun CloseIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.5.dp.toPx()
        val a = size.width * 0.15f
        val b = size.width * 0.85f
        drawLine(color, androidx.compose.ui.geometry.Offset(a, a), androidx.compose.ui.geometry.Offset(b, b), stroke, StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(b, a), androidx.compose.ui.geometry.Offset(a, b), stroke, StrokeCap.Round)
    }
}

// ---- Icone delle velocità (Impostazioni → A piedi e in bici) ----
// Disegnate a mano in un riquadro 24 × 24 con la sintassi dei tracciati SVG, come
// nell'anteprima approvata dall'utente (artifact claude.ai/artifact/CyfMSCgqV8Dc5KWPcRrkWY).
// Lumaca e lepre guardano a destra, verso dove vai.

/** Lumaca: velocità più lenta del normale. */
@Composable
fun SnailIcon(color: Color, size: Dp = 20.dp) = SvgIcon(size) {
    drawPath(svgPath("M2 19.5h17.5a2 2 0 0 0 2-2V15a1.6 1.6 0 0 0-3.2 0v1.2H2.8A1 1 0 0 0 2 19.5z"), color)
    drawPath(svgPath("M19.2 14.2 18.2 9.6M20.6 14.2l1.4-4.3"), color, style = roundStroke(1.2f))
    drawCircle(color, 1f, Offset(18.1f, 9.4f))
    drawCircle(color, 1f, Offset(22f, 9.7f))
    drawCircle(color, 6.6f, Offset(10f, 11f))
    // La spirale del guscio è un "buco": si vede lo sfondo che c'è dietro.
    drawPath(
        svgPath("M10 11a1.4 1.4 0 1 1 1.4 1.4 2.8 2.8 0 1 1-2.8-2.8 4.3 4.3 0 1 1 4.3 4.3"),
        Color.Black, style = roundStroke(1.3f), blendMode = BlendMode.Clear,
    )
}

/** Lepre che corre: velocità più alta del normale. */
@Composable
fun HareIcon(color: Color, size: Dp = 20.dp) = SvgIcon(size) {
    rotatedOval(color, 10.5f, 14.2f, 7.2f, 4.4f, -8f)    // corpo
    drawCircle(color, 3.2f, Offset(18.2f, 10.4f))         // testa
    rotatedOval(color, 16.2f, 4.6f, 1.35f, 4.2f, -28f)   // orecchie
    rotatedOval(color, 18.6f, 4.4f, 1.25f, 4f, -8f)
    drawCircle(color, 1.9f, Offset(3.2f, 12.4f))          // coda
    drawPath(svgPath("M5.5 17.5 2 20.6M15 17.6l4.2 2.6M12.5 18l1.2 3"), color, style = roundStroke(2.1f))  // zampe
}

/** Omino che cammina: velocità normale a piedi. */
@Composable
fun WalkerIcon(color: Color, size: Dp = 20.dp) = SvgIcon(size) {
    drawCircle(color, 2.3f, Offset(13.5f, 3.8f))
    drawPath(
        svgPath("M12.6 8.2 10.8 14.4M12.4 9 8.6 11.6 7.4 14.4M12.6 9.2l2.6 3.2 2.8 0.9M10.8 14.4 8.4 21M10.8 14.4l3.6 2.8 1 3.8"),
        color, style = roundStroke(2.4f),
    )
}

/** Bicicletta: velocità normale in bici. */
@Composable
fun BikeIcon(color: Color, size: Dp = 20.dp) = SvgIcon(size) {
    drawCircle(color, 4f, Offset(5.2f, 16f), style = Stroke(2f))
    drawCircle(color, 4f, Offset(18.8f, 16f), style = Stroke(2f))
    drawPath(
        svgPath("M5.2 16 9.2 9h7.2l2.4 7M9.2 9l3.4 7H5.2M12.6 16l3.8-7M15.8 9l-0.9 -3h2.6M7.4 6.6h3.6M9.2 6.6V9"),
        color, style = roundStroke(1.9f),
    )
}

/**
 * Tela per un'icona da 24 × 24 unità, ingrandita a [size]. Disegnata su un
 * livello a parte ("Offscreen"): serve perché BlendMode.Clear (la spirale della
 * lumaca) buchi solo l'icona e non quello che c'è sotto.
 */
@Composable
private fun SvgIcon(size: Dp, draw: DrawScope.() -> Unit) {
    Canvas(
        Modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        scale(this.size.width / 24f, pivot = Offset.Zero) { draw() }
    }
}

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

private fun roundStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Ellisse di centro (cx, cy) e semiassi rx, ry, ruotata di [degrees]. */
private fun DrawScope.rotatedOval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, degrees: Float) {
    rotate(degrees, pivot = Offset(cx, cy)) {
        drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2))
    }
}
