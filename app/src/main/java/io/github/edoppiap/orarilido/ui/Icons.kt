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
