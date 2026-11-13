package io.github.edoppiap.orarilido.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import io.github.edoppiap.orarilido.places.PlaceKind
import kotlin.math.sqrt

/**
 * Icone dei punti di interesse, disegnate a mano (niente emoji, niente librerie).
 *
 * Tutte seguono lo schema della croce della farmacia:
 *  - forma piena a tinta unita ("principale", a volte con un secondo colore);
 *  - sotto, un bordino chiaro (scuro di notte) largo 1 dp per staccarla dalle strade;
 *  - eventuali dettagli (porta, vetrina, riga del plinto) col colore del bordino;
 *  - nessuno sfondo.
 *
 * I disegni sono in un riquadro di 24 x 24 unità (= 24 dp) e usano la sintassi dei
 * tracciati SVG: sono gli stessi dell'anteprima scelta dall'utente il 13/11
 * (artifact claude.ai/artifact/UqE2DTN4onvDA5tZCPqf6t): fontanella K, bar A,
 * supermercato A, ciclofficina M, bagni B.
 */
fun drawPlaceIcon(kind: PlaceKind, night: Boolean, haloColor: Int, density: Float): Bitmap {
    val size = (24 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(size / 24f, size / 24f) // da qui in poi si disegna in unità da 0 a 24
    val colors = PlaceColors.of(kind, night)
    // Due passate: prima il bordino di tutte le forme, poi le forme vere sopra.
    // Così dove due pezzi si toccano (es. naso e colonna) non c'è un bordino in mezzo.
    for (halo in listOf(true, false)) {
        val pen = IconPen(canvas, halo, colors.main, colors.second, haloColor)
        when (kind) {
            PlaceKind.WATER -> nasone(pen)
            PlaceKind.BAR -> coffeeCup(pen)
            PlaceKind.BIKE_SHOP -> doubleWrench(pen)
            PlaceKind.PHARMACY -> greekCross(pen)
            PlaceKind.SUPERMARKET -> shopFront(pen)
            PlaceKind.TOILET -> manAndWoman(pen)
        }
    }
    return bmp
}

/** Colori di ogni tipo: principale e secondo colore, di giorno e di notte (più chiari). */
private class PlaceColors(val main: Int, val second: Int) {
    companion object {
        fun of(kind: PlaceKind, night: Boolean): PlaceColors = when (kind) {
            // Ghisa del nasone + goccia azzurra.
            PlaceKind.WATER -> if (night) PlaceColors(0xFFA7B4BD.toInt(), 0xFF4FB8EE.toInt())
                               else PlaceColors(0xFF34444D.toInt(), 0xFF1E9BDC.toInt())
            PlaceKind.BAR -> if (night) PlaceColors(0xFFC99A72.toInt(), 0xFFC99A72.toInt())
                             else PlaceColors(0xFF7A4A2A.toInt(), 0xFF7A4A2A.toInt())
            PlaceKind.BIKE_SHOP -> if (night) PlaceColors(0xFFAAB6C1.toInt(), 0xFFAAB6C1.toInt())
                                   else PlaceColors(0xFF4F5B66.toInt(), 0xFF4F5B66.toInt())
            // Verde delle insegne delle farmacie (uguale giorno e notte).
            PlaceKind.PHARMACY -> PlaceColors(0xFF1E9E4A.toInt(), 0xFF1E9E4A.toInt())
            // Arancio senape + strisce della tenda (chiare di giorno, scure di notte).
            PlaceKind.SUPERMARKET -> if (night) PlaceColors(0xFFE9A140.toInt(), 0xFF7A4C10.toInt())
                                     else PlaceColors(0xFFD27F14.toInt(), 0xFFF5CF96.toInt())
            PlaceKind.TOILET -> if (night) PlaceColors(0xFFA58BE6.toInt(), 0xFFA58BE6.toInt())
                                else PlaceColors(0xFF6B4FB0.toInt(), 0xFF6B4FB0.toInt())
        }
    }
}

/**
 * "Penna" che sa disegnare una forma nella passata giusta:
 *  - passata bordino (halo = true): ogni forma diventa più grossa di 1 unità per lato,
 *    col colore del bordino; i dettagli non si disegnano;
 *  - passata principale: forme col loro colore, dettagli col colore del bordino.
 */
private class IconPen(
    private val canvas: Canvas,
    private val halo: Boolean,
    private val main: Int,
    private val second: Int,
    private val haloColor: Int,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
    }

    /** Forma piena (secondColor = usa il secondo colore). */
    fun fill(path: Path, secondColor: Boolean = false) {
        if (halo) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.BUTT
            paint.color = haloColor
        } else {
            paint.style = Paint.Style.FILL
            paint.color = if (secondColor) second else main
        }
        canvas.drawPath(path, paint)
    }

    /** Linea spessa (naso del nasone, manico della tazzina, vapore). */
    fun line(path: Path, width: Float, round: Boolean = false) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        paint.strokeWidth = if (halo) width + 2f else width
        paint.color = if (halo) haloColor else main
        canvas.drawPath(path, paint)
    }

    /** Dettaglio pieno col colore del bordino (vetrina, porta). */
    fun detail(path: Path) {
        if (halo) return
        paint.style = Paint.Style.FILL
        paint.color = haloColor
        canvas.drawPath(path, paint)
    }

    /** Dettaglio a linea sottile col colore del bordino (riga del plinto). */
    fun detailLine(path: Path, width: Float) {
        if (halo) return
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = width
        paint.color = haloColor
        canvas.drawPath(path, paint)
    }
}

// ---------------------------------------------------------------- forme di base

/** Tracciato scritto con la sintassi SVG ("M 1 2 H 3 ..."). */
private fun svg(d: String): Path = PathParser.createPathFromPathData(d)

/** Rettangolo con angoli arrotondati di raggio r. */
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f) = Path().apply {
    addRoundRect(x, y, x + w, y + h, r, r, Path.Direction.CW)
}

private fun circle(cx: Float, cy: Float, r: Float) = Path().apply {
    addCircle(cx, cy, r, Path.Direction.CW)
}

/** Sposta un tracciato (per centrare il disegno nel riquadro). */
private fun Path.moved(dx: Float, dy: Float = 0f) = apply { offset(dx, dy) }

/** Goccia con la punta in (x, top) e la pancia di raggio 1,6. */
private fun drop(x: Float, top: Float): Path {
    val k = 1.6f
    val by = top + 3.1f // centro della pancia
    return svg("M$x $top C$x $top ${x - k} ${by - 1} ${x - k} $by " +
        "A$k $k 0 0 0 ${x + k} $by C${x + k} ${by - 1} $x $top $x $top Z")
}

// ---------------------------------------------------------------- disegni

/**
 * Fontanella: il nasone visto di lato, con le proporzioni del disegno tecnico
 * (alto 110, largo 36,6), un po' allargato per leggersi a 24 dp. Dall'alto:
 * campana, cappello, collarino, colonna, anello, piede svasato, plinto.
 * Il naso è più lungo e spesso del vero (se no a 24 dp sparisce), con la goccia blu.
 */
private fun nasone(pen: IconPen) {
    val dx = -0.7f // centra il disegno (il naso sporge a destra)
    pen.fill(svg("M5.75 3.85 C7.6 3.65 7.9 1.9 9 1.9 C10.1 1.9 10.4 3.65 12.25 3.85 Z").moved(dx)) // campana
    pen.fill(rect(5.1f + dx, 3.7f, 7.8f, 1.15f, 0.55f))                    // cappello
    pen.fill(svg("M5.5 4.8 H12.5 L12.05 5.4 H5.95 Z").moved(dx))            // modanatura sotto il cappello
    pen.fill(rect(5.95f + dx, 5.2f, 6.1f, 12.6f))                           // colonna
    pen.fill(rect(5.5f + dx, 6.5f, 7f, 0.7f, 0.35f))                        // collarino
    pen.fill(rect(5.2f + dx, 14.9f, 7.6f, 1.15f, 0.55f))                    // anello grosso
    pen.fill(svg("M5.95 17.6 C5.95 18.3 5.1 18.4 5.1 19 H12.9 C12.9 18.4 12.05 18.3 12.05 17.6 Z").moved(dx)) // piede
    pen.fill(rect(5.05f + dx, 18.9f, 7.9f, 2.6f, 0.2f))                     // plinto
    pen.detailLine(svg("M5.1 18.95 H12.9").moved(dx), 0.45f)                // riga tra piede e plinto
    pen.fill(rect(11.8f + dx, 9.6f, 0.9f, 2.2f, 0.35f))                     // attacco del naso
    pen.line(svg("M12.5 10.6 H15.2 Q18.4 10.6 18.4 14").moved(dx), 2.2f)    // naso
    pen.fill(drop(18.4f + dx, 15.2f), secondColor = true)                   // goccia
}

/** Bar: tazzina da espresso fumante col piattino, vista di lato. */
private fun coffeeCup(pen: IconPen) {
    val dx = 0.8f
    pen.line(svg("M8.6 8.6 c-1.1-1.2 1.1-2.1 0-3.6 M12.4 8.6 c-1.1-1.2 1.1-2.1 0-3.6").moved(dx), 1.2f, round = true) // vapore
    pen.line(svg("M16 12 H17.2 A2 2 0 0 1 17.2 16 H15.6").moved(dx), 1.6f)  // manico
    pen.fill(svg("M4.8 10.5 H16.2 V12 C16.2 15.9 13.8 18.2 10.5 18.2 C7.2 18.2 4.8 15.9 4.8 12 Z").moved(dx)) // tazzina
    pen.fill(rect(3.2f + dx, 18.9f, 14.6f, 1.7f, 0.85f))                    // piattino
}

/** Supermercato: facciata vista di fronte, tenda a festoni a righe, vetrina e porta. */
private fun shopFront(pen: IconPen) {
    pen.fill(rect(4.5f, 10f, 15f, 10.5f))                                   // muro
    pen.fill(rect(3.5f, 3.6f, 17f, 1.8f, 0.5f))                             // asta della tenda
    for (i in 0..3) {                                                       // 4 strisce, colori alternati
        val x = 3.5f + i * 4.25f
        pen.fill(svg("M$x 5 h4.25 v3.6 a2.125 1.4 0 0 1 -4.25 0 Z"), secondColor = i % 2 == 1)
    }
    pen.detail(rect(6.4f, 12.6f, 5.6f, 4.4f, 0.3f))                         // vetrina
    pen.detail(rect(13.8f, 12.6f, 3.8f, 7.9f, 0.3f))                        // porta
}

/**
 * Ciclofficina: chiave a forchetta doppia, inclinata di 45°.
 * Le teste sono UNA sola sagoma col taglio vuoto (si vede la mappa), non un
 * rettangolo chiaro sopra un cerchio: era il difetto della prima versione.
 */
private fun doubleWrench(pen: IconPen) {
    val tilt = Matrix().apply { setRotate(45f, 12f, 12f) }
    val handle = rect(10.9f, 6f, 2.2f, 12f)
    val top = wrenchHead(12f, 5.2f, 3.4f, 1f)
    val bottom = wrenchHead(12f, 18.8f, 3.4f, 1f).apply {
        transform(Matrix().apply { setRotate(180f, 12f, 18.8f) }) // testa capovolta
    }
    for (p in listOf(handle, top, bottom)) {
        p.transform(tilt)
        pen.fill(p)
    }
}

/**
 * Testa di chiave aperta rivolta in alto: cerchio (cx, cy, r) con un taglio largo
 * 2h che scende fin poco sotto il centro.
 */
private fun wrenchHead(cx: Float, cy: Float, r: Float, h: Float): Path {
    val y0 = cy - sqrt(r * r - h * h) // dove il taglio incontra il cerchio
    val yb = cy + 0.05f * r           // fondo del taglio
    return svg("M${cx - h} $y0 A$r $r 0 1 0 ${cx + h} $y0 L${cx + h} $yb A$h $h 0 0 1 ${cx - h} $yb Z")
}

/** Farmacia: croce greca, due bracci uguali. */
private fun greekCross(pen: IconPen) {
    pen.fill(svg("M4 9.2 H9.2 V4 H14.8 V9.2 H20 V14.8 H14.8 V20 H9.2 V14.8 H4 Z"))
}

/** Bagni: omino e donnina come sul cartello, con la riga in mezzo. */
private fun manAndWoman(pen: IconPen) {
    pen.fill(circle(7.4f, 4.9f, 1.9f))                                      // testa lui
    pen.fill(rect(5.4f, 7.6f, 4f, 7f, 1.4f))                                // busto
    pen.fill(rect(5.9f, 12.8f, 3f, 7.4f, 0.8f))                             // gambe
    pen.fill(rect(11.6f, 3.4f, 0.8f, 17f))                                  // riga
    pen.fill(circle(16.6f, 4.9f, 1.9f))                                     // testa lei
    pen.fill(svg("M16.6 7.4 C15.2 7.4 14.8 8 14.5 9.4 L13 15.2 H20.2 L18.7 9.4 C18.4 8 18 7.4 16.6 7.4 Z")) // vestito
    pen.fill(rect(15.1f, 14.5f, 3f, 5.7f, 0.8f))                            // gambe
}
