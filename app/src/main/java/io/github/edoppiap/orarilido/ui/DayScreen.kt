package io.github.edoppiap.orarilido.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.ui.theme.OrariTheme

// ---- Dati della schermata (preparati dal ViewModel) ----

data class DayUi(
    val stationName: String,
    val relativeDay: String?,    // "Oggi", "Domani", "Ieri" o null
    val dateLabel: String,       // "giovedì 12 novembre"
    val dayTypeLabel: String,    // "Orario feriale", "Orario del sabato", "Orario festivo"
    val directions: List<DirectionTab>,
    val selectedDirection: Direction,
    /** Una riga per ora: "07 | 03 18 33 48". */
    val hours: List<HourRow>,
    /** Riga dell'ora del prossimo treno, per scorrere subito lì. */
    val nextHourIndex: Int,
)

data class DirectionTab(val direction: Direction, val title: String)
data class HourRow(val label: String, val cells: List<MinuteCell>)
data class MinuteCell(val text: String, val unconfirmed: Boolean, val past: Boolean, val next: Boolean)

/**
 * Orari di tutta la giornata per una stazione, nello stile dei cartelli
 * in stazione: una riga per ora, con i minuti di passaggio.
 * I treni già passati sono sbiaditi, il prossimo è evidenziato.
 */
@Composable
fun DayScreen(
    day: DayUi,
    onBack: () -> Unit,
    onShiftDay: (Long) -> Unit,
    onDirection: (Direction) -> Unit,
) {
    BackHandler(onBack = onBack)  // il tasto "indietro" torna alla mappa
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    // Quando cambiano giorno o direzione, scorri all'ora del prossimo treno
    // (una riga sopra, per vedere anche il treno appena passato).
    LaunchedEffect(day.dateLabel, day.selectedDirection) {
        listState.scrollToItem((day.nextHourIndex - 1).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp),
    ) {
        // Intestazione: indietro + nome stazione
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Chevron(colors.primary, pointsLeft = true) }
            Text(day.stationName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        }

        // Selettore del giorno: ‹  Oggi · giovedì 12 novembre · Feriale  ›
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onShiftDay(-1) }) { Chevron(colors.primary, pointsLeft = true) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    day.relativeDay ?: day.dateLabel.replaceFirstChar { it.uppercase() },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Text(
                    (if (day.relativeDay != null) day.dateLabel + " · " else "") + day.dayTypeLabel,
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onShiftDay(1) }) { Chevron(colors.primary, pointsLeft = false) }
        }

        // Direzioni: selettore con la pillola blu che scivola sotto quella scelta.
        SegmentedToggle(
            options = day.directions.map { it.direction to it.title },
            selected = day.selectedDirection,
            onSelect = onDirection,
            modifier = Modifier.padding(bottom = 8.dp),
            fontSize = 14.sp,
            segmentPadding = 14.dp,
        )

        // Tabella: una riga per ora, dentro una scheda
        LazyColumn(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(day.hours) { i, hour ->
                if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                HourLine(hour)
            }
        }

        Text(
            "Orari programmati, non in tempo reale",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant,
        )
    }
}

/** "07   03   18   33   48" */
@Composable
private fun HourLine(hour: HourRow) {
    val colors = MaterialTheme.colorScheme
    val allPast = hour.cells.all { it.past }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            hour.label,
            modifier = Modifier.width(48.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (allPast) colors.onSurfaceVariant.copy(alpha = 0.5f) else colors.onSurface,
        )
        hour.cells.forEach { cell ->
            val text = buildAnnotatedString {
                append(cell.text)
                if (cell.unconfirmed) {
                    withStyle(
                        SpanStyle(
                            color = colors.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 0.7.em,
                            baselineShift = BaselineShift.Superscript,
                        )
                    ) { append("?") }
                }
            }
            Text(
                text,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (cell.next) Modifier.background(colors.primary) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 18.sp,
                fontWeight = if (cell.next) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    cell.next -> colors.onPrimary
                    cell.past -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> colors.onSurface
                },
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 700, widthDp = 390)
@Composable
private fun DayScreenPreview() {
    OrariTheme {
        DayScreen(
            DayUi(
                stationName = "Acilia",
                relativeDay = "Oggi",
                dateLabel = "giovedì 12 novembre",
                dayTypeLabel = "Orario feriale",
                directions = listOf(
                    DirectionTab(Direction.TO_ROMA, "Verso Roma"),
                    DirectionTab(Direction.TO_OSTIA, "Verso Ostia"),
                ),
                selectedDirection = Direction.TO_ROMA,
                hours = (5..9).map { h ->
                    HourRow(
                        "%02d".format(h),
                        listOf("03", "10", "18", "33", "48").map {
                            MinuteCell(it, unconfirmed = it == "10", past = h < 7, next = h == 7 && it == "03")
                        },
                    )
                },
                nextHourIndex = 2,
            ),
            onBack = {}, onShiftDay = {}, onDirection = {},
        )
    }
}
