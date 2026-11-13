package io.github.edoppiap.orarilido.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.edoppiap.orarilido.places.PlaceKind
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
    /** Le scelte della pagina Impostazioni. */
    val settings: AppSettings,
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
            settings = AppSettings().let { def ->
                AppSettings(
                    appTheme = p[APP_THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: def.appTheme,
                    mapTheme = p[MAP_THEME]?.let { runCatching { MapTheme.valueOf(it) }.getOrNull() } ?: def.mapTheme,
                    placesOn = p[PLACES_ON] ?: def.placesOn,
                    // Categorie salvate per nome: un nome sconosciuto (versione futura) si ignora.
                    placeKinds = p[PLACE_KINDS]?.mapNotNull { n -> PlaceKind.entries.firstOrNull { it.name == n } }?.toSet()
                        ?: def.placeKinds,
                    circlesOn = p[CIRCLES_ON] ?: def.circlesOn,
                    routeOn = p[ROUTE_ON] ?: def.routeOn,
                    walkLevel = (p[WALK_LEVEL] ?: def.walkLevel).coerceIn(0, 4),
                    bikeLevel = (p[BIKE_LEVEL] ?: def.bikeLevel).coerceIn(0, 4),
                    fadeMissed = p[FADE_MISSED] ?: def.fadeMissed,
                    goOn = p[GO_ON] ?: def.goOn,
                )
            },
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

    /** Salva tutte le impostazioni (sono poche: si riscrivono tutte a ogni cambio). */
    suspend fun setSettings(s: AppSettings) {
        store.edit {
            it[APP_THEME] = s.appTheme.name
            it[MAP_THEME] = s.mapTheme.name
            it[PLACES_ON] = s.placesOn
            it[PLACE_KINDS] = s.placeKinds.map { k -> k.name }.toSet()
            it[CIRCLES_ON] = s.circlesOn
            it[ROUTE_ON] = s.routeOn
            it[WALK_LEVEL] = s.walkLevel
            it[BIKE_LEVEL] = s.bikeLevel
            it[FADE_MISSED] = s.fadeMissed
            it[GO_ON] = s.goOn
        }
    }

    /** Impostazioni → "Cancella preferite e fermate salvate". */
    suspend fun clearFavoritesAndGoStops() {
        store.edit {
            it.remove(FAVORITES)
            Direction.entries.forEach { d -> it.remove(goStopKey(d)) }
        }
    }

    private companion object {
        fun goStopKey(d: Direction) = stringPreferencesKey("go_stop_" + d.jsonKey)
        val LAST_STATION = stringPreferencesKey("last_station")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val MODE = stringPreferencesKey("primary_mode")
        val TRAVEL = stringPreferencesKey("travel_mode")
        // Impostazioni
        val APP_THEME = stringPreferencesKey("app_theme")
        val MAP_THEME = stringPreferencesKey("map_theme")
        val PLACES_ON = booleanPreferencesKey("places_on")
        val PLACE_KINDS = stringSetPreferencesKey("place_kinds")
        val CIRCLES_ON = booleanPreferencesKey("circles_on")
        val ROUTE_ON = booleanPreferencesKey("route_on")
        val WALK_LEVEL = intPreferencesKey("walk_level")
        val BIKE_LEVEL = intPreferencesKey("bike_level")
        val FADE_MISSED = booleanPreferencesKey("fade_missed")
        val GO_ON = booleanPreferencesKey("go_on")
    }
}
