package io.github.edoppiap.orarilido

import android.Manifest
import android.content.pm.ApplicationInfo
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.edoppiap.orarilido.location.LocationSource
import io.github.edoppiap.orarilido.schedule.Direction
import io.github.edoppiap.orarilido.ui.DayScreen
import io.github.edoppiap.orarilido.ui.InfoPage
import io.github.edoppiap.orarilido.ui.InfoScreen
import io.github.edoppiap.orarilido.ui.MainScreen
import io.github.edoppiap.orarilido.ui.MainViewModel
import io.github.edoppiap.orarilido.ui.theme.OrariTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
                        onExitGo = viewModel::exitGo,
                        onPinStop = viewModel::togglePinnedStop,
                        onMenu = { infoPage = InfoPage.MENU },
                        overlayOpen = day != null || infoPage != null,
                    )
                    // Giornata: entra da destra. "lastDay" la tiene disegnata mentre esce.
                    var lastDay by remember { mutableStateOf(day) }
                    if (day != null) lastDay = day
                    AnimatedVisibility(
                        visible = day != null,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut(),
                    ) {
                        lastDay?.let {
                            DayScreen(
                                it,
                                onBack = viewModel::closeDay,
                                onShiftDay = viewModel::shiftDay,
                                onDirection = viewModel::setDayDirection,
                            )
                        }
                    }

                    // Menu: entra da sinistra (dove sta il pulsante ☰) e lì ritorna.
                    var lastPage by remember { mutableStateOf(infoPage) }
                    if (infoPage != null) lastPage = infoPage
                    AnimatedVisibility(
                        visible = infoPage != null,
                        enter = slideInHorizontally { -it } + fadeIn(),
                        exit = slideOutHorizontally { -it } + fadeOut(),
                    ) {
                        // Tra le pagine del menu: avanti = da destra, indietro = verso destra.
                        AnimatedContent(
                            targetState = lastPage ?: InfoPage.MENU,
                            transitionSpec = {
                                val forward = initialState == InfoPage.MENU
                                (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { if (forward) -it / 3 else it } + fadeOut())
                            },
                            label = "infoPages",
                        ) { page ->
                            InfoScreen(page, onNavigate = { infoPage = it }, debugActions = debugActions(state.simulating) {
                                infoPage = null  // si chiude il menu per vedere la simulazione
                            })
                        }
                    }
                }
            }
        }

        holdFirstFrameUntilReady(askLocation = savedInstanceState == null)

        // Posizione: solo mentre l'app è visibile (STARTED) e solo con il permesso.
        // Se il permesso arriva dopo (dalla finestra di sistema), parte in quel momento.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationGranted.value = locationSource.hasPermission()  // può cambiare dalle Impostazioni
                // Se cambia il permesso o la frequenza voluta dalla modalità Go,
                // il GPS si riavvia con le impostazioni nuove.
                combine(locationGranted, viewModel.fastGps, ::Pair).collectLatest { (granted, fast) ->
                    if (granted) locationSource.updates(fast).collect { viewModel.onLocation(it) }
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
     * Voci in più nel menu, SOLO nella build di prova (debug): il simulatore
     * di viaggio per provare la modalità Go da casa. Nella build per la
     * famiglia (release) la lista è vuota e il menu non cambia.
     */
    private fun debugActions(simulating: Boolean, closeMenu: () -> Unit): List<Pair<String, () -> Unit>> {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return emptyList()
        return if (simulating) {
            listOf("Ferma la simulazione" to { viewModel.stopSimulation(); closeMenu() })
        } else {
            listOf(
                "Simula viaggio verso Ostia (da Vitinia)" to { viewModel.simulateRide(Direction.TO_OSTIA); closeMenu() },
                "Simula viaggio verso Roma (da Lido Centro)" to { viewModel.simulateRide(Direction.TO_ROMA); closeMenu() },
            )
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
