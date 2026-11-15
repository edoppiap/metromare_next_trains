package io.github.edoppiap.orarilido.data

import android.content.Context
import io.github.edoppiap.orarilido.alerts.AlertsFile
import io.github.edoppiap.orarilido.alerts.AlertsParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Avvisi Cotral: scarica avvisi.json da GitHub e ne tiene l'ultima copia
 * sul telefono (così senza rete si vede l'ultimo stato noto).
 *
 * Privacy: è una semplice richiesta del file, senza parametri. Non contiene
 * la posizione né altri dati; GitHub vede solo l'indirizzo IP, come per
 * qualunque pagina web.
 *
 * Le funzioni bloccano: vanno chiamate fuori dal thread principale.
 */
class AlertsRepository(context: Context) {

    private val cache = File(context.filesDir, "avvisi.json")

    /** Ultima copia salvata, o null se non c'è (primo avvio) o è rovinata. */
    fun loadCached(): AlertsFile? =
        runCatching { AlertsParser.parse(cache.readText()) }.getOrNull()

    /**
     * Scarica il file. Se è valido lo salva e lo restituisce; se la rete
     * manca o il file non è valido restituisce null (e la copia salvata resta).
     */
    fun download(): AlertsFile? = runCatching {
        val conn = URL(URL_AVVISI).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.useCaches = false
            if (conn.responseCode != 200) return null
            // Il file è di pochi kB: oltre 256 kB qualcosa non va.
            val bytes = conn.inputStream.use { it.readNBytesCompat(256 * 1024) } ?: return null
            val text = bytes.toString(Charsets.UTF_8)
            val parsed = AlertsParser.parse(text) ?: return null
            // Scrittura "sicura": prima in un file temporaneo, poi si rinomina.
            val tmp = File(cache.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(cache)
            parsed
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    companion object {
        /** Ramo "dati" del repository pubblico, aggiornato dallo script ogni 30 minuti. */
        const val URL_AVVISI =
            "https://raw.githubusercontent.com/edoppiap/metromare_next_trains/dati/avvisi.json"
    }
}

/** Legge al massimo [max] byte; null se il contenuto è più lungo. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
    return out.toByteArray()
}
