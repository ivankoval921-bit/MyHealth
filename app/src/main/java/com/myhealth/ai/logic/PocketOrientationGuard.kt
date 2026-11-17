package com.myhealth.ai.logic

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

enum class DictationState {
    IDLE,
    LISTENING,
    PAUSED
}

enum class DictationTarget {
    SYMPTOM_INPUT,
    HISTORY_SEARCH
}

enum class PocketStopReason(val messageRu: String) {
    NONE(""),
    PROXIMITY_COVERED("Микрофон выключен: датчик приближения (телефон закрыт / убран в карман)"),
    UPSIDE_DOWN_OR_FACEDOWN("Микрофон выключен: телефон перевёрнут вниз (в карман / экраном вниз)")
}

object PocketOrientationMath {
    fun isAccelerometerUpsideDownOrFaceDown(ax: Float, ay: Float, az: Float): Boolean {
        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm < 4.0f) return false
        val ny = ay / norm
        val nz = az / norm
        // Upside-down portrait (top edge tilted steeply downward, e.g. sliding into pocket)
        val upsideDown = ny < -0.68f
        // Face-down on surface
        val faceDown = nz < -0.78f
        return upsideDown || faceDown
    }

    fun isProximityCovered(distanceCm: Float, maxRangeCm: Float): Boolean {
        val limit = minOf(maxRangeCm.coerceAtLeast(0.5f), 3.5f)
        return distanceCm >= 0f && distanceCm < limit
    }
}

/**
 * Monitors Proximity + Accelerometer + Gyroscope only while dictation is active.
 * Automatically stops the microphone if the phone is placed in a pocket (proximity covered)
 * or flipped upside-down / face-down cleanly.
 */
class PocketOrientationGuard(
    context: Context,
    private val onAutoStop: (PocketStopReason) -> Unit
) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proxSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var active = false
    private var startedAtMs = 0L
    private var nearSinceMs = 0L
    private var flippedSinceMs = 0L
    private var lastGyroMag = 0f

    fun start() {
        stop()
        val mgr = sensorManager ?: return
        active = true
        startedAtMs = System.currentTimeMillis()
        nearSinceMs = 0L
        flippedSinceMs = 0L
        lastGyroMag = 0f
        proxSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        active = false
        nearSinceMs = 0L
        flippedSinceMs = 0L
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - startedAtMs < STARTUP_GRACE_MS) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values.getOrElse(0) { 0f }
                val gy = event.values.getOrElse(1) { 0f }
                val gz = event.values.getOrElse(2) { 0f }
                lastGyroMag = abs(gx) + abs(gy) + abs(gz)
            }
            Sensor.TYPE_PROXIMITY -> {
                val dist = event.values.getOrElse(0) { return }
                val maxR = proxSensor?.maximumRange ?: 5f
                if (PocketOrientationMath.isProximityCovered(dist, maxR)) {
                    if (nearSinceMs == 0L) nearSinceMs = now
                    if (now - nearSinceMs >= PROX_HOLD_MS) {
                        trigger(PocketStopReason.PROXIMITY_COVERED)
                    }
                } else {
                    nearSinceMs = 0L
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrElse(0) { return }
                val ay = event.values.getOrElse(1) { return }
                val az = event.values.getOrElse(2) { return }
                if (PocketOrientationMath.isAccelerometerUpsideDownOrFaceDown(ax, ay, az)) {
                    if (flippedSinceMs == 0L) flippedSinceMs = now
                    val requiredHold = if (lastGyroMag > 1.5f) 420L else FLIP_HOLD_MS
                    if (now - flippedSinceMs >= requiredHold) {
                        trigger(PocketStopReason.UPSIDE_DOWN_OR_FACEDOWN)
                    }
                } else {
                    flippedSinceMs = 0L
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun trigger(reason: PocketStopReason) {
        if (!active) return
        stop()
        onAutoStop(reason)
    }

    companion object {
        private const val STARTUP_GRACE_MS = 1200L
        private const val PROX_HOLD_MS = 400L
        private const val FLIP_HOLD_MS = 650L
    }
}
