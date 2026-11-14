package io.github.edoppiap.orarilido.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Il cartello del bus sostitutivo: quadrato smussato blu (il colore della linea,
 * perché il bus "è" la linea in un'altra forma) con un bus visto di fronte.
 * È lo stesso sulla mappa, nelle schede e nella giornata intera (scelto
 * dall'utente il 14/11 nell'anteprima, tra giallo, blu e bianco).
 * Disegnato a mano in un quadrato di 26 × 26 unità, come le icone dei punti di interesse.
 */

/** Disegna il cartello su [canvas], grande [sizePx] pixel. */
private fun drawBusBadge(canvas: Canvas, sizePx: Float, bg: Int, fg: Int, border: Int) {
    canvas.save()
    canvas.scale(sizePx / 26f, sizePx / 26f)  // da qui in poi si disegna in unità da 0 a 26
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), r, r, paint)
    }
    // Cartello: bordino del colore della scheda/mappa, poi il fondo blu.
    rect(0f, 0f, 26f, 26f, 7.2f, border)
    rect(2.5f, 2.5f, 21f, 21f, 4.8f, bg)
    // Il bus, visto di fronte.
    canvas.translate(1f, 1f)
    rect(5.5f, 4f, 13f, 14f, 2.6f, fg)           // carrozzeria
    rect(7.2f, 7f, 9.6f, 5.4f, 1f, bg)           // parabrezza
    rect(9.5f, 5f, 5f, 1f, 0.5f, bg)             // indicatore della linea
    paint.color = bg
    canvas.drawCircle(8.6f, 15f, 1.05f, paint)   // fari
    canvas.drawCircle(15.4f, 15f, 1.05f, paint)
    rect(6.7f, 17.4f, 2.6f, 2.6f, 0.8f, fg)      // ruote
    rect(14.7f, 17.4f, 2.6f, 2.6f, 0.8f, fg)
    canvas.restore()
}

/** Il cartello come immagine, per i marker della mappa (disegnato una volta sola). */
fun busBadgeBitmap(sizeDp: Float, density: Float, bg: Int, fg: Int, border: Int): Bitmap {
    val size = (sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawBusBadge(Canvas(bmp), size.toFloat(), bg, fg, border)
    return bmp
}

/** Il cartello nelle schede e nella giornata, coi colori del tema. */
@Composable
fun BusBadge(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val bg = colors.primary.toArgb()
    val fg = colors.onPrimary.toArgb()
    // Fuori dalla mappa il bordino non serve (la scheda o la striscia hanno già un fondo pulito).
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        drawIntoCanvas { drawBusBadge(it.nativeCanvas, this.size.minDimension, bg, fg, android.graphics.Color.TRANSPARENT) }
    }
}
