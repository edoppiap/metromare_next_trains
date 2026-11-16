package io.github.edoppiap.orarilido.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.edoppiap.orarilido.ui.theme.Card
import io.github.edoppiap.orarilido.ui.theme.CardDark
import io.github.edoppiap.orarilido.ui.theme.LocalDarkTheme

/*
 * Pulsanti sopra la mappa (☰, X di Go, 🚶|🚲, bussola, "centra"): seguono il
 * TEMA DELL'APP, non lo stile della mappa (16/11). Stesso colore delle schede
 * delle stazioni, niente bordo (nessun'altra superficie ce l'ha: provato un
 * grigio più chiaro col bordino e scartato), ombra leggera in entrambi i temi.
 */

/** Colore di fondo dei pulsanti sulla mappa: quello delle schede. */
@Composable
fun mapButtonFill(): Color = if (LocalDarkTheme.current) CardDark else Card

/**
 * Ombra, fondo e ritaglio tondo di un pulsante sulla
 * mappa. Va messo PRIMA di clickable. [fill] = false per chi il fondo se lo
 * disegna da sé (il selettore 🚶|🚲).
 */
@Composable
fun Modifier.mapButtonSurface(fill: Boolean = true): Modifier {
    val shape = RoundedCornerShape(50)
    var m = this.shadow(3.dp, shape).clip(shape)
    if (fill) m = m.background(mapButtonFill())
    return m
}
