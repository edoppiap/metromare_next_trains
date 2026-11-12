package io.github.edoppiap.orarilido.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

/**
 * Direzione in cui punta il telefono, in gradi dal nord geografico (0 = nord,
 * 90 = est), oppure null se il telefono non ha il sensore.
 *
 * Usa il sensore "rotation vector" (bussola + giroscopio). Resta acceso SOLO
 * mentre l'app è visibile, per non consumare batteria in background.
 * [near] serve a correggere la differenza tra nord magnetico e geografico
 * (a Roma ~4°); le coordinate restano in memoria.
 */
@Composable
fun rememberHeading(near: GeoPoint?): State<Float?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val heading = remember { mutableStateOf<Float?>(null) }

    // Declinazione magnetica: si calcola una volta per posizione approssimativa.
    val declination = remember(near?.let { (it.lat * 10).toInt() to (it.lon * 10).toInt() }) {
        near?.let {
            GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination
        } ?: 0f
    }

    DisposableEffect(lifecycleOwner, declination) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Se lo schermo è ruotato (orizzontale), riallinea gli assi.
                @Suppress("DEPRECATION")
                val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.rotation
                val (x, y) = when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val degrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360f) % 360f

                // Filtro: aggiorna solo per variazioni di almeno 2°, così la mappa
                // non ridisegna di continuo per tremolii minimi.
                val old = heading.value
                if (old == null || angleDiff(old, degrees) >= 2f) heading.value = degrees
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // Acceso in primo piano, spento in background.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                Lifecycle.Event.ON_STOP -> sensorManager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }
    return heading
}

/** Differenza tra due angoli, da 0 a 180°. */
private fun angleDiff(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}
