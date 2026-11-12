package io.github.edoppiap.orarilido.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.edoppiap.orarilido.schedule.Station
import io.github.edoppiap.orarilido.ui.theme.OrariTheme

/** Schermata principale: stazione in alto, poi un riquadro per ogni direzione. */
@Composable
fun MainScreen(state: MainUiState, onSelectStation: (String) -> Unit) {
    // Mostrare o no la lista delle stazioni. "Saveable" = sopravvive alla rotazione.
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)  // non finire sotto barra di stato/navigazione
    ) {
        if (!state.ready) return@Column  // dura pochi ms: la finestra resta sullo sfondo

        if (picking) {
            BackHandler { picking = false }  // il tasto "indietro" chiude la lista
            StationPicker(state.stations, state.stationId) { id ->
                onSelectStation(id)
                picking = false
            }
        } else {
            Departures(state, onChangeStation = { picking = true })
        }
    }
}

@Composable
private fun Departures(state: MainUiState, onChangeStation: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nome stazione + "Cambia"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.stationName,
                modifier = Modifier.weight(1f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onChangeStation) { Text("Cambia", fontWeight = FontWeight.SemiBold) }
        }

        state.blocks.forEach { DirectionCard(it) }

        Text(
            "Orari programmati, non in tempo reale · in vigore dal ${state.validFrom}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectionCard(block: DirectionBlock) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // Intestazione: pillola blu "Verso Roma" + capolinea in grigio
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                block.title,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                "  " + block.terminus,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        block.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DepartureLine(row, big = i == 0)  // il primo treno è il più grande
        }
    }
}

/** "tra 4 min ............ 08:17" con eventuale "?" rosso ad apice. */
@Composable
private fun DepartureLine(row: DepartureRow, big: Boolean) {
    val colors = MaterialTheme.colorScheme
    val numberColor = if (row.imminent) colors.tertiary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (big) 6.dp else 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            buildAnnotatedString {
                if (row.waitUnit.isNotEmpty()) {
                    withStyle(SpanStyle(fontSize = 16.sp, color = colors.onSurfaceVariant)) { append("tra ") }
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = numberColor)) { append(row.wait) }
                if (row.waitUnit.isNotEmpty()) {
                    withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = numberColor)) {
                        append(" " + row.waitUnit)
                    }
                }
            },
            modifier = Modifier.weight(1f),
            fontSize = if (big) 44.sp else 28.sp,
        )
        Text(
            buildAnnotatedString {
                append(row.clock)
                if (row.unconfirmed) {
                    // "?" piccolo, rosso e sollevato come un esponente
                    withStyle(
                        SpanStyle(
                            color = colors.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 0.7.em,
                            baselineShift = BaselineShift.Superscript,
                        )
                    ) { append("?") }
                }
            },
            modifier = Modifier.padding(bottom = if (big) 8.dp else 4.dp),
            fontSize = 18.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

/** Lista delle stazioni (nella fase 5 sarà ordinata per distanza). */
@Composable
private fun StationPicker(stations: List<Station>, selectedId: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Scegli la stazione",
            modifier = Modifier.padding(16.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyColumn {
            items(stations, key = { it.id }) { s ->
                val selected = s.id == selectedId
                Text(
                    s.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(s.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    fontSize = 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

// Anteprima per Android Studio (pannello "Preview"), con dati finti.
@Preview(showBackground = true, heightDp = 640)
@Composable
private fun MainScreenPreview() {
    OrariTheme {
        MainScreen(
            MainUiState(
                ready = true,
                stationId = "acilia",
                stationName = "Acilia",
                validFrom = "9/11/2026",
                blocks = listOf(
                    DirectionBlock(
                        "Verso Roma", "Porta San Paolo", listOf(
                            DepartureRow("2", "min", "08:03", unconfirmed = false, imminent = true),
                            DepartureRow("9", "min", "08:10", unconfirmed = true, imminent = false),
                            DepartureRow("17", "min", "08:18", unconfirmed = false, imminent = false),
                        )
                    ),
                    DirectionBlock(
                        "Verso Ostia", "Cristoforo Colombo", listOf(
                            DepartureRow("5", "min", "08:06", unconfirmed = false, imminent = false),
                            DepartureRow("20", "min", "08:21", unconfirmed = false, imminent = false),
                            DepartureRow("35", "min", "08:36", unconfirmed = false, imminent = false),
                        )
                    ),
                ),
            ),
            onSelectStation = {},
        )
    }
}
