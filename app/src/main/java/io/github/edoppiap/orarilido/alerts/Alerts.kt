package io.github.edoppiap.orarilido.alerts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Avvisi Cotral (scioperi, guasti, lavori). Kotlin puro, niente Android:
 * si prova con i test JUnit.
 *
 * Da dove arrivano: uno script su GitHub (tools/cotral_alerts.py, ogni 30
 * minuti) legge gli avvisi pubblici di Cotral, tiene quelli sulla Metromare
 * e scrive avvisi.json nel ramo "dati". L'app scarica quel file e basta:
 * mostra SEMPRE il titolo originale di Cotral e il link alla sua pagina,
 * mai nostre interpretazioni. Le regole "urgente / notizia" stanno nello
 * script; qui si decide solo cosa mostrare ADESSO e quando accendere il
 * pallino rosso sull'icona del menu.
 */

/** Il file avvisi.json. */
@Serializable
data class AlertsFile(
    /** Quando lo script ha cambiato gli avvisi l'ultima volta (ora di Roma). */
    val updated: String? = null,
    val alerts: List<Alert> = emptyList(),
)

/** Un avviso, come lo scrive lo script. */
@Serializable
data class Alert(
    val id: Long,
    /** Ultima modifica di Cotral, es. "2026-11-13T15:14:58". Se cambia, il pallino si riaccende. */
    val modified: String,
    /** "urgente" (in cima al menu, pallino) oppure "notizia" (solo nella lista). */
    val level: String,
    val title: String,
    val link: String,
    /** Fine validità (o fine sciopero), es. "2026-11-20T23:59:00"; null = non indicata. */
    val end: String? = null,
    /** Solo scioperi: fasce in cui i treni sono in sciopero, es. ["08:30-16:59"]. */
    val trainStrikeBands: List<String> = emptyList(),
) {
    val urgent: Boolean get() = level == "urgente"
    val isStrike: Boolean get() = title.contains("sciopero", ignoreCase = true)
    val endTime: LocalDateTime? get() = end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val modifiedTime: LocalDateTime? get() = runCatching { LocalDateTime.parse(modified) }.getOrNull()
}

object AlertsParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Legge il file; null se non è valido (allora l'app tiene la copia di
     * prima). Per sicurezza tiene solo link a pagine di Cotral: anche se
     * qualcuno manomettesse il file, l'app non manderebbe nessuno altrove.
     */
    fun parse(text: String): AlertsFile? {
        val file = runCatching { json.decodeFromString(AlertsFile.serializer(), text) }.getOrNull() ?: return null
        val ok = file.alerts.filter {
            it.link.startsWith("https://cotralspa.it/") &&
                it.title.isNotBlank() && it.title.length <= 200 &&
                it.modifiedTime != null
        }
        return file.copy(alerts = ok)
    }
}

/** Cosa mostrare adesso. */
data class AlertsView(
    /** Urgenti non tolti con la X: in cima al menu (l'interfaccia ne mostra al massimo 2). */
    val urgent: List<Alert> = emptyList(),
    /** Tutti gli avvisi validi, urgenti per primi: pagina "Notizie". */
    val all: List<Alert> = emptyList(),
    /** Pallino rosso sull'icona ☰. */
    val badge: Boolean = false,
    /** Sciopero OGGI (riga in cima alla tendina, pallino sui treni a rischio); null = niente. */
    val strike: StrikeDay? = null,
)

/**
 * Giorno di sciopero dei treni. [bands] = fasce in cui i treni sono in
 * sciopero (vuota se lo script non è riuscito a leggerle: allora c'è solo la
 * riga "Sciopero oggi", senza pallini sui treni).
 */
data class StrikeDay(
    val date: LocalDate,
    val bands: List<Pair<LocalTime, LocalTime>>,
    val link: String,
    val end: LocalDateTime,
) {
    /**
     * Treno a rischio = parte DALLA SUA ORIGINE (di solito il capolinea)
     * durante una fascia di sciopero. Di solito le corse partite prima
     * dell'inizio arrivano a destinazione: il treno delle 08:25 da Porta San
     * Paolo che passa ad Acilia alle 08:46 non è a rischio. Nell'app si legge
     * solo "potrebbe non passare": non è una certezza in nessun senso.
     */
    fun atRisk(originDeparture: LocalDateTime): Boolean {
        if (originDeparture.toLocalDate() != date) return false
        val t = originDeparture.toLocalTime()
        return bands.any { (from, to) -> !t.isBefore(from) && !t.isAfter(to) }
    }

    /** "Sciopero oggi · treni a rischio 08:30–16:59 e 20:00–23:59" */
    val title: String
        get() = if (bands.isEmpty()) "Sciopero oggi"
        else "Sciopero oggi · treni a rischio " + joinItalian(bands.map { (a, b) -> "${a.format(HM)}–${b.format(HM)}" })

    /** La riga sotto spiega il pallino (solo se ci sono pallini da spiegare). */
    val legend: String? get() = if (bands.isEmpty()) null else "● = treno a rischio, potrebbe non passare"

    private companion object {
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        fun joinItalian(parts: List<String>) =
            if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
    }
}

object AlertRules {

    /**
     * La sera prima di uno sciopero il pallino si riaccende (deciso con
     * l'utente): da quest'ora del giorno prima del giorno di fine.
     */
    const val EVE_HOUR = 18

    /** Avvisi ancora validi: senza fine, o con la fine non ancora passata. */
    fun valid(file: AlertsFile?, now: LocalDateTime): List<Alert> =
        file?.alerts.orEmpty()
            .filter { a -> a.endTime.let { it == null || !it.isBefore(now) } }
            .sortedWith(compareBy<Alert> { !it.urgent }.thenByDescending { it.modified })

    /** Siamo nella "vigilia" di uno sciopero? (dalle 18 del giorno prima alla fine) */
    fun inEve(a: Alert, now: LocalDateTime): Boolean {
        val end = a.endTime ?: return false
        if (!a.isStrike) return false
        val eveStart = end.toLocalDate().minusDays(1).atTime(EVE_HOUR, 0)
        return !now.isBefore(eveStart)
    }

    /**
     * Chiave di un avviso urgente "in questo momento". Visto/tolto si
     * ricordano per chiave, quindi l'avviso torna (pallino acceso, di nuovo
     * in cima) se Cotral lo modifica oppure se arriva la vigilia dello sciopero.
     */
    fun key(a: Alert, now: LocalDateTime): String =
        "${a.id}@${a.modified}@" + if (inEve(a, now)) "vigilia" else "annuncio"

    fun view(file: AlertsFile?, now: LocalDateTime, seen: Set<String>, dismissed: Set<String>): AlertsView {
        val all = valid(file, now)
        val urgent = all.filter { it.urgent && key(it, now) !in dismissed }
        return AlertsView(
            urgent = urgent,
            all = all,
            badge = urgent.any { key(it, now) !in seen },
            strike = strikeToday(file, now),
        )
    }

    /**
     * C'è uno sciopero dei treni OGGI? Il giorno è quello della fine dello
     * sciopero (la data di "inizio" sulla pagina Cotral è quella di
     * pubblicazione). Solo gli scioperi urgenti: quelli "notizia" sono solo
     * dei bus o rinviati. Sparisce dopo la fine dell'ultima fascia.
     * La X nel menu non lo toglie: il giorno dello sciopero serve vederlo.
     */
    fun strikeToday(file: AlertsFile?, now: LocalDateTime): StrikeDay? {
        val a = valid(file, now).firstOrNull { it.urgent && it.isStrike && it.endTime?.toLocalDate() == now.toLocalDate() }
            ?: return null
        val end = a.endTime ?: return null
        val bands = a.trainStrikeBands.mapNotNull { parseBand(it) }
        if (bands.isNotEmpty() && now.toLocalTime().isAfter(bands.maxOf { it.second })) return null
        return StrikeDay(end.toLocalDate(), bands, a.link, end)
    }

    /** "08:30-16:59" -> 08:30..16:59 (null se non si legge). */
    private fun parseBand(s: String): Pair<LocalTime, LocalTime>? = runCatching {
        val (a, b) = s.split("-").map { LocalTime.parse(it.trim()) }
        a to b
    }.getOrNull()

    /** Chiavi da segnare come "viste" quando si apre il menu. */
    fun keysToMarkSeen(file: AlertsFile?, now: LocalDateTime): Set<String> =
        valid(file, now).filter { it.urgent }.map { key(it, now) }.toSet()

    /** Tiene solo le chiavi di avvisi ancora nel file: così l'elenco salvato non cresce per sempre. */
    fun prune(keys: Set<String>, file: AlertsFile?): Set<String> {
        val ids = file?.alerts.orEmpty().map { "${it.id}@" }
        return keys.filter { k -> ids.any { k.startsWith(it) } }.toSet()
    }

    private val DAY_TIME = DateTimeFormatter.ofPattern("d/M HH:mm")

    /** "aggiornato 13/11 15:14" */
    fun updatedLabel(a: Alert): String = a.modifiedTime?.let { "aggiornato " + it.format(DAY_TIME) } ?: ""

    /** "Treni in sciopero 08:30–16:59 · 20:00–23:59" (solo scioperi con fasce lette). */
    fun strikeLabel(a: Alert): String? =
        a.trainStrikeBands.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { it.replace("-", "–") }
            ?.let { "Treni in sciopero $it" }
}
