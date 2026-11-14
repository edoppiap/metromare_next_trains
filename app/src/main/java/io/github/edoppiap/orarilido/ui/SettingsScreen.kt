package io.github.edoppiap.orarilido.ui

import io.github.edoppiap.orarilido.ui.theme.LocalOnBackground
import io.github.edoppiap.orarilido.ui.theme.LocalDarkTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.edoppiap.orarilido.data.AppSettings
import io.github.edoppiap.orarilido.data.AppTheme
import io.github.edoppiap.orarilido.data.MapTheme
import io.github.edoppiap.orarilido.data.Speeds
import io.github.edoppiap.orarilido.data.TravelMode
import io.github.edoppiap.orarilido.places.PlaceKind
import io.github.edoppiap.orarilido.ui.theme.Blue
import io.github.edoppiap.orarilido.ui.theme.BlueDark
import io.github.edoppiap.orarilido.ui.theme.Card
import io.github.edoppiap.orarilido.ui.theme.CardDark
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pagina Impostazioni (dal menu ☰). Ogni cambio si applica subito e viene
 * salvato: non c'è un pulsante "Salva".
 *
 * [onChange] riceve una funzione "vecchie impostazioni → nuove", così ogni riga
 * cambia solo il suo campo.
 */
@Composable
fun SettingsPage(
    settings: AppSettings,
    /** "9 novembre 2026": inizio di validità dell'orario in uso. */
    validFrom: String,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearSaved: () -> Unit,
) {
    // ---- Aspetto ----
    SectionTitle("Aspetto")
    SettingsCard {
        Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RowTitle("Tema dell'app")
            WideToggle(
                options = AppTheme.entries,
                selected = settings.appTheme,
                onSelect = { t -> onChange { it.copy(appTheme = t) } },
            ) { t, color -> ToggleText(APP_THEME_LABELS.getValue(t), color) }
        }
        Divider()
        Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                RowTitle("Mappa")
                RowSubtitle(MAP_THEME_SUBTITLES.getValue(settings.mapTheme))
            }
            WideToggle(
                options = MapTheme.entries,
                selected = settings.mapTheme,
                onSelect = { t -> onChange { it.copy(mapTheme = t) } },
            ) { t, color -> ToggleText(MAP_THEME_LABELS.getValue(t), color) }
            MapPreview(settings.mapTheme)
        }
    }

    // ---- Sulla mappa ----
    SectionTitle("Sulla mappa")
    SettingsCard {
        Column(Modifier.padding(RowPadding)) {
            SwitchRow(
                title = "Punti di interesse",
                subtitle = "Solo a mappa piena, da vicino",
                checked = settings.placesOn,
                onCheckedChange = { on -> onChange { it.copy(placesOn = on) } },
                padded = false,
            )
            Spacer(Modifier.height(10.dp))
            PlaceKindGrid(
                selected = settings.placeKinds,
                enabled = settings.placesOn,
                onToggle = { kind ->
                    onChange { s -> s.copy(placeKinds = if (kind in s.placeKinds) s.placeKinds - kind else s.placeKinds + kind) }
                },
            )
        }
        Divider()
        SwitchRow(
            title = "Cerchi dei tempi",
            subtitle = "5, 10 e 15 minuti attorno a te",
            checked = settings.circlesOn,
            onCheckedChange = { on -> onChange { it.copy(circlesOn = on) } },
        )
        Divider()
        SwitchRow(
            title = "Percorso fino alla stazione",
            subtitle = "Tratteggio sulle strade, calcolato sul telefono",
            checked = settings.routeOn,
            onCheckedChange = { on -> onChange { it.copy(routeOn = on) } },
        )
    }

    // ---- A piedi e in bici ----
    SectionTitle("A piedi e in bici")
    SettingsCard {
        SpeedRow(TravelMode.WALK, settings.walkLevel) { l -> onChange { it.copy(walkLevel = l) } }
        Divider()
        SpeedRow(TravelMode.BIKE, settings.bikeLevel) { l -> onChange { it.copy(bikeLevel = l) } }
        Divider()
        SwitchRow(
            title = "Sbiadisci i treni che non prendi",
            subtitle = "In base alla distanza e alla tua velocità (a piedi o in bici, come scelto sulla mappa)",
            checked = settings.fadeMissed,
            onCheckedChange = { on -> onChange { it.copy(fadeMissed = on) } },
        )
    }

    // ---- In treno ----
    SectionTitle("In treno")
    SettingsCard {
        SwitchRow(
            title = "Riconosci quando sono in treno",
            subtitle = "Mostra le fermate davanti e quando ci arrivi. Solo con l'app aperta.",
            checked = settings.goOn,
            onCheckedChange = { on -> onChange { it.copy(goOn = on) } },
        )
    }

    // ---- Orario ----
    SectionTitle("Orario")
    SettingsCard {
        Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                RowTitle("In vigore dal $validFrom")
                RowSubtitle("Orario ufficiale Astral, incluso nell'app")
            }
            // Il download dell'orario aggiornato non c'è ancora: la voce è solo annunciata.
            Text(
                "Controlla aggiornamenti · in arrivo",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---- Dati salvati ----
    SectionTitle("Dati salvati")
    SettingsCard {
        Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RowSubtitle(
                "Sul telefono restano solo le stazioni preferite, l'ultima stazione usata, " +
                    "le fermate scelte in treno e queste impostazioni. Mai la tua posizione."
            )
            ClearSavedButton(onClearSaved)
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------------------
// Righe

private val RowPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = LocalOnBackground.current.accent,  // sta sul fondo, fuori dai riquadri
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RowTitle(text: String) {
    Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun RowSubtitle(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Riga con interruttore: tutta la riga è toccabile, non solo l'interruttore. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    padded: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (padded) Modifier.padding(RowPadding) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            RowTitle(title)
            if (subtitle != null) RowSubtitle(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,  // lo gestisce la riga intera (toggleable)
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                checkedBorderColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.outlineVariant,
                uncheckedBorderColor = colors.outlineVariant,
            ),
        )
    }
}

/** Selettore a pillola largo quanto la riga, con segmenti uguali. */
@Composable
private fun <T> WideToggle(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    description: (T) -> String? = { null },
    label: @Composable (T, Color) -> Unit,
) {
    SegmentedToggle(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.outlineVariant,
        segmentPadding = 2.dp,
        equalWidth = true,
        description = description,
        label = label,
    )
}

@Composable
private fun ToggleText(text: String, color: Color) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}

// ---------------------------------------------------------------------------
// Anteprima della mappa

/**
 * Riquadro che mostra come sarà la mappa: terreno, acqua, strade, linea blu.
 * In "Auto" due riquadri affiancati: di giorno e di notte.
 * I colori imitano gli stili veri (res/raw/map_style_light/dark.json).
 */
@Composable
private fun MapPreview(theme: MapTheme) {
    val appDark = LocalDarkTheme.current
    val panels = when (theme) {
        MapTheme.AUTO -> listOf(false, true)
        MapTheme.APP -> listOf(appDark)
        MapTheme.LIGHT -> listOf(false)
        MapTheme.DARK -> listOf(true)
    }
    // Cambiando scelta i riquadri sfumano invece di saltare.
    AnimatedContent(
        targetState = panels,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "mapPreview",
    ) { shown ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { dark ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MiniMap(dark, Modifier.fillMaxWidth().height(72.dp))
                    if (theme == MapTheme.AUTO) {
                        Text(
                            if (dark) "dopo il tramonto" else "di giorno",
                            modifier = Modifier.padding(top = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMap(dark: Boolean, modifier: Modifier) {
    val land = if (dark) Color(0xFF1C232A) else Color(0xFFE4E9EE)
    val water = if (dark) Color(0xFF0F2C3E) else Color(0xFFA9CFE6)
    val road = if (dark) Color(0xFF2C363F) else Color(0xFFFBFCFD)
    val line = if (dark) BlueDark else Blue
    val dot = if (dark) CardDark else Card
    Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
        val w = size.width
        val h = size.height
        clipRect {
            drawRect(land)
            // Mare in basso a sinistra (Ostia), strade, e la linea che sale verso Roma.
            drawPath(Path().apply {
                moveTo(0f, h * 0.8f); quadraticTo(w * 0.19f, h * 0.7f, w * 0.29f, h); lineTo(0f, h); close()
            }, water)
            val roadStroke = Stroke(4.dp.toPx())
            drawLine(road, Offset(w * 0.06f, 0f), Offset(w * 0.44f, h), roadStroke.width)
            drawLine(road, Offset(0f, h * 0.42f), Offset(w, h * 0.42f), roadStroke.width)
            drawLine(road, Offset(w * 0.75f, 0f), Offset(w * 0.65f, h), roadStroke.width)
            drawPath(Path().apply {
                moveTo(w * 0.38f, 0f); quadraticTo(w * 0.56f, h * 0.42f, w, h * 0.33f)
            }, road, style = roadStroke)
            drawPath(Path().apply {
                moveTo(w * 0.11f, h * 0.89f)
                quadraticTo(w * 0.38f, h * 0.67f, w * 0.55f, h * 0.5f)
                quadraticTo(w * 0.72f, h * 0.33f, w * 0.97f, h * 0.14f)
            }, line, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            listOf(Offset(w * 0.33f, h * 0.7f), Offset(w * 0.68f, h * 0.38f)).forEach { c ->
                drawCircle(dot, 4.dp.toPx(), c)
                drawCircle(line, 4.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Punti di interesse

/** Le sei categorie, due per riga, con le stesse icone della mappa. */
@Composable
private fun PlaceKindGrid(selected: Set<PlaceKind>, enabled: Boolean, onToggle: (PlaceKind) -> Unit) {
    // Interruttore spento: le categorie restano visibili (e ricordate) ma grigie.
    val gridAlpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(300), label = "placesGrid")
    Column(Modifier.alpha(gridAlpha), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PLACE_KIND_ORDER.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { kind ->
                    PlaceKindChip(kind, kind in selected, enabled, { onToggle(kind) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlaceKindChip(kind: PlaceKind, checked: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current.density
    // La stessa icona disegnata a mano della mappa (PlaceIcons.kt), col bordino del colore della scheda.
    val icon = remember(kind, dark, density) {
        drawPlaceIcon(kind, dark, colors.surface.toArgb(), density).asImageBitmap()
    }
    val chipAlpha by animateFloatAsState(if (checked) 1f else 0.45f, tween(200), label = "chip")
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(22.dp).alpha(chipAlpha))
        Spacer(Modifier.width(8.dp))
        Text(
            PLACE_KIND_LABELS.getValue(kind),
            modifier = Modifier.weight(1f).alpha(chipAlpha),
            fontSize = 14.sp,
            color = colors.onSurface,
            maxLines = 1,
        )
        CheckBox(checked)
    }
}

/** Quadratino con la spunta, disegnato a mano (blu se scelto, solo bordo se no). */
@Composable
private fun CheckBox(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tick by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "tick")
    Canvas(Modifier.size(18.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val boxSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawRoundRect(androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.primary, tick),
            Offset(inset, inset), boxSize, r, style = Stroke(stroke))
        if (tick > 0f) {
            drawRoundRect(colors.primary.copy(alpha = tick), Offset(inset, inset), boxSize, r)
            val path = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.71f)
                lineTo(size.width * 0.78f, size.height * 0.31f)
            }
            drawPath(path, colors.onPrimary.copy(alpha = tick), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

// ---------------------------------------------------------------------------
// Velocità

/**
 * Riga "A piedi" o "In bici": selettore a cinque velocità con le icone
 * (doppia lumaca, lumaca, omino o bici, lepre, doppia lepre), poi i cerchi
 * 5/10/15 minuti che si allargano o stringono, i km/h e un esempio.
 */
@Composable
private fun SpeedRow(mode: TravelMode, level: Int, onLevel: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val walk = mode == TravelMode.WALK
    val table = if (walk) Speeds.WALK_KMH else Speeds.BIKE_KMH
    Column(Modifier.padding(RowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowTitle(if (walk) "A piedi" else "In bici")
        WideToggle(
            options = (0..4).toList(),
            selected = level,
            onSelect = onLevel,
            description = { SPEED_NAMES[it] },
        ) { l, color ->
            Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) { SpeedIcon(l, walk, color) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeedRings(factor = (table[level] / table[Speeds.NORMAL]).toFloat(), maxFactor = (table[4] / table[Speeds.NORMAL]).toFloat())
            Spacer(Modifier.width(14.dp))
            Column {
                val kmh = table[level]
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatKmh(kmh), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Text(" km/h", modifier = Modifier.padding(bottom = 4.dp), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
                // Esempio: 1 km a piedi, 3 km in bici.
                val km = if (walk) 1 else 3
                val minutes = (km / kmh * 60).roundToInt()
                Text("$km km = $minutes min", fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** L'icona di una velocità: due lumache, una, omino/bici, una lepre, due. */
@Composable
private fun SpeedIcon(level: Int, walk: Boolean, color: Color) {
    when (level) {
        0 -> DoubleIcon { SnailIcon(color, 16.dp) }
        1 -> SnailIcon(color)
        2 -> if (walk) WalkerIcon(color) else BikeIcon(color)
        3 -> HareIcon(color)
        else -> DoubleIcon { HareIcon(color, 16.dp) }
    }
}

/** Due icone piccole un po' sovrapposte (doppia lumaca, doppia lepre). */
@Composable
private fun DoubleIcon(icon: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
        icon()
        icon()
    }
}

/**
 * Cerchi 5/10/15 minuti, come sulla mappa, che si allargano con la velocità.
 * Il cerchio tratteggiato è il massimo (15 minuti a doppia lepre): fa da metro.
 */
@Composable
private fun SpeedRings(factor: Float, maxFactor: Float) {
    val colors = MaterialTheme.colorScheme
    val f by animateFloatAsState(factor, tween(350, easing = FastOutSlowInEasing), label = "rings")
    Canvas(Modifier.size(84.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2 - 2.dp.toPx()
        val unit = outer / (3 * maxFactor)
        drawCircle(
            colors.onSurfaceVariant.copy(alpha = 0.35f), outer, c,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))),
        )
        listOf(0.75f, 0.55f, 0.35f).forEachIndexed { i, a ->
            drawCircle(colors.primary.copy(alpha = a), unit * (i + 1) * f, c, style = Stroke(1.5.dp.toPx()))
        }
        drawCircle(colors.surface, 6.5.dp.toPx(), c)
        drawCircle(colors.primary, 4.5.dp.toPx(), c)
    }
}

private fun formatKmh(kmh: Double): String =
    if (kmh % 1.0 == 0.0) kmh.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", kmh)

// ---------------------------------------------------------------------------
// Cancella dati

/** "Cancella preferite e fermate salvate", con conferma nella pagina stessa. */
@Composable
private fun ClearSavedButton(onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // 0 = pulsante, 1 = "Sicuro?", 2 = fatto
    var step by remember { mutableStateOf(0) }
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "clearSaved",
    ) { s ->
        when (s) {
            0 -> LinkText("Cancella preferite e fermate salvate", colors.error) { step = 1 }
            1 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Le preferite andranno perse.", modifier = Modifier.weight(1f), fontSize = 14.sp, color = colors.onSurface)
                LinkText("Annulla", colors.primary) { step = 0 }
                LinkText("Cancella", colors.error) {
                    onClear()
                    step = 2
                }
            }
            else -> Text("Cancellate", fontSize = 15.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

// ---------------------------------------------------------------------------
// Testi

private val APP_THEME_LABELS = mapOf(AppTheme.SYSTEM to "Sistema", AppTheme.LIGHT to "Chiaro", AppTheme.DARK to "Scuro")
private val MAP_THEME_LABELS = mapOf(MapTheme.AUTO to "Auto", MapTheme.APP to "Come app", MapTheme.LIGHT to "Chiara", MapTheme.DARK to "Scura")
private val MAP_THEME_SUBTITLES = mapOf(
    MapTheme.AUTO to "Chiara di giorno, scura dopo il tramonto",
    MapTheme.APP to "Segue il tema dell'app",
    MapTheme.LIGHT to "Sempre chiara",
    MapTheme.DARK to "Sempre scura",
)
/** Nomi letti da TalkBack (sullo schermo ci sono solo le icone). */
private val SPEED_NAMES = listOf("Doppia lumaca", "Lumaca", "Normale", "Lepre", "Doppia lepre")
private val PLACE_KIND_ORDER = listOf(
    PlaceKind.WATER, PlaceKind.BAR, PlaceKind.BIKE_SHOP, PlaceKind.PHARMACY, PlaceKind.SUPERMARKET, PlaceKind.TOILET,
)
private val PLACE_KIND_LABELS = mapOf(
    PlaceKind.WATER to "Fontanelle",
    PlaceKind.BAR to "Bar",
    PlaceKind.BIKE_SHOP to "Ciclofficine",
    PlaceKind.PHARMACY to "Farmacie",
    PlaceKind.SUPERMARKET to "Supermercati",
    PlaceKind.TOILET to "Bagni",
)
