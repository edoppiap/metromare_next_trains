package io.github.edoppiap.orarilido.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Le pagine del menu. */
enum class InfoPage(val title: String) {
    MENU("Menu"),
    STORY("La storia di questa app"),
    PRIVACY("Privacy"),
    TERMS("Termini d'uso"),
    CREDITS("Fonti e licenze"),
}

/**
 * Menu e pagine informative. Si apre sopra la mappa, come la giornata.
 * [onNavigate] con null = chiudi il menu.
 */
@Composable
fun InfoScreen(
    page: InfoPage,
    onNavigate: (InfoPage?) -> Unit,
    /** Voci solo per la build di prova (es. simulatore della modalità Go). */
    debugActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val back = { onNavigate(if (page == InfoPage.MENU) null else InfoPage.MENU) }
    BackHandler(onBack = back)
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Chevron(colors.primary, pointsLeft = true) }
            Text(page.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            if (page == InfoPage.MENU) {
                MenuList(onNavigate)
                if (debugActions.isNotEmpty()) DebugList(debugActions)
            } else {
                Text(
                    INFO_TEXTS.getValue(page),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .padding(16.dp),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = colors.onSurface,
                )
            }
        }
    }
}

/** Riquadro "Solo build di prova" con le voci del simulatore. */
@Composable
private fun DebugList(actions: List<Pair<String, () -> Unit>>) {
    val colors = MaterialTheme.colorScheme
    Text(
        "Solo build di prova",
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.onSurfaceVariant,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface),
    ) {
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
            Text(
                label,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = action)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                fontSize = 16.sp,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun MenuList(onNavigate: (InfoPage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface),
    ) {
        val pages = listOf(InfoPage.STORY, InfoPage.PRIVACY, InfoPage.TERMS, InfoPage.CREDITS)
        pages.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(p) }
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.title, modifier = Modifier.weight(1f), fontSize = 16.sp, color = colors.onSurface)
                Chevron(colors.onSurfaceVariant, pointsLeft = false)
            }
        }
    }
    // Versione dell'app, letta dal sistema.
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    Text(
        "Orari Metromare · versione $version\n" +
            "App non ufficiale, non affiliata a Cotral o Astral\n" +
            "Orari programmati, non in tempo reale",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        textAlign = TextAlign.Center,
        fontSize = 12.sp,
        color = colors.onSurfaceVariant,
    )
}

private const val REPO = "github.com/edoppiap/metromare_next_trains"

// BOZZE dei testi: da rivedere prima di distribuire l'app più ampiamente.
private val INFO_TEXTS = mapOf(
    InfoPage.STORY to """
        Questa app non è di Cotral né di Astral: l'ha fatta una persona sola, nel tempo libero, per sé e per la propria famiglia.

        È nata da un problema concreto: le app di navigazione mostravano orari della Metromare non aggiornati. Così ho preso l'orario ufficiale e ho costruito un'app che fa una cosa sola: aprirsi in un attimo e dirti tra quanto passa il prossimo treno dalla stazione più vicina, in entrambe le direzioni.

        Niente pubblicità, niente account, niente tracciamento.

        L'app è stata sviluppata insieme a Claude, un assistente di intelligenza artificiale di Anthropic.

        Il codice è libero (licenza MIT) e si trova su GitHub:
        $REPO
        Se trovi un errore negli orari, puoi segnalarlo lì.
    """.trimIndent(),

    InfoPage.PRIVACY to """
        In breve: l'app non raccoglie dati su di te.

        Posizione
        Se dai il permesso, la posizione serve solo a trovare la stazione più vicina, calcolare le distanze e disegnare i cerchi sulla mappa. Viene letta solo mentre l'app è aperta, resta sul telefono e non viene mai salvata né inviata dal codice dell'app. Puoi negare il permesso: l'app funziona lo stesso.

        Cosa resta sul telefono
        Solo le tue scelte: ultima stazione usata, stazioni preferite, "Vicina/Preferite", "a piedi/in bici". Nessuna coordinata. Si cancellano disinstallando l'app.

        Connessioni a internet
        La mappa è fornita da Google Maps: per mostrarla il telefono scarica le immagini della zona inquadrata dai server di Google, che ricevono dati tecnici come l'indirizzo IP e l'area visualizzata, secondo l'informativa di Google (policies.google.com/privacy).
        In futuro l'app potrà scaricare l'orario aggiornato da GitHub: un semplice file, senza alcun dato personale.

        Cosa NON c'è
        Niente statistiche d'uso, pubblicità, segnalazione automatica degli errori, account o profilazione.

        Contatti
        $REPO
    """.trimIndent(),

    InfoPage.TERMS to """
        App non ufficiale
        Questa app non è affiliata né approvata da Cotral, Astral, Regione Lazio o Roma Capitale. "Metromare" è un marchio dei rispettivi titolari ed è citato solo per indicare la linea.

        Orari programmati
        Gli orari sono quelli PROGRAMMATI dell'orario ufficiale, non in tempo reale: ritardi, soppressioni, scioperi, lavori e bus sostitutivi non sono mostrati. Prima di partire, soprattutto in caso di dubbi, controlla i canali ufficiali (ad esempio l'app Astral Infomobilità).
        Gli orari contrassegnati con "?" non sono confermati dalla fonte ufficiale.

        Nessuna garanzia
        L'app è gratuita e fornita "così com'è", senza garanzie di alcun tipo. L'autore non è responsabile di treni persi, ritardi o altri inconvenienti derivanti dal suo uso.

        Licenza
        Il codice è distribuito con licenza MIT.
    """.trimIndent(),

    InfoPage.CREDITS to """
        Orario
        Orario ufficiale Metromare (Roma–Lido) in vigore dal 9 novembre 2026, pubblicato da Astral / Regione Lazio.

        Stazioni e tracciato della linea
        © OpenStreetMap contributors, dati disponibili con licenza ODbL (openstreetmap.org/copyright).

        Mappa
        Google Maps.

        Alba e tramonto
        Calcolati sul telefono con la formula semplificata della NOAA.

        Codice
        Licenza MIT · $REPO
    """.trimIndent(),
)
