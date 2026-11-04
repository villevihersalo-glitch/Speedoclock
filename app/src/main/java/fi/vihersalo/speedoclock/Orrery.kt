package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Third side of the Chrono centre: the solar system seen from above the North Pole, planets where they
 * really are today (heliocentric longitude from mean orbital elements + the biggest eccentricity term,
 * good to a degree or two). Orbits are evenly spaced so all eight fit;
 * sizes are not to scale. Earth is lit on the side facing the Sun; the Moon sits in its real phase angle.
 * Drawn on a 600 x 600 canvas.
 */
object Orrery {
    private const val C = 300f

    /** name, a (AU), L0 at J2000 (deg), rate (deg/day), perihelion lon (deg), eccentricity, colour, size */
    private class Planet(
        val name: String, val a: Double, val l0: Double, val rate: Double, val peri: Double, val e: Double,
        val color: Int, val size: Float,
    )

    private val planets = listOf(
        Planet("MERCURY", 0.387, 252.25084, 4.09233445, 77.45645, 0.2056, c(0xFFB5ADA4), 3.2f),
        Planet("VENUS", 0.723, 181.97973, 1.60213034, 131.53298, 0.0068, c(0xFFF1D79A), 4.6f),
        Planet("EARTH", 1.000, 100.46435, 0.98560910, 102.94719, 0.0167, c(0xFF3F86E8), 6.2f),
        Planet("MARS", 1.524, 355.45332, 0.52403304, 336.04084, 0.0934, c(0xFFE0663A), 4.2f),
        Planet("JUPITER", 5.203, 34.40438, 0.08308676, 14.75385, 0.0484, c(0xFFD9B48A), 10f),
        Planet("SATURN", 9.537, 49.94432, 0.03346063, 92.43194, 0.0542, c(0xFFE8D29A), 8.5f),
        Planet("URANUS", 19.19, 313.23218, 0.01173129, 170.96424, 0.0472, c(0xFF9FE3EA), 6.5f),
        Planet("NEPTUNE", 30.07, 304.88003, 0.00598106, 44.97135, 0.0086, c(0xFF5A7BF0), 6.5f),
    )

    /** Heliocentric ecliptic longitude in degrees. */
    fun longitude(name: String, epochMs: Long): Double {
        val p = planets.first { it.name == name }
        val d = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val l = p.l0 + p.rate * d
        val m = Math.toRadians(l - p.peri) // mean anomaly
        val eqCentre = Math.toDegrees(2 * p.e * sin(m) + 1.25 * p.e * p.e * sin(2 * m))
        return ((l + eqCentre) % 360 + 360) % 360
    }

    /** Evenly spaced orbits, like a brass orrery: positions are true, distances are not. */
    private fun orbitR(a: Double): Float {
        val idx = planets.indexOfFirst { it.a == a }
        return if (idx >= 0) 46f + idx * 31f else 46f + (3f + ((a - 2.2) / 1.1).toFloat() * 0.6f + 0.2f) * 31f
    }

    fun render(epochMs: Long, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / 600f, px / 600f)
        draw(cv, epochMs)
        return bmp
    }

    fun draw(cv: Canvas, epochMs: Long) {
        cv.drawCircle(C, C, 296f, stroke(c(0xFFCFC8BA), 6f))
        cv.drawCircle(C, C, 293f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(C, C, 300f, intArrayOf(c(0xFF0E1222), c(0xFF06080F), c(0xFF020305)),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        })
        val rnd = java.util.Random(11)
        repeat(110) {
            val x = rnd.nextFloat() * 600f; val y = rnd.nextFloat() * 600f
            if ((x - C) * (x - C) + (y - C) * (y - C) < 285f * 285f) {
                cv.drawCircle(x, y, 0.5f + rnd.nextFloat(), fill(((30 + rnd.nextInt(120)) shl 24) or 0xFFFFFF))
            }
        }

        // Orbits and the asteroid belt.
        for (p in planets) cv.drawCircle(C, C, orbitR(p.a), stroke(if (p.name == "EARTH") c(0x553F86E8) else c(0x26FFFFFF), 1.2f))
        repeat(160) {
            val a = 2.2 + rnd.nextDouble() * 1.1
            val ang = rnd.nextDouble() * 2 * Math.PI
            val r = orbitR(a)
            cv.drawCircle(C + r * cos(ang).toFloat(), C - r * sin(ang).toFloat(), 0.7f, fill(c(0x55C9B9A0)))
        }

        // The Sun.
        cv.drawCircle(C, C, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(C, C, 34f, intArrayOf(c(0xAAFFC24A), c(0x33FF8A1A), 0), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        })
        cv.drawCircle(C, C, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(C - 3f, C - 3f, 15f, intArrayOf(c(0xFFFFFBE0), c(0xFFFFD25A), c(0xFFFF9A1A)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        })

        val label = textPaint(c(0x99F4F0E6), 10.5f, SANS_BOLD).apply { letterSpacing = .12f }
        for (p in planets) {
            val lon = Math.toRadians(longitude(p.name, epochMs))
            val r = orbitR(p.a)
            // 0° (spring equinox direction) to the right, planets move anticlockwise as seen from the north.
            val x = C + r * cos(lon).toFloat()
            val y = C - r * sin(lon).toFloat()
            if (p.name == "SATURN") {
                cv.drawOval(RectF(x - p.size * 2f, y - p.size * 0.7f, x + p.size * 2f, y + p.size * 0.7f), stroke(c(0xCCE8D29A), 1.8f))
            }
            if (p.name == "EARTH") earth(cv, x, y, p.size, epochMs) else litBall(cv, x, y, p.size, p.color)
            val ly = if (y < C) y - p.size - 8f else y + p.size + 13f
            if (p.size >= 4.5f && p.name != "EARTH") cv.textCentered(p.name, x, ly, label)
        }

        cv.textCentered("SOLAR SYSTEM", C, 40f, textPaint(c(0x8CF4F0E6), 13f, SANS_BOLD).apply { letterSpacing = .3f })
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)).uppercase(Locale.US)
        cv.textCentered(date, C, 568f, textPaint(c(0xFFD9A441), 14f, SANS_BOLD).apply { letterSpacing = .15f })
    }

    /** A planet lit from the Sun's direction (from the centre). */
    private fun litBall(cv: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val ang = atan2(C - y, C - x)
        val hx = x + cos(ang) * r * 0.45f
        val hy = y + sin(ang) * r * 0.45f
        cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(hx, hy, r * 1.6f, intArrayOf(c(0xFFFFFFFF), color, c(0xFF0A0A0A)),
                floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** Earth: day half facing the Sun, night half dark, plus the Moon at its real phase angle. */
    private fun earth(cv: Canvas, x: Float, y: Float, r: Float, epochMs: Long) {
        cv.drawCircle(x, y, r * 2.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r * 2.4f, intArrayOf(c(0x553F86E8), 0), null, Shader.TileMode.CLAMP)
        })
        cv.drawCircle(x, y, r, fill(c(0xFF0B1A33))) // night side
        val toSun = Math.toDegrees(atan2((C - y).toDouble(), (C - x).toDouble())).toFloat()
        val day = Path().apply { arcTo(oval(x, y, r), toSun - 90f, 180f, true); close() }
        cv.drawPath(day, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x + cos(Math.toRadians(toSun.toDouble())).toFloat() * r * .4f,
                y + sin(Math.toRadians(toSun.toDouble())).toFloat() * r * .4f, r * 1.4f,
                intArrayOf(c(0xFFBFE6FF), c(0xFF3F86E8), c(0xFF1C4E9A)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        })
        cv.drawPath(day, stroke(c(0x55FFFFFF), 0.8f))

        // Moon: seen from Earth it is (age × 360°) east of the Sun.
        val sunLon = longitude("EARTH", epochMs) + 180.0
        val moonLon = Math.toRadians(sunLon + MoonPhase.age(epochMs) * 360.0)
        val mr = r + 9f
        val mx = x + mr * cos(moonLon).toFloat()
        val my = y - mr * sin(moonLon).toFloat()
        cv.drawCircle(x, y, mr, stroke(c(0x22FFFFFF), 0.8f))
        litBall(cv, mx, my, 2.2f, c(0xFFD8D6CE))
        cv.textCentered("EARTH", x, if (y < C) y - r - 12f else y + r + 17f, textPaint(c(0xFF7FB4FF), 11f, SANS_BOLD).apply { letterSpacing = .12f })
    }
}
