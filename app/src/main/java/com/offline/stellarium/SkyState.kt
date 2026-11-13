package com.offline.stellarium

/** Shared mutable app state (read/written across UI and GL threads). */
object SkyState {
    @Volatile var timeMillis: Long = System.currentTimeMillis()
    @Volatile var timeSpeed: Long = 1L          // 0 = paused, 1 = real time, else multiplier
    @Volatile var latitudeDeg: Double = 39.9042
    @Volatile var longitudeDeg: Double = 116.4074

    @Volatile var sensorMode: Boolean = true    // true = point at sky, false = drag to look
    @Volatile var sensorPresent: Boolean = false

    @Volatile var showConstellations: Boolean = true
    @Volatile var showLabels: Boolean = true
    @Volatile var showAtmosphere: Boolean = true
    @Volatile var magLimit: Float = 6.0f

    @Volatile var selected: SkyObject? = null

    // drag-mode view center: azimuth (rad, from north, eastward) + altitude (rad)
    @Volatile var dragAz: Double = 0.0
    @Volatile var dragAlt: Double = 0.7
}

/** A selectable sky object. */
class SkyObject(
    val name: String,
    val type: String,            // "Star", "Planet", "Sun", "Moon", "Constellation"
    val raRad: Double,
    val decRad: Double,
    val mag: Double,
    val distanceText: String = "",
    val extraText: String = ""
)

/** A projected, drawable label for the overlay canvas. */
class LabelItem(
    val text: String,
    val x: Float, val y: Float,
    val kind: Int,               // 0 star, 1 planet, 2 constellation, 3 cardinal, 4 sun, 5 moon
    val obj: SkyObject?,
    val color: Int = 0xFFB9C4D6.toInt(),
    val phase: Double = 0.0,     // moon phase fraction 0..1
    val waxing: Boolean = true,
    val sizePx: Float = 0f       // disc radius for sun/moon
)
