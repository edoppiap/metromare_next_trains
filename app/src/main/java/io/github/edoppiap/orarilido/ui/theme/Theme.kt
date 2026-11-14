package io.github.edoppiap.orarilido.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Material 3 ha dei "ruoli" di colore predefiniti: li riempiamo con la nostra
// palette. I colori dinamici di Android 12+ (presi dallo sfondo del telefono)
// sono volutamente disattivati: l'app deve avere sempre il blu della linea.
//
// Ruoli usati:
//   background       → fondo della tendina e delle pagine (chiaro: BLU)
//   surface          → schede e riquadri (bianco)
//   primary          → blu linea (pillole, pulsanti, dentro le schede)
//   onSurfaceVariant → grigio dei testi secondari (dentro le schede)
//   tertiary         → corallo, treno imminente
//   error            → rosso, "?" corse non confermate
// Per quello che sta DIRETTAMENTE sul fondo (fuori dalle schede) c'è [OnBackground].

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Card,
    background = Blue,
    onBackground = Card,
    surface = Card,
    onSurface = Ink,
    onSurfaceVariant = Grey,
    outlineVariant = GreyLight,
    tertiary = Coral,
    error = Red,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = BackgroundDark,
    background = BackgroundDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    onSurfaceVariant = GreyDark,
    outlineVariant = GreyLightDark,
    tertiary = CoralDark,
    error = RedDark,
)

/**
 * Colori di ciò che sta direttamente sul fondo (tendina, pagine), fuori dalle
 * schede: titoli, "‹ indietro", note in piccolo, divisori, maniglia.
 * Nel tema chiaro il fondo è blu, quindi sono bianchi; nel tema scuro sono
 * quelli di sempre.
 */
@Immutable
data class OnBackground(
    /** Titoli ("In primo piano", nome della stazione nella giornata). */
    val text: Color,
    /** Note in piccolo ("Orari programmati…"). */
    val muted: Color,
    /** "‹ indietro", titoli di sezione delle Impostazioni. */
    val accent: Color,
    /** Divisori e maniglia della tendina. */
    val line: Color,
    /** Pillola della direzione posata sul fondo (Go: "In treno [Verso Ostia]"). */
    val pill: Color,
    val onPill: Color,
    /** "?" della legenda delle corse non confermate: il rosso pieno sul blu non si leggerebbe. */
    val question: Color,
)

private val LightOnBackground = OnBackground(
    text = Card,
    muted = Card.copy(alpha = 0.8f),
    accent = Card,
    line = Card.copy(alpha = 0.45f),
    pill = Card,
    onPill = Blue,
    question = Color(0xFFFFB4B6),  // rosso schiarito: resta "il ? rosso" anche sul blu
)

private val DarkOnBackground = OnBackground(
    text = InkDark,
    muted = GreyDark,
    accent = BlueDark,
    line = GreyLightDark,
    pill = BlueDark,
    onPill = BackgroundDark,
    question = RedDark,
)

/** Colori sul fondo: `LocalOnBackground.current.text` ecc. */
val LocalOnBackground = staticCompositionLocalOf { LightOnBackground }

/**
 * L'app è in tema scuro? (Prima si deduceva dalla luminosità del fondo, ma
 * ora nel tema chiaro il fondo è blu, cioè "scuro".)
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun OrariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOnBackground provides if (darkTheme) DarkOnBackground else LightOnBackground,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
