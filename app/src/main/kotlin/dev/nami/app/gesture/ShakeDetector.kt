package dev.nami.app.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.sqrt

/**
 * Детектор встряхивания устройства (Shake-to-shuffle).
 * Слушает акселерометр, вычисляет перегрузку (g-force) и фильтрует случайные
 * покачивания при ходьбе за счёт окна из нескольких пиков и кулдауна.
 */
class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isRunning = false
    private var thresholdG = 2.5f
    private var lastShakeTimestamp: Long = 0L
    private var shakeCount = 0
    private var lastPeakTimestamp: Long = 0L

    fun start(sensitivity: Float = 13.0f) {
        if (isRunning || accelerometer == null || sensorManager == null) return
        thresholdG = (sensitivity / SensorManager.GRAVITY_EARTH).coerceIn(1.8f, 4.0f)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        isRunning = false
        shakeCount = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return

        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        if (gForce > thresholdG) {
            if (now - lastPeakTimestamp > 500) {
                shakeCount = 0
            }
            lastPeakTimestamp = now
            shakeCount++

            if (shakeCount >= 2 && now - lastShakeTimestamp > 1500) {
                lastShakeTimestamp = now
                shakeCount = 0
                triggerHaptic()
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerHaptic() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(40)
            }
        }
    }
}
