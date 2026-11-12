package io.github.edoppiap.orarilido.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Material 3 ha dei "ruoli" di colore predefiniti: li riempiamo con la nostra
// palette. I colori dinamici di Android 12+ (presi dallo sfondo del telefono)
// sono volutamente disattivati: l'app deve avere sempre il blu della linea.
//
// Ruoli usati:
//   primary          → blu linea (pillole, pulsanti)
//   onSurfaceVariant → grigio dei testi secondari
//   tertiary         → corallo, treno imminente
//   error            → rosso, "?" corse non confermate

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Card,
    background = Background,
    onBackground = Ink,
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

@Composable
fun OrariTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
