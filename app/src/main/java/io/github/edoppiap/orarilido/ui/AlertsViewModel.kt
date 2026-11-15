package io.github.edoppiap.orarilido.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.edoppiap.orarilido.OrariApp
import io.github.edoppiap.orarilido.alerts.Alert
import io.github.edoppiap.orarilido.alerts.AlertRules
import io.github.edoppiap.orarilido.alerts.AlertsFile
import io.github.edoppiap.orarilido.alerts.AlertsView
import io.github.edoppiap.orarilido.data.AlertsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Avvisi Cotral nel menu: tiene il file, cosa hai già visto/tolto, e
 * calcola cosa mostrare (AlertRules). Separato da MainViewModel: gli
 * avvisi non c'entrano con orari e mappa, e non devono MAI rallentare
 * l'apertura (tutto avviene dopo, in background).
 */
class AlertsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AlertsRepository(app)
    private val prefs = (app as OrariApp).stationPrefs

    private var file: AlertsFile? = null
    private var seen: Set<String> = emptySet()
    private var dismissed: Set<String> = emptySet()
    private var loaded = false

    /** Ultimo tentativo di scaricare (per non riscaricare a ogni ritorno in primo piano). */
    private var lastDownload = 0L
    private var downloading = false

    private val _view = MutableStateFlow(AlertsView())
    val view: StateFlow<AlertsView> = _view

    init {
        // Prima la copia salvata (subito, anche senza rete), poi si scarica.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file = repo.loadCached()
                prefs.loadAlertKeys().let { (s, d) -> seen = s; dismissed = d }
            }
            loaded = true
            recompute()
            onForeground()
        }
    }

    /**
     * App in primo piano: scarica gli avvisi se l'ultima volta è stata più di
     * [MIN_INTERVAL_MS] fa. Lo script aggiorna il file ogni 30 minuti:
     * scaricarlo più spesso non servirebbe.
     */
    fun onForeground() {
        if (!loaded || downloading) return
        val now = SystemClock.elapsedRealtime()
        if (lastDownload != 0L && now - lastDownload < MIN_INTERVAL_MS) return
        downloading = true
        lastDownload = now
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) { repo.download() }
            downloading = false
            if (fresh != null) {
                file = fresh
                // Si dimenticano le chiavi di avvisi che non ci sono più.
                seen = AlertRules.prune(seen, fresh)
                dismissed = AlertRules.prune(dismissed, fresh)
                save()
            }
            recompute()
        }
    }

    /** Ricalcolo col passare del tempo (fine di un avviso, vigilia di uno sciopero). */
    fun tick() {
        if (loaded) recompute()
    }

    /** Menu aperto: gli avvisi urgenti di adesso sono "visti", il pallino si spegne. */
    fun onMenuOpened() {
        if (!loaded) return
        val keys = AlertRules.keysToMarkSeen(file, LocalDateTime.now())
        if (keys.all { it in seen }) return
        seen = seen + keys
        save()
        recompute()
    }

    /** X su un avviso urgente: sparisce dalla cima del menu (resta nelle Notizie). */
    fun dismiss(alert: Alert) {
        dismissed = dismissed + AlertRules.key(alert, LocalDateTime.now())
        save()
        recompute()
    }

    private fun recompute() {
        _view.value = AlertRules.view(file, LocalDateTime.now(), seen, dismissed)
    }

    private fun save() {
        val s = seen
        val d = dismissed
        viewModelScope.launch(Dispatchers.IO) { prefs.setAlertKeys(s, d) }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
