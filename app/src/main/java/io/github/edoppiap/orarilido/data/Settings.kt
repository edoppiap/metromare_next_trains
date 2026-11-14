package io.github.edoppiap.orarilido.data

import io.github.edoppiap.orarilido.places.PlaceKind

/** Tema dell'app: come il telefono, oppure sempre chiaro / sempre scuro. */
enum class AppTheme { SYSTEM, LIGHT, DARK }

/**
 * Tema della mappa, indipendente da quello dell'app:
 * AUTO = chiara di giorno e scura dopo il tramonto; APP = segue il tema dell'app.
 */
enum class MapTheme { AUTO, APP, LIGHT, DARK }

/**
 * Le scelte della pagina Impostazioni. I valori predefiniti sono quelli con cui
 * l'app si comportava prima che le impostazioni esistessero: chi non le apre
 * non vede nessuna differenza (tranne "treni sbiaditi", che è nuova e parte spenta).
 */
data class AppSettings(
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val mapTheme: MapTheme = MapTheme.AUTO,
    /** Punti di interesse sulla mappa: interruttore generale... */
    val placesOn: Boolean = true,
    /** ...e categorie scelte (restano ricordate anche a interruttore spento). */
    val placeKinds: Set<PlaceKind> = PlaceKind.entries.toSet(),
    /** Cerchi 5/10/15 minuti attorno a te. */
    val circlesOn: Boolean = true,
    /** Percorso suggerito fino alla stazione. */
    val routeOn: Boolean = true,
    /** Velocità a piedi e in bici: indice in [Speeds] (0 = doppia lumaca … 4 = doppia lepre). */
    val walkLevel: Int = Speeds.NORMAL,
    val bikeLevel: Int = Speeds.NORMAL,
    /** Sbiadisci nelle schede i treni che non fai in tempo a prendere. */
    val fadeMissed: Boolean = false,
) {
    /** Metri al minuto con il mezzo scelto e la velocità impostata per lui. */
    fun metersPerMinute(mode: TravelMode): Double =
        if (mode == TravelMode.BIKE) Speeds.bikeMetersPerMinute(bikeLevel) else Speeds.walkMetersPerMinute(walkLevel)
}

/**
 * Le cinque velocità, come in VLC: doppia lumaca, lumaca, normale, lepre, doppia lepre.
 * Sono medie (soste ai semafori comprese), in km/h. "Normale" = 4,8 km/h a piedi
 * (80 m/min) e 15 km/h in bici (250 m/min), le velocità usate prima delle impostazioni.
 */
object Speeds {
    const val NORMAL = 2
    val WALK_KMH = listOf(3.4, 4.1, 4.8, 5.5, 6.2)
    val BIKE_KMH = listOf(10.0, 12.5, 15.0, 18.5, 22.0)  // doppia lepre a 22 (scelta dell'utente)

    fun walkMetersPerMinute(level: Int) = kmhToMetersPerMinute(WALK_KMH[level.coerceIn(0, 4)])
    fun bikeMetersPerMinute(level: Int) = kmhToMetersPerMinute(BIKE_KMH[level.coerceIn(0, 4)])

    private fun kmhToMetersPerMinute(kmh: Double) = kmh * 1000.0 / 60.0
}
