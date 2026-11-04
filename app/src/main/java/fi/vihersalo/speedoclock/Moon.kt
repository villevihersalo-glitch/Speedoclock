package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** Moon phase from the date alone (accurate to a few hours, plenty for a clock). */
object MoonPhase {
    const val SYNODIC = 29.530588853

    /** 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    fun age(epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val a = (jd - 2451550.09766) / SYNODIC // reference new moon 6 Jan 2000 14:20 UTC
        return a - floor(a)
    }

    fun illumination(age: Double) = (1 - cos(2 * Math.PI * age)) / 2

    fun name(age: Double): String = when {
        age < 0.0339 || age > 0.9661 -> "NEW MOON"
        age < 0.2161 -> "WAXING CRESCENT"
        age < 0.2839 -> "FIRST QUARTER"
        age < 0.4661 -> "WAXING GIBBOUS"
        age < 0.5339 -> "FULL MOON"
        age < 0.7161 -> "WANING GIBBOUS"
        age < 0.7839 -> "LAST QUARTER"
        else -> "WANING CRESCENT"
    }

    /** Milliseconds until the moon next reaches [targetAge] (0.5 = full, 0 = new). */
    fun untilAge(epochMs: Long, targetAge: Double): Long {
        var d = targetAge - age(epochMs)
        if (d <= 0.01) d += 1.0
        return (d * SYNODIC * 86_400_000).toLong()
    }
}

/**
 * The back of the Chrono's inner dial: a realistic moon in today's phase, its name, how much is lit
 * and when the next full / new moon is. Drawn on a 600 x 600 canvas (the inner dial, radius ~296).
 */
object MoonFace {
    const val SIZE = 600f
    private const val CX = 300f

    private val CREAM = c(0xFFF4F0E6)
    private val AMBER = c(0xFFD9A441)

    fun render(epochMs: Long, lat: Double?, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / SIZE, px / SIZE)
        draw(cv, epochMs, lat)
        return bmp
    }

    fun draw(cv: Canvas, epochMs: Long, lat: Double?) {
        // Night-sky dial, same rim as the seconds dial.
        cv.drawCircle(CX, CX, 296f, stroke(c(0xFFCFC8BA), 6f))
        cv.drawCircle(CX, CX, 293f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                CX, CX - 40f, 330f,
                intArrayOf(c(0xFF1A2236), c(0xFF0C111D), c(0xFF04060A)),
                floatArrayOf(0f, .6f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        stars(cv)

        val age = MoonPhase.age(epochMs)
        val my = 262f
        val r = 128f
        moon(cv, CX, my, r, age, southern = (lat ?: 0.0) < 0)

        cv.textCentered("MOON", CX, 88f, textPaint(c(0x8CF4F0E6), 15f, SANS_BOLD).apply { letterSpacing = .3f })
        cv.textCentered(MoonPhase.name(age), CX, 428f, textPaint(CREAM, 24f, SANS_BOLD).apply { letterSpacing = .08f })
        val pct = (MoonPhase.illumination(age) * 100).roundToInt()
        cv.textCentered("$pct% LIT", CX, 460f, textPaint(c(0xB3F4F0E6), 17f, SANS_BOLD).apply { letterSpacing = .14f })

        val fmt = DateTimeFormatter.ofPattern("d.M.")
        val zone = ZoneId.systemDefault()
        val full = Instant.ofEpochMilli(epochMs + MoonPhase.untilAge(epochMs, 0.5)).atZone(zone).toLocalDate()
        val new = Instant.ofEpochMilli(epochMs + MoonPhase.untilAge(epochMs, 0.0)).atZone(zone).toLocalDate()
        val line = "FULL ${full.format(fmt)}   ·   NEW ${new.format(fmt)}"
        cv.textCentered(line, CX, 500f, textPaint(AMBER, 17f, SANS_BOLD).apply { letterSpacing = .06f })
        cv.textCentered("TAP FOR SOLAR SYSTEM", CX, 536f, textPaint(c(0x4DF4F0E6), 11f, SANS_BOLD).apply { letterSpacing = .2f })
    }

    private fun stars(cv: Canvas) {
        val rnd = java.util.Random(42)
        repeat(70) {
            val x = 20f + rnd.nextFloat() * 560f
            val y = 20f + rnd.nextFloat() * 560f
            val dx = x - CX; val dy = y - CX
            if (dx * dx + dy * dy > 280f * 280f) return@repeat
            val b = 40 + rnd.nextInt(150)
            cv.drawCircle(x, y, 0.6f + rnd.nextFloat() * 1.3f, fill((b shl 24) or 0xFFFFFF))
        }
    }

    // Big dark "seas", as (x, y, radius) on a unit disc, roughly where they are on the real moon.
    private val maria = arrayOf(
        floatArrayOf(-0.34f, -0.36f, 0.27f), // Imbrium
        floatArrayOf(-0.62f, 0.02f, 0.30f),  // Procellarum
        floatArrayOf(0.12f, -0.33f, 0.15f),  // Serenitatis
        floatArrayOf(0.26f, -0.07f, 0.19f),  // Tranquillitatis
        floatArrayOf(0.62f, -0.27f, 0.11f),  // Crisium
        floatArrayOf(0.50f, 0.13f, 0.14f),   // Fecunditatis
        floatArrayOf(0.30f, 0.27f, 0.10f),   // Nectaris
        floatArrayOf(-0.15f, 0.38f, 0.15f),  // Nubium
        floatArrayOf(-0.44f, 0.42f, 0.09f),  // Humorum
        floatArrayOf(-0.05f, -0.63f, 0.09f), // Frigoris
        floatArrayOf(-0.08f, 0.05f, 0.11f),  // Insularum
    )

    private fun surface(cv: Canvas, x: Float, y: Float, r: Float, lit: Boolean) {
        if (lit) {
            cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    x - r * 0.25f, y - r * 0.25f, r * 1.35f,
                    intArrayOf(c(0xFFF4F1E8), c(0xFFD9D6CC), c(0xFF9C998F)),
                    floatArrayOf(0f, .6f, 1f),
                    Shader.TileMode.CLAMP,
                )
            })
        } else {
            cv.drawCircle(x, y, r, fill(c(0xFF1C2028))) // earthshine
        }
        // Seas: soft-edged, built from a few overlapping blobs so they don't look like stickers.
        val core = if (lit) c(0x7A66645E) else c(0x24000000)
        val blob = java.util.Random(3)
        for (m in maria) {
            repeat(3) { k ->
                val ox = if (k == 0) 0f else (blob.nextFloat() - .5f) * m[2] * 0.9f
                val oy = if (k == 0) 0f else (blob.nextFloat() - .5f) * m[2] * 0.9f
                val mr = m[2] * r * (if (k == 0) 1f else 0.55f + blob.nextFloat() * 0.3f)
                val mx = x + (m[0] + ox) * r; val myy = y + (m[1] + oy) * r
                cv.drawCircle(mx, myy, mr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(mx, myy, mr, intArrayOf(core, core, 0), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                })
            }
        }
        // small craters with a lit rim
        val rnd = java.util.Random(7)
        repeat(34) {
            val a = rnd.nextFloat() * 2 * Math.PI
            val d = Math.sqrt(rnd.nextDouble()) * 0.9
            val cx = x + (cos(a) * d * r).toFloat()
            val cy = y + (Math.sin(a) * d * r).toFloat()
            val cr = r * (0.02f + rnd.nextFloat() * 0.05f)
            if (!lit) return@repeat
            cv.drawCircle(cx, cy, cr, fill(c(0x33595750)))
            cv.drawCircle(cx + cr * 0.25f, cy + cr * 0.25f, cr, stroke(c(0x2EFFFFFF), 1.2f))
        }
        if (lit) {
            // Tycho, bright young crater near the south
            cv.drawCircle(x - 0.12f * r, y + 0.72f * r, r * 0.035f, fill(c(0xCCFFFFFF)))
        }
    }

    /** Draws the moon with the lit part for [age]. Waxing is lit on the right (northern hemisphere view). */
    private fun moon(cv: Canvas, x: Float, y: Float, r: Float, age: Double, southern: Boolean) {
        // soft glow
        val illum = MoonPhase.illumination(age).toFloat()
        cv.drawCircle(x, y, r * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r * 1.5f, intArrayOf(((70 * illum).toInt() shl 24) or 0xFFF6D8, 0), floatArrayOf(.66f, 1f), Shader.TileMode.CLAMP)
        })

        surface(cv, x, y, r, lit = false)

        // Lit region: limb semicircle on the sunny side + terminator half-ellipse.
        val waxing = age < 0.5
        val rx = r * abs(cos(2 * Math.PI * age)).toFloat()
        val gibbous = illum > 0.5f
        val lit = Path().apply {
            moveTo(x, y - r)
            arcTo(RectF(x - r, y - r, x + r, y + r), -90f, 180f, false) // right limb, top -> bottom
            arcTo(RectF(x - rx, y - r, x + rx, y + r), 90f, if (gibbous) 180f else -180f, false)
            close()
        }
        val mirror = waxing == southern // lit side on the left
        if (mirror) lit.transform(Matrix().apply { setScale(-1f, 1f, x, y) })

        if (illum > 0.005f) {
            cv.save()
            cv.clipPath(lit)
            surface(cv, x, y, r, lit = true)
            cv.restore()
            // soften the terminator a touch
            cv.drawPath(lit, stroke(c(0x22000000), 2f))
        }
        cv.drawCircle(x, y, r, stroke(c(0x33FFFFFF), 1.2f))
    }
}
