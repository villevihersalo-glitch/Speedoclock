package fi.vihersalo.speedoclock

import android.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun position from latitude/longitude, fully offline (NOAA solar equations, good to about a minute).
 * The 24 h ring is coloured by how high the sun is, so blue hour and golden hour come out naturally,
 * including on days when they are long (north) or short (tropics).
 */
object Sun {
    /** Samples per day: one every 5 minutes, plus the closing midnight. */
    const val STEPS = 288

    /** Sun elevation in degrees, every 5 minutes of the local day [date], STEPS + 1 values. */
    fun dayElevations(lat: Double, lon: Double, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): FloatArray {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return FloatArray(STEPS + 1) { i -> elevation(lat, lon, midnight + i * 5 * 60_000L).toFloat() }
    }

    fun elevation(lat: Double, lon: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(eps)) * sin(rad(lambda)))

        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
        )
        val utcMinutes = ((epochMs % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
        val trueSolar = utcMinutes + eqTime + 4 * lon
        val hourAngle = trueSolar / 4 - 180
        val cosZen = (sin(rad(lat)) * sin(decl) + cos(rad(lat)) * cos(decl) * cos(rad(hourAngle))).coerceIn(-1.0, 1.0)
        return 90 - deg(acos(cosZen))
    }

    /** Minutes after midnight when the sun crosses [elev] going up (rising) or down; null if it doesn't today. */
    fun crossing(elevs: FloatArray, elev: Float, rising: Boolean): Int? {
        for (i in 0 until elevs.size - 1) {
            val a = elevs[i]; val b = elevs[i + 1]
            val hit = if (rising) a < elev && b >= elev else a >= elev && b < elev
            if (hit) return (i * 5 + 5 * (elev - a) / (b - a)).toInt()
        }
        return null
    }

    // Sky colours keyed by sun elevation (degrees).
    private val stopElev = floatArrayOf(-18f, -9f, -6f, -4f, -1f, 2f, 6f, 10f, 25f)
    private val stopColor = intArrayOf(
        c(0xFF070A1E), // night
        c(0xFF0F1A4A), // nautical dusk
        c(0xFF1F3C9A), // blue hour starts
        c(0xFF3D6FE0), // blue hour
        c(0xFFE0604A), // sun at the horizon
        c(0xFFFF9A2E), // golden hour
        c(0xFFFFC85A), // golden hour ends
        c(0xFFFFE9B8), // morning / afternoon light
        c(0xFFBFE3FF), // high sun
    )

    fun skyColor(elev: Float): Int {
        if (elev <= stopElev.first()) return stopColor.first()
        if (elev >= stopElev.last()) return stopColor.last()
        val i = stopElev.indexOfFirst { it > elev } - 1
        val f = (elev - stopElev[i]) / (stopElev[i + 1] - stopElev[i])
        return lerp(stopColor[i], stopColor[i + 1], f)
    }

    private fun lerp(a: Int, b: Int, f: Float) = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(d: Double) = ((d % 360) + 360) % 360
}
