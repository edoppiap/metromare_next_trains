package io.github.edoppiap.orarilido.ui.theme

import androidx.compose.ui.graphics.Color

// Palette dell'app (vedi CLAUDE.md, sezione Stile):
// blu della linea + grigio "stazione"; corallo e rosso solo come segnali.
// Tema chiaro = solo blu e bianco: il blu fa da fondo (tendina, pagine),
// il bianco porta il contenuto (schede, riquadri).

// Tema chiaro
val Blue = Color(0xFF0077B3)          // colore della linea, e fondo della tendina e delle pagine
val Grey = Color(0xFF5F6B76)          // testi secondari (dentro le schede)
val GreyLight = Color(0xFFDDE2E7)     // separatori dentro le schede
val Card = Color(0xFFFFFFFF)
val Ink = Color(0xFF14181C)           // testo principale, numeri
val Coral = Color(0xFFFF7A59)         // treno in arrivo entro 3 minuti
val Red = Color(0xFFE03A3E)           // "?" delle corse non confermate
// Avvisi Cotral urgenti (sciopero, guasto) nel menu: scheda color crema col
// pallino ambra (versione B, scelta dall'utente il 15/11). Solo lì.
val AlertPale = Color(0xFFFFF1C9)     // fondo della scheda
val AlertAmber = Color(0xFFE09B00)    // pallino
val AlertMuted = Color(0xFF6B5410)    // testi secondari sulla scheda
/** Dietro la mappa, finché non è pronta (= colore della finestra in res/values/colors.xml). */
val MapPlaceholder = Color(0xFFF6F8FA)

// Tema scuro
val BlueDark = Color(0xFF4FB0E3)
val GreyDark = Color(0xFF9AA5B0)
val GreyLightDark = Color(0xFF2A323A)
val BackgroundDark = Color(0xFF0F1418)
val CardDark = Color(0xFF1A2128)
val InkDark = Color(0xFFF2F4F5)
val CoralDark = Color(0xFFFF9A80)
val RedDark = Color(0xFFFF5A60)
val AlertPaleDark = Color(0xFF33290F)
val AlertAmberDark = Color(0xFFFFC43D)
val AlertInkDark = Color(0xFFFFE6A6)  // testo principale sulla scheda scura
val AlertMutedDark = Color(0xFFCDB47C)
