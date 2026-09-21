package dev.primeremote.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns the phone's accelerometer into a pair of -1..1 axes, so a layout can steer by
 * tilting. Values are low-pass filtered because raw accelerometer data is far too jittery
 * to drive motors with.
 */
class TiltProvider(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = sensor != null

    private val _x = MutableStateFlow(0f)
    val x: StateFlow<Float> = _x.asStateFlow()

    private val _y = MutableStateFlow(0f)
    val y: StateFlow<Float> = _y.asStateFlow()

    private var filteredX = 0f
    private var filteredY = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            // Held in landscape: the device's X axis points up the screen and Y across it.
            val rawX = -event.values[1] / TILT_RANGE
            val rawY = -event.values[0] / TILT_RANGE
            filteredX += (rawX - filteredX) * SMOOTHING
            filteredY += (rawY - filteredY) * SMOOTHING
            _x.value = filteredX.coerceIn(-1f, 1f)
            _y.value = filteredY.coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val s = sensor ?: return
        sensorManager?.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        _x.value = 0f
        _y.value = 0f
        filteredX = 0f
        filteredY = 0f
    }

    private companion object {
        /** Tilting this many m/s² away from flat counts as full deflection (about 35°). */
        const val TILT_RANGE = 5.6f
        const val SMOOTHING = 0.25f
    }
}
