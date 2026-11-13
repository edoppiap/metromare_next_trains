package io.github.edoppiap.orarilido.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.edoppiap.orarilido.schedule.Direction
import kotlinx.coroutines.flow.first

// Un unico file di preferenze per tutta l'app.
private val Context.dataStore by preferencesDataStore(name = "prefs")

/** Cosa mostrare nella sezione "In primo piano" della tendina. */
enum class PrimaryMode { NEAREST, FAVORITES }

/** Come ti muovi verso la stazione: decide la grandezza dei cerchi sulla mappa. */
enum class TravelMode { WALK, BIKE }

/** Tutte le preferenze, lette in un colpo solo all'avvio. */
data class SavedPrefs(
    val lastStationId: String?,
    val favorites: Set<String>,
    val mode: PrimaryMode,
    val travelMode: TravelMode,
    /** Modalità Go: la fermata fissata l'ultima volta in ciascuna direzione. */
    val goStops: Map<Direction, String>,
)

/**
 * Preferenze salvate sul telefono. Per privacy qui finiscono SOLO id di
 * stazioni (es. "acilia") e scelte dell'utente, mai coordinate GPS.
 */
class StationPrefs(context: Context) {

    private val store = context.applicationContext.dataStore

    suspend fun load(): SavedPrefs {
        val p = store.data.first()
        return SavedPrefs(
            lastStationId = p[LAST_STATION],
            favorites = p[FAVORITES].orEmpty(),
            mode = p[MODE]?.let { runCatching { PrimaryMode.valueOf(it) }.getOrNull() } ?: PrimaryMode.NEAREST,
            travelMode = p[TRAVEL]?.let { runCatching { TravelMode.valueOf(it) }.getOrNull() } ?: TravelMode.WALK,
            goStops = Direction.entries.mapNotNull { d -> p[goStopKey(d)]?.let { d to it } }.toMap(),
        )
    }

    suspend fun setLastStationId(id: String) {
        store.edit { it[LAST_STATION] = id }
    }

    suspend fun setFavorites(ids: Set<String>) {
        store.edit { it[FAVORITES] = ids }
    }

    suspend fun setMode(mode: PrimaryMode) {
        store.edit { it[MODE] = mode.name }
    }

    suspend fun setTravelMode(mode: TravelMode) {
        store.edit { it[TRAVEL] = mode.name }
    }

    /** Modalità Go: ricorda (o dimentica, con null) la fermata fissata in quella direzione. */
    suspend fun setGoStop(direction: Direction, id: String?) {
        store.edit { if (id != null) it[goStopKey(direction)] = id else it.remove(goStopKey(direction)) }
    }

    private companion object {
        fun goStopKey(d: Direction) = stringPreferencesKey("go_stop_" + d.jsonKey)
        val LAST_STATION = stringPreferencesKey("last_station")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val MODE = stringPreferencesKey("primary_mode")
        val TRAVEL = stringPreferencesKey("travel_mode")
    }
}
