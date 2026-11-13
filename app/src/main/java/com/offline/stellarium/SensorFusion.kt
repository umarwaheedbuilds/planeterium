package com.offline.stellarium

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Rotation-vector sensor -> device orientation matrix (remapped for landscape). */
class SensorFusion(
    context: Context,
    private val onOrientation: (FloatArray) -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(16)
    private val remapMatrix = FloatArray(16)

    val hasSensor: Boolean get() = rotationSensor != null

    fun register() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun unregister() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val landscape = appContext.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_Z,
                SensorManager.AXIS_X,
                remapMatrix
            )
        } else {
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_Z,
                SensorManager.AXIS_Y,
                remapMatrix
            )
        }
        onOrientation(remapMatrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
