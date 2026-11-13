package com.offline.stellarium

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Loads bundled binary catalogs: stars.dat and mw.dat (ra, dec, mag, color each). */
class StarCatalog(context: Context) {
    val starBuffer: FloatBuffer
    val starCount: Int
    val mwBuffer: FloatBuffer
    val mwCount: Int

    init {
        val s = load(context, "stars.dat")
        starCount = s.first
        starBuffer = s.second
        val m = load(context, "mw.dat")
        mwCount = m.first
        mwBuffer = m.second
    }

    private fun load(context: Context, name: String): Pair<Int, FloatBuffer> {
        val bytes = context.assets.open(name).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val fb = ByteBuffer.allocateDirect(count * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until count * 4) fb.put(buf.float)
        fb.position(0)
        return count to fb
    }
}

/** All GLSL shaders. Stars/lines convert RA/Dec -> ENU on the GPU via uLst/uLat. */
object Shaders {
    const val STAR_VERT = """
        uniform mat4 uViewProj;
        uniform float uLst;
        uniform float uLat;
        uniform float uPixelH;
        uniform float uFovScale;
        uniform float uMaxMag;
        uniform float uAlpha;
        uniform float uSizeFactor;
        attribute vec4 aStar;   // ra, dec, mag, color
        varying float vColor;
        varying float vAlpha;
        void main() {
            float ra = aStar.x;
            float dec = aStar.y;
            float mag = aStar.z;
            float ha = uLst - ra;
            float sd = sin(dec); float cd = cos(dec);
            float e = -cd * sin(ha);
            float n = sd * cos(uLat) - cd * sin(uLat) * cos(ha);
            float u = sd * sin(uLat) + cd * cos(uLat) * cos(ha);
            vColor = aStar.w;
            float fade = clamp((uMaxMag + 0.5 - mag) / 0.7, 0.0, 1.0);
            vAlpha = clamp(pow(0.42, mag - 1.0), 0.03, 1.0) * fade * uAlpha;
            gl_PointSize = clamp(uPixelH * uFovScale * (7.0 - mag) * 0.09 * uSizeFactor, 1.0, 22.0);
            if (u < -0.03 || vAlpha < 0.004) {
                gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                gl_PointSize = 0.0;
            } else {
                gl_Position = uViewProj * vec4(e, n, u, 1.0);
            }
        }
    """
    const val STAR_FRAG = """
        precision mediump float;
        varying float vColor;
        varying float vAlpha;
        void main() {
            vec2 c = gl_PointCoord - 0.5;
            float d = length(c);
            float alpha = smoothstep(0.5, 0.08, d) * vAlpha;
            if (alpha < 0.004) discard;
            vec3 blue = vec3(0.72, 0.82, 1.0);
            vec3 white = vec3(1.0, 0.98, 0.94);
            vec3 orange = vec3(1.0, 0.78, 0.55);
            vec3 col = vColor < 0.5
                ? mix(blue, white, vColor * 2.0)
                : mix(white, orange, (vColor - 0.5) * 2.0);
            gl_FragColor = vec4(col, alpha);
        }
    """
    const val LINE_VERT = """
        uniform mat4 uViewProj;
        uniform float uLst;
        uniform float uLat;
        uniform float uAlpha;
        attribute vec2 aRaDec;
        varying float vAlpha;
        void main() {
            float ha = uLst - aRaDec.x;
            float sd = sin(aRaDec.y); float cd = cos(aRaDec.y);
            float e = -cd * sin(ha);
            float n = sd * cos(uLat) - cd * sin(uLat) * cos(ha);
            float u = sd * sin(uLat) + cd * cos(uLat) * cos(ha);
            vAlpha = (u < -0.02) ? 0.0 : uAlpha;
            gl_Position = uViewProj * vec4(e, n, u, 1.0);
        }
    """
    const val LINE_FRAG = """
        precision mediump float;
        uniform vec3 uColor;
        varying float vAlpha;
        void main() {
            if (vAlpha < 0.01) discard;
            gl_FragColor = vec4(uColor, vAlpha);
        }
    """
    const val SKY_VERT = """
        attribute vec2 aXY;
        varying vec2 vXY;
        void main() {
            vXY = aXY;
            gl_Position = vec4(aXY, 0.0, 1.0);
        }
    """
    const val SKY_FRAG = """
        precision mediump float;
        varying vec2 vXY;
        uniform float uDaylight;
        uniform float uTwilight;
        void main() {
            vec3 zen = mix(vec3(0.006, 0.010, 0.026), vec3(0.13, 0.31, 0.66), uDaylight);
            vec3 hor = mix(vec3(0.016, 0.026, 0.060), vec3(0.50, 0.67, 0.87), uDaylight);
            hor += uTwilight * vec3(0.42, 0.15, 0.02);
            float y = clamp(vXY.y, -1.0, 1.0);
            vec3 col = mix(hor, zen, smoothstep(-0.15, 0.6, y));
            gl_FragColor = vec4(col, 1.0);
        }
    """
}
