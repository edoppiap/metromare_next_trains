package io.github.edoppiap.orarilido.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// Un unico file di preferenze per tutta l'app.
private val Context.dataStore by preferencesDataStore(name = "prefs")

/** Cosa mostrare nella sezione "In primo piano" della tendina. */
enum class PrimaryMode { NEAREST, FAVORITES }

/** Tutte le preferenze, lette in un colpo solo all'avvio. */
data class SavedPrefs(
    val lastStationId: String?,
    val favorites: Set<String>,
    val mode: PrimaryMode,
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

    private companion object {
        val LAST_STATION = stringPreferencesKey("last_station")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val MODE = stringPreferencesKey("primary_mode")
    }
}
