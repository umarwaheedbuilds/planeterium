package com.offline.stellarium

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Complete offline ephemeris: Sun, Moon (with perturbations) and all 8
 * planets from Keplerian orbital elements (Paul Schlyter's low-precision
 * method, ~arcmin accuracy 1900-2100). Zero network, pure math.
 */
object AstroMath {
    private const val D2R = Math.PI / 180.0
    private const val R2D = 180.0 / Math.PI

    private fun rev(x: Double) = x - 360.0 * floor(x / 360.0)
    /** Days since 2000 Jan 0.0 (= 1999-12-31 00:00 UT). */
    private fun dayNum(timeMillis: Long) = timeMillis / 86400000.0 - 10956.0

    /** Local sidereal time, radians. */
    fun lstRadians(timeMillis: Long, lonDeg: Double): Double {
        val d = timeMillis / 86400000.0 - 10957.5
        val gmst = 18.697374558 + 24.06570982441908 * d
        var lst = (gmst * 15.0 + lonDeg) % 360.0
        if (lst < 0) lst += 360.0
        return lst * D2R
    }

    /** RA/Dec (rad) -> azimuth (rad, from north eastward) + altitude (rad). */
    fun altAz(raRad: Double, decRad: Double, latDeg: Double, lstRad: Double): DoubleArray {
        val lat = latDeg * D2R
        val ha = lstRad - raRad
        val sinAlt = sin(decRad) * sin(lat) + cos(decRad) * cos(lat) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val az = atan2(
            -sin(ha) * cos(decRad),
            sin(decRad) * cos(lat) - cos(decRad) * sin(lat) * cos(ha)
        )
        return doubleArrayOf(az, alt)
    }

    /** Alt/az (rad) -> east, north, up unit vector. */
    fun enu(az: Double, alt: Double): DoubleArray =
        doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))

    // ---------- SUN ----------
    /** Returns [ra, dec] radians and distance in AU. */
    fun sunPosition(timeMillis: Long): DoubleArray {
        val d = dayNum(timeMillis)
        val w = 282.9404 + 4.70935E-5 * d
        val e = 0.016709 - 1.151E-9 * d
        val M = rev(356.0470 + 0.9856002585 * d)
        var E = M + e * R2D * sin(M * D2R) * (1 + e * cos(M * D2R))
        E = M + e * R2D * sin(E * D2R) * (1 + e * cos(E * D2R))
        val xv = cos(E * D2R) - e
        val yv = sqrt(1 - e * e) * sin(E * D2R)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val lon = rev(v * R2D + w)
        return eqFromEcl(r * cos(lon * D2R), r * sin(lon * D2R), 0.0, d)
    }

    // ---------- MOON ----------
    /** Returns [ra, dec] rad, distance km, phase fraction 0..1, waxing flag as 1.0/0.0. */
    fun moonPosition(timeMillis: Long): DoubleArray {
        val d = dayNum(timeMillis)
        val N = rev(125.1228 - 0.0529538083 * d)
        val i = 5.1454
        val w = rev(318.0634 + 0.1643573223 * d)
        val a = 60.2666
        val e = 0.054900
        val M = rev(115.3654 + 13.0649929509 * d)
        var E = M + e * R2D * sin(M * D2R) * (1 + e * cos(M * D2R))
        repeat(5) {
            E = M + e * R2D * sin(E * D2R) * (1 + e * cos(E * D2R))
        }
        val xv = a * (cos(E * D2R) - e)
        val yv = a * sqrt(1 - e * e) * sin(E * D2R)
        val v = atan2(yv, xv)
        val r0 = sqrt(xv * xv + yv * yv)
        val u = v * R2D + w
        val Nr = N * D2R
        val ir = i * D2R
        val ur = u * D2R
        val xh = r0 * (cos(Nr) * cos(ur) - sin(Nr) * sin(ur) * cos(ir))
        val yh = r0 * (sin(Nr) * cos(ur) + cos(Nr) * sin(ur) * cos(ir))
        val zh = r0 * sin(ur) * sin(ir)
        var lon = rev(atan2(yh, xh) * R2D)
        var lat = asin(zh / r0) * R2D
        val Ms = rev(356.0470 + 0.9856002585 * d)
        val Ls = Ms + 282.9404 + 4.70935E-5 * d
        val Lm = N + w + M
        val Dm = rev(Lm - Ls)
        val F = rev(Lm - N)
        fun s(x: Double) = sin(x * D2R)
        lon += -1.274 * s(M - 2 * Dm) + 0.658 * s(2 * Dm) - 0.186 * s(Ms)
             - 0.059 * s(2 * M - 2 * Dm) - 0.057 * s(M - 2 * Dm + Ms)
             + 0.053 * s(M + 2 * Dm) + 0.046 * s(2 * Dm - Ms)
             + 0.041 * s(M - Ms) - 0.035 * s(Dm) - 0.031 * s(M + Ms)
             - 0.015 * s(2 * F - 2 * Dm) + 0.011 * s(M - 4 * Dm)
        lat += -0.173 * s(F - 2 * Dm) - 0.055 * s(M - F - 2 * Dm)
             - 0.046 * s(M + F - 2 * Dm) + 0.033 * s(F + 2 * Dm) + 0.017 * s(2 * M + F)
        val rm = r0 - 0.58 * cos((M - 2 * Dm) * D2R) - 0.46 * cos(2 * Dm * D2R)
        val lonR = rev(lon) * D2R
        val latR = lat * D2R
        val x = rm * cos(latR) * cos(lonR)
        val y = rm * cos(latR) * sin(lonR)
        val z = rm * sin(latR)
        val eq = eqFromEcl(x, y, z, d)
        val elong = rev(Lm - Ls)
        val phase = (1 + cos(elong * D2R)) / 2.0
        return doubleArrayOf(eq[0], eq[1], rm * 6371.0, phase, if (elong < 180) 1.0 else 0.0)
    }

    // ---------- PLANETS ----------
    val PLANET_NAMES = arrayOf("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
    val PLANET_COLORS = intArrayOf(0xFFB0B0A8.toInt(), 0xFFF2E6C9.toInt(), 0x000000,
        0xFFE57373.toInt(), 0xFFE6C9A0.toInt(), 0xFFE8D9A0.toInt(),
        0xFF9FD8D4.toInt(), 0xFF7AA2F7.toInt())
    val PLANET_MAGS = doubleArrayOf(-0.2, -4.2, 0.0, 0.7, -2.2, 0.6, 5.7, 7.9)

    /** Geocentric [ra, dec] rad + distance AU for planet index (0,1,3..7). */
    fun planetPosition(index: Int, timeMillis: Long): DoubleArray {
        val d = dayNum(timeMillis)
        val p = planetHelio(index, d)
        val e = planetHelio(2, d)
        return eqFromEcl(p[0] - e[0], p[1] - e[1], p[2] - e[2], d)
    }

    private fun planetHelio(idx: Int, d: Double): DoubleArray {
        // Schlyter elements: N, i, w, a, e, M  (angles deg, rates per day)
        var N = 0.0; var i = 0.0; var w = 0.0; var a = 1.0; var e = 0.0; var M = 0.0
        when (idx) {
            0 -> { N = 48.3313 + 3.24587E-5 * d; i = 7.0047 + 5.00E-8 * d; w = 29.1241 + 1.01444E-5 * d; a = 0.387098; e = 0.205635 + 5.59E-10 * d; M = 168.6562 + 4.0923344368 * d }
            1 -> { N = 76.6799 + 2.46590E-5 * d; i = 3.3946 + 2.75E-8 * d; w = 54.8910 + 1.38374E-5 * d; a = 0.723330; e = 0.006773 - 1.302E-9 * d; M = 48.0052 + 1.6021367308 * d }
            2 -> { N = 0.0; i = 0.0; w = 282.9404 + 4.70935E-5 * d; a = 1.000000; e = 0.016709 - 1.151E-9 * d; M = 356.0470 + 0.9856002585 * d }
            3 -> { N = 49.5574 + 2.11081E-5 * d; i = 1.8497 - 1.78E-8 * d; w = 286.5016 + 2.92961E-5 * d; a = 1.523688; e = 0.093405 + 2.516E-9 * d; M = 18.6021 + 0.5240207766 * d }
            4 -> { N = 100.4542 + 2.76854E-5 * d; i = 1.3030 - 1.557E-7 * d; w = 273.8777 + 1.64505E-5 * d; a = 5.20256; e = 0.048498 + 4.469E-9 * d; M = 19.8950 + 0.0830853001 * d }
            5 -> { N = 113.6634 + 2.38980E-5 * d; i = 2.4886 - 1.081E-7 * d; w = 339.3939 + 2.97661E-5 * d; a = 9.55475; e = 0.055546 - 9.499E-9 * d; M = 316.9670 + 0.0334442282 * d }
            6 -> { N = 74.0005 + 1.3978E-5 * d; i = 0.7733 + 1.9E-8 * d; w = 96.6612 + 3.0565E-5 * d; a = 19.18171 - 1.55E-8 * d; e = 0.047318 + 7.45E-9 * d; M = 142.5905 + 0.011725806 * d }
            7 -> { N = 131.7806 + 3.0173E-5 * d; i = 1.7700 - 2.55E-7 * d; w = 272.8461 - 6.027E-6 * d; a = 30.05826 + 3.313E-8 * d; e = 0.008606 + 2.15E-9 * d; M = 260.2471 + 0.005995147 * d }
        }
        N = rev(N); i = rev(i); w = rev(w); M = rev(M)
        var E = M + e * R2D * sin(M * D2R) * (1 + e * cos(M * D2R))
        repeat(2) { E = M + e * R2D * sin(E * D2R) * (1 + e * cos(E * D2R)) }
        val xv = a * (cos(E * D2R) - e)
        val yv = a * sqrt(1 - e * e) * sin(E * D2R)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val u = rev(v * R2D + w)
        val Nr = N * D2R; val ir = i * D2R; val ur = u * D2R
        val xh = r * (cos(Nr) * cos(ur) - sin(Nr) * sin(ur) * cos(ir))
        val yh = r * (sin(Nr) * cos(ur) + cos(Nr) * sin(ur) * cos(ir))
        val zh = r * sin(ur) * sin(ir)
        var lon = rev(atan2(yh, xh) * R2D)
        var lat = asin(zh / r) * R2D
        val Ms = rev(356.0470 + 0.9856002585 * d)
        val Mj = rev(19.8950 + 0.0830853001 * d)
        val Mu = rev(142.5905 + 0.011725806 * d)
        fun s(x: Double) = sin(x * D2R)
        fun c(x: Double) = cos(x * D2R)
        when (idx) {
            4 -> lon += -0.332 * s(2 * Mj - 5 * Ms - 67.6) - 0.056 * s(2 * Mj - 2 * Ms + 21)
                 + 0.042 * s(3 * Mj - 5 * Ms + 21) - 0.036 * s(Mj - 2 * Ms)
                 + 0.022 * c(Mj - Ms) + 0.023 * s(2 * Mj - 3 * Ms + 52) - 0.016 * s(Mj - 5 * Ms - 69)
            5 -> { lon += 0.812 * s(2 * Mj - 5 * Ms - 67.6) - 0.229 * c(2 * Mj - 4 * Ms - 2)
                 + 0.119 * s(Mj - 2 * Ms - 3) + 0.046 * s(2 * Mj - 6 * Ms - 69)
                 + 0.014 * s(Mj - 3 * Ms + 32)
                 lat += -0.020 * c(2 * Mj - 4 * Ms - 2) + 0.018 * s(2 * Mj - 6 * Ms - 49) }
            6 -> lon += -0.004 * s(Ms - 2 * Mu + 115.2) + 0.005 * s(2 * Ms - 3 * Mu + 322.1)
        }
        val lonR = rev(lon) * D2R; val latR = lat * D2R
        return doubleArrayOf(r * cos(latR) * cos(lonR), r * cos(latR) * sin(lonR), r * sin(latR))
    }

    /** Ecliptic (of date) x,y,z -> equatorial [ra, dec, dist]. */
    private fun eqFromEcl(x: Double, y: Double, z: Double, d: Double): DoubleArray {
        val obl = (23.4393 - 3.563E-7 * d) * D2R
        val xe = x
        val ye = y * cos(obl) - z * sin(obl)
        val ze = y * sin(obl) + z * cos(obl)
        return doubleArrayOf(atan2(ye, xe), atan2(ze, sqrt(xe * xe + ye * ye)), sqrt(xe * xe + ye * ye + ze * ze))
    }

    fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun fmtHMS(raRad: Double): String {
        val h = rev(raRad * R2D / 15.0)
        val hh = h.toInt(); val mm = ((h - hh) * 60).toInt()
        val ss = ((h - hh) * 3600 - mm * 60)
        return "%02dh %02dm %04.1fs".format(hh, mm, ss)
    }

    fun fmtDMS(decRad: Double): String {
        val dd0 = decRad * R2D
        val sign = if (dd0 < 0) "-" else "+"
        val a = abs(dd0); val dd = a.toInt(); val mm = ((a - dd) * 60).toInt()
        val ss = (a - dd) * 3600 - mm * 60
        return "%s%02d° %02d' %04.1f\"".format(sign, dd, mm, ss)
    }
}
