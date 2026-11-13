package com.offline.stellarium

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos

/**
 * Canvas overlay: draws labels, Sun glow, Moon phase disc, planet dots,
 * and a highlight ring around the selected object.
 */
class LabelsOverlay(context: Context, private val renderer: SkyRenderer) : View(context) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = 0xFFB9C4D6.toInt()
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val conPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = 0xFF7C96C4.toInt()
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        color = 0xFF6F87A8.toInt()
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1E2A.toInt() }
    private val moonLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEED8.toInt() }
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF53D9A9.toInt()
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2253D9A9 }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val items = renderer.labels
        // first pass: discs (sun, moon, planets)
        for (it in items) {
            when (it.kind) {
                4 -> drawSun(canvas, it)
                5 -> drawMoon(canvas, it)
                1 -> drawPlanetDot(canvas, it)
            }
        }
        // second pass: text labels
        if (SkyState.showLabels) {
            for (it in items) {
                val p = when (it.kind) {
                    2 -> conPaint
                    3 -> cardPaint
                    else -> textPaint
                }
                p.color = it.color
                val ty = when (it.kind) {
                    4 -> it.y + it.sizePx + 24f
                    5 -> it.y + it.sizePx + 22f
                    1 -> it.y + 16f
                    else -> it.y + 26f
                }
                val tx = it.x - p.measureText(it.text) / 2
                canvas.drawText(it.text, tx, ty, p)
            }
        }
        // selection ring
        SkyState.selected?.let { sel ->
            for (it in items) {
                if (it.obj === sel) {
                    canvas.drawCircle(it.x, it.y, 30f, ringFill)
                    canvas.drawCircle(it.x, it.y, 30f, ringPaint)
                    break
                }
            }
        }
    }

    private fun drawSun(canvas: Canvas, it: LabelItem) {
        val r = it.sizePx
        sunPaint.shader = RadialGradient(it.x, it.y, r * 2.2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE9A8.toInt(), 0x00FFCE54.toInt()),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(it.x, it.y, r * 2.2f, sunPaint)
        sunPaint.shader = null
        sunPaint.color = 0xFFFFF6D8.toInt()
        canvas.drawCircle(it.x, it.y, r, sunPaint)
    }

    private fun drawMoon(canvas: Canvas, it: LabelItem) {
        val r = it.sizePx
        canvas.drawCircle(it.x, it.y, r + 3f, ringFill)
        // dark disc
        canvas.drawCircle(it.x, it.y, r, moonDark)
        // find the sun's on-screen direction to orient the terminator
        var sunAngle = 0f
        for (o in renderer.labels) {
            if (o.kind == 4) {
                sunAngle = Math.toDegrees(
                    kotlin.math.atan2(o.y - it.y, o.x - it.x)).toFloat()
                break
            }
        }
        canvas.save()
        canvas.translate(it.x, it.y)
        canvas.rotate(sunAngle)   // local +x now points at the sun
        // terminator ellipse semi-axis: |cos(i)| = |2p - 1|, signed a = r(2p-1)
        val a = (r * (2.0 * it.phase - 1.0)).toFloat()
        val path = Path()
        path.moveTo(0f, -r)
        if (it.waxing) {
            // lit side on +x
            path.arcTo(android.graphics.RectF(-r, -r, r, r), -90f, 180f, false) // right limb
            if (a >= 0f) {
                // gibbous: terminator from bottom to top along its left side
                path.arcTo(android.graphics.RectF(-a, -r, a, r), 90f, 180f, false)
            } else {
                // crescent: terminator along its right side, sliver between
                path.arcTo(android.graphics.RectF(a, -r, -a, r), 90f, -180f, false)
            }
        } else {
            // lit side on -x
            if (a >= 0f) {
                // gibbous: terminator left side first, then left limb
                path.arcTo(android.graphics.RectF(-a, -r, a, r), -90f, -180f, false)
                path.arcTo(android.graphics.RectF(-r, -r, r, r), 90f, 180f, false)
            } else {
                // crescent: left limb then terminator right side
                path.arcTo(android.graphics.RectF(-r, -r, r, r), -90f, -180f, false)
                path.arcTo(android.graphics.RectF(a, -r, -a, r), 90f, 180f, false)
            }
        }
        path.close()
        canvas.drawPath(path, moonLit)
        canvas.restore()
    }

    private fun drawPlanetDot(canvas: Canvas, it: LabelItem) {
        planetPaint.color = it.color
        canvas.drawCircle(it.x, it.y, 7f, planetPaint)
        planetPaint.alpha = 60
        canvas.drawCircle(it.x, it.y, 12f, planetPaint)
        planetPaint.alpha = 255
    }
}
