package com.cardiopulse.app.core.ppg

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Monitors hand stability via linear acceleration (or high-pass filtered accelerometer)
 * during PPG capture to catch motion artifacts before they corrupt heart rate readings.
 */
class MotionMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private val magnitudeRing = DoubleArray(25)
    private var ringIndex = 0
    private var ringCount = 0

    @Volatile
    var currentMotionRms: Double = 0.0
        private set

    fun start() {
        reset()
        val sensor = linearSensor ?: accelSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        ringIndex = 0
        ringCount = 0
        currentMotionRms = 0.0
        gravityInitialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax: Float
        val ay: Float
        val az: Float

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            ax = event.values[0]
            ay = event.values[1]
            az = event.values[2]
        } else {
            // Isolate linear motion with exponential low-pass gravity filter
            val alpha = 0.85f
            if (!gravityInitialized) {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                gravityInitialized = true
            } else {
                gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]
            }
            ax = event.values[0] - gravity[0]
            ay = event.values[1] - gravity[1]
            az = event.values[2] - gravity[2]
        }

        val magSq = (ax * ax + ay * ay + az * az).toDouble()
        magnitudeRing[ringIndex] = magSq
        ringIndex = (ringIndex + 1) % magnitudeRing.size
        if (ringCount < magnitudeRing.size) ringCount++

        var sumSq = 0.0
        for (i in 0 until ringCount) {
            sumSq += magnitudeRing[i]
        }
        currentMotionRms = sqrt(sumSq / ringCount.coerceAtLeast(1))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
