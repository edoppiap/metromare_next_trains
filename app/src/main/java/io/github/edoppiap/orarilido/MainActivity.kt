package io.github.edoppiap.orarilido

import android.Manifest
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.edoppiap.orarilido.location.LocationSource
import io.github.edoppiap.orarilido.ui.DayScreen
import io.github.edoppiap.orarilido.ui.InfoPage
import io.github.edoppiap.orarilido.ui.InfoScreen
import io.github.edoppiap.orarilido.ui.MainScreen
import io.github.edoppiap.orarilido.ui.MainViewModel
import io.github.edoppiap.orarilido.ui.theme.OrariTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val locationSource by lazy { LocationSource(this) }

    /** true se l'utente ci ha dato il permesso di posizione. */
    private val locationGranted = MutableStateFlow(false)

    // Finestra di sistema "Consenti l'accesso alla posizione?".
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationGranted.value = locationSource.hasPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OrariTheme {
                val state by viewModel.state.collectAsState()
                val day by viewModel.day.collectAsState()
                // Pagina del menu aperta (null = chiuso). "Saveable": sopravvive alla rotazione.
                var infoPage by rememberSaveable { mutableStateOf<InfoPage?>(null) }
                // La giornata si apre SOPRA la schermata principale: così la mappa
                // resta viva sotto e tornando indietro non va ricaricata.
                Box {
                    MainScreen(
                        state,
                        onOpenDay = viewModel::openDay,
                        onModeChange = viewModel::setMode,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onTravelMode = viewModel::setTravelMode,
                        onMenu = { infoPage = InfoPage.MENU },
                    )
                    day?.let {
                        DayScreen(
                            it,
                            onBack = viewModel::closeDay,
                            onShiftDay = viewModel::shiftDay,
                            onDirection = viewModel::setDayDirection,
                        )
                    }
                    infoPage?.let { InfoScreen(it, onNavigate = { page -> infoPage = page }) }
                }
            }
        }

        holdFirstFrameUntilReady(askLocation = savedInstanceState == null)

        // Posizione: solo mentre l'app è visibile (STARTED) e solo con il permesso.
        // Se il permesso arriva dopo (dalla finestra di sistema), parte in quel momento.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationGranted.value = locationSource.hasPermission()  // può cambiare dalle Impostazioni
                locationGranted.collectLatest { granted ->
                    if (granted) locationSource.updates().collect { viewModel.onLocation(it) }
                }
            }
        }

        // Aggiornamento: ogni 20 secondi e allo scoccare di ogni minuto (così
        // "tra 3 min" cambia puntuale), ma solo mentre l'app è visibile.
        // repeatOnLifecycle riparte da capo quando l'app torna in primo piano,
        // quindi c'è anche un aggiornamento immediato al ritorno.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refresh()
                    val toNextMinute = 60_000 - System.currentTimeMillis() % 60_000
                    delay(minOf(20_000, toNextMinute + 50))  // +50 ms: per sicurezza oltre il minuto
                }
            }
        }
    }

    /**
     * Non disegna il primo fotogramma finché gli orari non sono pronti
     * (pochi millisecondi): così l'utente non vede mai una schermata vuota
     * seguita da un "salto", ma direttamente gli orari.
     */
    private fun holdFirstFrameUntilReady(askLocation: Boolean) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!viewModel.state.value.ready) return false  // non ancora: salta il disegno
                content.viewTreeObserver.removeOnPreDrawListener(this)
                // Misura per noi: millisecondi dall'avvio del processo al primo fotogramma utile.
                val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                Log.i("OrariAvvio", "Orari visibili dopo $ms ms dall'avvio del processo")
                reportFullyDrawn()
                // Solo ORA, con gli orari già a schermo, chiediamo il permesso di posizione
                // (se manca). Android stesso smette di chiederlo dopo due rifiuti.
                if (askLocation && !locationSource.hasPermission()) {
                    content.post {
                        permissionRequest.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }
                return true
            }
        })
    }
}
