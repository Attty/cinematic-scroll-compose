package dev.vlad.cinematicscroll

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.exp

/**
 * Tilt offset built by integrating raw gyroscope angular velocity with an
 * exponential spring back to zero. Unlike Euler angles from the rotation
 * vector, this is stable in any phone pose (no gimbal lock when the phone
 * is held upright) and naturally re-centers after a second of stillness.
 * x = accumulated turn around the screen's vertical axis (left/right),
 * y = around the horizontal axis (toward/away), both in radians.
 */
@Composable
internal fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            private var lastTimestamp = 0L
            private var x = 0f
            private var y = 0f

            override fun onSensorChanged(event: SensorEvent) {
                if (lastTimestamp != 0L) {
                    val dt = (event.timestamp - lastTimestamp) * 1e-9f
                    if (dt in 0f..0.5f) {
                        x += event.values[1] * dt
                        y += event.values[0] * dt
                        val decay = exp(-dt * 2.0f)
                        x = (x * decay).coerceIn(-0.35f, 0.35f)
                        y = (y * decay).coerceIn(-0.35f, 0.35f)
                        tilt.value = Offset(x, y)
                    }
                }
                lastTimestamp = event.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return tilt
}
