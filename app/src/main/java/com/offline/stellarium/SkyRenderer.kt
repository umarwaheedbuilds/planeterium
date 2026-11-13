package com.offline.stellarium

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * OpenGL sky renderer: atmosphere gradient, Milky Way, 9k stars, constellation
 * lines. Also projects objects to screen coordinates each frame for the
 * label overlay and tap identification.
 */
class SkyRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val catalog = StarCatalog(context)
    private var starProg = 0
    private var lineProg = 0
    private var skyProg = 0

    private val lineVertCount: Int
    private val lineBuffer: java.nio.FloatBuffer
    private val skyBuffer: java.nio.FloatBuffer

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var sensorRotation: FloatArray? = null

    private var fovDeg = 60f
    private var w = 1
    private var h = 1

    @Volatile var labels: List<LabelItem> = emptyList()
        private set
    private var tapCandidates: List<Float> = emptyList()  // x, y, objIndex into tapObjects
    private var tapObjects: List<SkyObject> = emptyList()

    init {
        var n = 0
        for (c in Constellations.all) n += c.linePairs.size
        lineVertCount = n
        val arr = FloatArray(n * 2)
        var p = 0
        for (c in Constellations.all) {
            for (idx in c.linePairs) {
                arr[p++] = c.starRaDec[idx * 2]
                arr[p++] = c.starRaDec[idx * 2 + 1]
            }
        }
        lineBuffer = floatBuf(arr)
        skyBuffer = floatBuf(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    }

    fun setSensorRotation(m: FloatArray) {
        val copy = FloatArray(16)
        System.arraycopy(m, 0, copy, 0, 16)
        sensorRotation = copy
    }

    fun zoom(factor: Float) {
        fovDeg = (fovDeg / factor).coerceIn(1.5f, 130f)
    }

    fun drag(dx: Float, dy: Float) {
        SkyState.dragAz = (SkyState.dragAz + dx * 0.0052) % (2 * Math.PI)
        SkyState.dragAlt = (SkyState.dragAlt + dy * 0.0052).coerceIn(-1.45, 1.55)
    }

    /** Center drag-mode view on an object given its ra/dec. */
    fun centerOn(raRad: Double, decRad: Double, timeMillis: Long) {
        val azalt = AstroMath.altAz(raRad, decRad, SkyState.latitudeDeg,
            AstroMath.lstRadians(timeMillis, SkyState.longitudeDeg))
        SkyState.dragAz = azalt[0]
        SkyState.dragAlt = azalt[1]
    }

    /** Copy current pointing direction into drag state (sensor -> handoff). */
    fun adoptCurrentCenter() {
        val dir = viewCenterDir()
        SkyState.dragAz = atan2(dir[0], dir[1])
        SkyState.dragAlt = asin(max(-1.0, minOf(1.0, dir[2])))
    }

    private fun viewCenterDir(): DoubleArray {
        // forward = -(row 2 of view matrix), in ENU
        return doubleArrayOf(-viewMatrix[8].toDouble(), -viewMatrix[9].toDouble(),
            -viewMatrix[10].toDouble())
    }

    fun findObjectAt(x: Float, y: Float, maxDist: Float = 56f): SkyObject? {
        var best: SkyObject? = null
        var bestD = maxDist
        var i = 0
        while (i + 2 < tapCandidates.size) {
            val dx = tapCandidates[i] - x
            val dy = tapCandidates[i + 1] - y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < bestD) {
                bestD = d
                best = tapObjects.getOrNull(tapCandidates[i + 2].toInt())
            }
            i += 3
        }
        return best
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.006f, 0.01f, 0.026f, 1f)
        starProg = buildProgram(Shaders.STAR_VERT, Shaders.STAR_FRAG)
        lineProg = buildProgram(Shaders.LINE_VERT, Shaders.LINE_FRAG)
        skyProg = buildProgram(Shaders.SKY_VERT, Shaders.SKY_FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        w = max(1, width); h = max(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t = SkyState.timeMillis
        val latR = Math.toRadians(SkyState.latitudeDeg)
        val lst = AstroMath.lstRadians(t, SkyState.longitudeDeg)

        updateViewMatrix()
        updateProj()

        // Sun altitude drives atmosphere + star visibility
        val sun = AstroMath.sunPosition(t)
        val sunAzAlt = AstroMath.altAz(sun[0], sun[1], SkyState.latitudeDeg, lst)
        val sunAlt = sunAzAlt[1]
        val daylight = AstroMath.smoothstep(-0.18, -0.02, sunAlt)
        val twilight = AstroMath.smoothstep(-0.30, -0.10, sunAlt) * (1 - daylight)
        val starAlpha = 1.0 - AstroMath.smoothstep(-0.14, 0.01, sunAlt)

        // --- atmosphere ---
        if (SkyState.showAtmosphere) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(skyProg)
            val aXY = GLES20.glGetAttribLocation(skyProg, "aXY")
            GLES20.glEnableVertexAttribArray(aXY)
            GLES20.glVertexAttribPointer(aXY, 2, GLES20.GL_FLOAT, false, 0, skyBuffer)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(skyProg, "uDaylight"), daylight.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(skyProg, "uTwilight"), twilight.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glDisableVertexAttribArray(aXY)
        } else {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }


        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_VERTEX_PROGRAM_POINT_SIZE)

        drawStarBuffer(catalog.mwBuffer, catalog.mwCount, lst, latR, starAlpha * 0.30, 0.55f, 8f)
        drawStarBuffer(catalog.starBuffer, catalog.starCount, lst, latR, starAlpha, 1f, SkyState.magLimit)

        if (SkyState.showConstellations) drawLines(lst, latR)

        updateLabels(t, lst, sun, sunAlt)
    }

    private fun drawStarBuffer(buf: java.nio.FloatBuffer, count: Int, lst: Double, latR: Double,
                               alpha: Double, sizeFactor: Float, maxMag: Float) {
        if (alpha <= 0.003) return
        GLES20.glUseProgram(starProg)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(starProg, "uViewProj"), 1, false, vp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uLst"), lst.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uLat"), latR.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uPixelH"), h / 60f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uFovScale"), (60f / fovDeg).coerceIn(0.3f, 30f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uMaxMag"), maxMag)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uAlpha"), alpha.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(starProg, "uSizeFactor"), sizeFactor)
        val aStar = GLES20.glGetAttribLocation(starProg, "aStar")
        GLES20.glEnableVertexAttribArray(aStar)
        GLES20.glVertexAttribPointer(aStar, 4, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aStar)
    }

    private fun drawLines(lst: Double, latR: Double) {
        GLES20.glUseProgram(lineProg)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProg, "uViewProj"), 1, false, vp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProg, "uLst"), lst.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProg, "uLat"), latR.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProg, "uAlpha"), 0.30f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(lineProg, "uColor"), 0.25f, 0.42f, 0.62f)
        val aPos = GLES20.glGetAttribLocation(lineProg, "aRaDec")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, lineBuffer)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVertCount)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun updateViewMatrix() {
        val rot = if (SkyState.sensorMode) sensorRotation else null
        if (rot != null) {
            System.arraycopy(rot, 0, viewMatrix, 0, 16)
        } else {
            // drag mode: build camera basis from az/alt view center
            val f = AstroMath.enu(SkyState.dragAz, SkyState.dragAlt)  // forward (E,N,U)
            // right = normalize(cross(forward, worldUp)), worldUp = (0,0,1)
            var rx = f[1]; var ry = -f[0]
            val len = kotlin.math.sqrt(rx * rx + ry * ry)
            if (len < 1e-6) { rx = 1.0; ry = 0.0 } else { rx /= len; ry /= len }
            val cx0 = rx; val cx1 = ry; val cx2 = 0.0
            val cz0 = -f[0]; val cz1 = -f[1]; val cz2 = -f[2]       // backward
            val cy0 = cz1 * cx2 - cz2 * cx1                          // up = cZ x cX
            val cy1 = cz2 * cx0 - cz0 * cx2
            val cy2 = cz0 * cx1 - cz1 * cx0
            val m = viewMatrix
            m[0] = cx0.toFloat(); m[4] = cx1.toFloat(); m[8] = cx2.toFloat();  m[12] = 0f
            m[1] = cy0.toFloat(); m[5] = cy1.toFloat(); m[9] = cy2.toFloat();  m[13] = 0f
            m[2] = cz0.toFloat(); m[6] = cz1.toFloat(); m[10] = cz2.toFloat(); m[14] = 0f
            m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        }
    }

    private fun updateProj() {
        val t = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        Matrix.frustumM(projMatrix, 0, -t * w / h, t * w / h, -t, t, 0.1f, 10f)
    }

    /** Project an ENU unit direction to screen px, or null if behind camera. */
    private fun project(e: Double, n: Double, u: Double): FloatArray? {
        val m = viewMatrix
        val cx = (m[0] * e + m[4] * n + m[8] * u).toDouble()
        val cy = (m[1] * e + m[5] * n + m[9] * u).toDouble()
        val cz = (m[2] * e + m[6] * n + m[10] * u).toDouble()
        if (cz > -0.05) return null
        val tt = tan(Math.toRadians(fovDeg / 2.0))
        val ndcX = cx / (-cz * tt * w / h)
        val ndcY = cy / (-cz * tt)
        return floatArrayOf(((ndcX + 1) / 2 * w).toFloat(), ((1 - ndcY) / 2 * h).toFloat())
    }

    private fun projectRaDec(raRad: Double, decRad: Double, lst: Double): FloatArray? {
        val lat = Math.toRadians(SkyState.latitudeDeg)
        val ha = lst - raRad
        val sd = sin(decRad); val cd = cos(decRad)
        val e = -cd * sin(ha)
        val n = sd * cos(lat) - cd * sin(lat) * cos(ha)
        val u = sd * sin(lat) + cd * cos(lat) * cos(ha)
        return project(e, n, u)
    }

    private fun updateLabels(t: Long, lst: Double, sun: DoubleArray, sunAlt: Double) {
        val out = ArrayList<LabelItem>(128)
        val candX = ArrayList<Float>(128)
        val candObj = ArrayList<SkyObject>(64)
        val showLabels = SkyState.showLabels
        val wideView = fovDeg > 35f

        fun addCandidate(x: Float, y: Float, obj: SkyObject) {
            candX.add(x); candX.add(y); candX.add(candObj.size.toFloat())
            candObj.add(obj)
        }

        // cardinal points on the horizon
        if (showLabels && wideView) {
            val cards = arrayOf("N", "E", "S", "W")
            for (k in 0 until 4) {
                val az = k * Math.PI / 2
                val d = AstroMath.enu(az, 0.0)
                val p = project(d[0], d[1], d[2]) ?: continue
                if (p[0] < -40 || p[0] > w + 40 || p[1] < -40 || p[1] > h + 40) continue
                out.add(LabelItem(cards[k], p[0], p[1], 3, null, 0xFF6F87A8.toInt()))
            }
        }

        // Sun + Moon (discs drawn by overlay)
        val sunObj = SkyObject("Sun", "Sun", sun[0], sun[1], -26.7,
            "%.3f AU (%.1fM km)".format(sun[2], sun[2] * 149.6))
        projectRaDec(sun[0], sun[1], lst)?.let { p ->
            if (p[0] > -60 && p[0] < w + 60 && p[1] > -60 && p[1] < h + 60) {
                out.add(LabelItem("Sun", p[0], p[1], 4, sunObj, 0xFFFFD76A.toInt(),
                    sizePx = (h / 38f).coerceIn(10f, 60f) * (60f / fovDeg).coerceIn(0.4f, 4f)))
                addCandidate(p[0], p[1], sunObj)
            }
        }
        val moon = AstroMath.moonPosition(t)
        val waxing = moon[4] > 0.5
        val moonObj = SkyObject("Moon", "Moon", moon[0], moon[1], -12.0,
            "%.0f km".format(moon[2]),
            "Phase: %.0f%% %s".format(moon[3] * 100, if (waxing) "waxing" else "waning"))
        projectRaDec(moon[0], moon[1], lst)?.let { p ->
            if (p[0] > -60 && p[0] < w + 60 && p[1] > -60 && p[1] < h + 60) {
                out.add(LabelItem("Moon", p[0], p[1], 5, moonObj, 0xFFE8E8D8.toInt(),
                    phase = moon[3], waxing = waxing,
                    sizePx = (h / 46f).coerceIn(8f, 50f) * (60f / fovDeg).coerceIn(0.4f, 4f)))
                addCandidate(p[0], p[1], moonObj)
            }
        }

        // planets
        for (idx in intArrayOf(0, 1, 3, 4, 5, 6, 7)) {
            val eq = AstroMath.planetPosition(idx, t)
            val name = AstroMath.PLANET_NAMES[idx]
            val obj = SkyObject(name, "Planet", eq[0], eq[1], AstroMath.PLANET_MAGS[idx],
                "%.2f AU (%.0fM km)".format(eq[2], eq[2] * 149.6))
            val p = projectRaDec(eq[0], eq[1], lst) ?: continue
            if (p[0] < -40 || p[0] > w + 40 || p[1] < -40 || p[1] > h + 40) continue
            addCandidate(p[0], p[1], obj)
            if (showLabels) out.add(LabelItem(name, p[0], p[1], 1, obj,
                AstroMath.PLANET_COLORS[idx]))
        }

        // named stars
        for (s in NamedStars.all) {
            val p = projectRaDec(s.raRad, s.decRad, lst) ?: continue
            if (p[0] < -40 || p[0] > w + 40 || p[1] < -40 || p[1] > h + 40) continue
            val obj = SkyObject(s.name, "Star", s.raRad, s.decRad, s.mag)
            addCandidate(p[0], p[1], obj)
            if (showLabels) out.add(LabelItem(s.name, p[0], p[1], 0, obj))
        }

        // constellation names at centroid
        if (SkyState.showConstellations && showLabels && wideView) {
            for (c in Constellations.all) {
                var sx = 0.0; var sy = 0.0; var sz = 0.0
                val nv = c.starRaDec.size / 2
                var vis = 0
                for (v in 0 until nv) {
                    val ra = c.starRaDec[v * 2].toDouble()
                    val dec = c.starRaDec[v * 2 + 1].toDouble()
                    val lat = Math.toRadians(SkyState.latitudeDeg)
                    val ha = lst - ra
                    val sd = sin(dec); val cd = cos(dec)
                    val e = -cd * sin(ha)
                    val n = sd * cos(lat) - cd * sin(lat) * cos(ha)
                    val u = sd * sin(lat) + cd * cos(lat) * cos(ha)
                    sx += e; sy += n; sz += u
                    if (u > 0) vis++
                }
                if (vis < (nv + 1) / 2) continue
                val len = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
                if (len < 1e-6) continue
                val p = project(sx / len, sy / len, sz / len) ?: continue
                if (p[0] < -40 || p[0] > w + 40 || p[1] < -40 || p[1] > h + 40) continue
                val obj = SkyObject(c.name, "Constellation", 0.0, 0.0, 0.0)
                out.add(LabelItem(c.name.uppercase(), p[0], p[1], 2, obj, 0xFF7C96C4.toInt()))
            }
        }

        labels = out
        tapCandidates = candX
        tapObjects = candObj
    }

    private fun floatBuf(data: FloatArray): java.nio.FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(data).also { it.position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            return id
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
