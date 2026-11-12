package io.github.edoppiap.orarilido.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Posizione dell'utente tramite FusedLocationProvider (Google Play Services).
 *
 * Strategia per la velocità:
 *   1. subito l'ultima posizione nota (lastLocation): istantanea, magari un po' vecchia
 *   2. poi aggiornamenti continui, ma SOLO mentre l'app è visibile
 *      (chi raccoglie il Flow smette quando l'app va in background)
 *
 * Privacy: le coordinate restano in memoria, non vengono salvate né inviate.
 */
class LocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Flow di posizioni: prima l'ultima nota, poi una ogni ~10 s o ogni 15 m percorsi. */
    @SuppressLint("MissingPermission")  // chi chiama controlla prima hasPermission()
    fun updates(): Flow<GeoPoint> = callbackFlow {
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null) trySend(GeoPoint(last.latitude, last.longitude))
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
            .setMinUpdateDistanceMeters(15f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(GeoPoint(it.latitude, it.longitude)) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // Quando chi ascolta smette (app in background), il GPS si spegne.
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
