package io.github.edoppiap.orarilido.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// Un unico file di preferenze per tutta l'app.
private val Context.dataStore by preferencesDataStore(name = "prefs")

/**
 * Preferenze salvate sul telefono. Per privacy qui finiscono SOLO id di
 * stazioni (es. "acilia"), mai coordinate GPS.
 */
class StationPrefs(context: Context) {

    private val store = context.applicationContext.dataStore

    suspend fun lastStationId(): String? = store.data.first()[LAST_STATION]

    suspend fun setLastStationId(id: String) {
        store.edit { it[LAST_STATION] = id }
    }

    private companion object {
        val LAST_STATION = stringPreferencesKey("last_station")
    }
}
