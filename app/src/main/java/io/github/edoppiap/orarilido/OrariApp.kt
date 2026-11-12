package io.github.edoppiap.orarilido

import android.app.Application
import io.github.edoppiap.orarilido.data.ScheduleRepository
import io.github.edoppiap.orarilido.data.StationPrefs
import io.github.edoppiap.orarilido.schedule.ScheduleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Primo codice nostro che gira quando il processo parte, prima ancora
 * dell'Activity. Lo usiamo per far partire SUBITO, in parallelo e fuori dal
 * thread principale, le due letture che servono alla prima schermata:
 *   1. l'orario (schedule.json)
 *   2. l'ultima stazione usata (DataStore)
 * Intanto Android crea l'Activity: quando lei chiede i dati, spesso sono già pronti.
 */
class OrariApp : Application() {

    // Scope delle operazioni "di app" (vivono quanto il processo).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var stationPrefs: StationPrefs
        private set

    /** L'orario, in caricamento in background. Si ottiene con `await()`. */
    lateinit var schedule: Deferred<ScheduleProvider>
        private set

    /** L'ultima stazione salvata (o null al primo avvio). */
    lateinit var savedStationId: Deferred<String?>
        private set

    override fun onCreate() {
        super.onCreate()
        stationPrefs = StationPrefs(this)
        schedule = appScope.async { ScheduleRepository(this@OrariApp).load() }
        savedStationId = appScope.async(Dispatchers.IO) { stationPrefs.lastStationId() }
    }
}
