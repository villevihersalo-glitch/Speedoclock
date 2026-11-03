package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.ceil

/**
 * Classic chrome-bezel gauge (design cues from vintage sports-car clocks):
 *  - outer amber ring: 24-hour scale with a red bar that fills through the day
 *  - middle dark ring: 60 LEDs, one per minute
 *  - inner black dial: seconds as a tachometer, black -> green -> yellow -> red.
 *    The needle itself is the AnalogClock second hand (needle_chrono.xml).
 * Drawn on a 1000 x 1000 canvas, centre (500, 500).
 */
object ChronoFace {
    const val SIZE = 1000f
    private const val CX = 500f
    private const val CY = 500f

    /** Where second 0 sits (lower left). Must match the rotation in needle_chrono.xml. */
    const val SEC_ZERO_DEG = 225f

    private val INK = c(0xFF2A1606)
    private val CREAM = c(0xFFF4F0E6)
    private val AMBER = c(0xFFFFB43A)
    private val GREEN = c(0xFF39E46A)
    private val RED = c(0xFFE8231A)

    /** [secSweep]: 360 for the widget's clock hand, 270 for the screensaver's own animated needle. */
    fun render(s: DashState, px: Int, secSweep: Float = 360f): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / SIZE, px / SIZE)
        draw(cv, s, secSweep)
        return bmp
    }

    fun draw(cv: Canvas, s: DashState, secSweep: Float = 360f) {
        bezel(cv)
        hourRing(cv, s)
        minuteLeds(cv, s)
        tachDial(cv, s, secSweep)
    }

    private fun bezel(cv: Canvas) {
        val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                CX, CY,
                intArrayOf(
                    c(0xFFF2F2F2), c(0xFF6A6A6A), c(0xFFFFFFFF), c(0xFF505050), c(0xFFE0E0E0),
                    c(0xFF7A7A7A), c(0xFFFFFFFF), c(0xFF5A5A5A), c(0xFFF2F2F2),
                ),
                null,
            )
        }
        cv.drawCircle(CX, CY, 500f, chrome)
        cv.drawCircle(CX, CY, 499f, stroke(c(0x66000000), 2f))
        cv.drawCircle(CX, CY, 481f, fill(c(0xFF0B0B0B)))
        cv.drawCircle(CX, CY, 479f, stroke(c(0xFFBDBDBD), 3f))
    }

    private fun hourRing(cv: Canvas, s: DashState) {
        // Warm, back-lit amber dial.
        cv.drawCircle(CX, CY, 474f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                420f, 380f, 640f,
                intArrayOf(c(0xFFFBE6B0), c(0xFFEDBE68), c(0xFFCF8A2E), c(0xFF7A4512)),
                floatArrayOf(0f, .4f, .78f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        cv.drawCircle(CX, CY, 474f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                CX, CY, 474f, intArrayOf(0, 0, c(0x99000000)), floatArrayOf(0f, .9f, 1f), Shader.TileMode.CLAMP,
            )
        })

        // 24 h gauge bar: fills clockwise from 24 at the top.
        val bar = oval(CX, CY, 452f)
        cv.drawCircle(CX, CY, 452f, stroke(c(0x33301A08), 18f))
        val sweep = s.minuteOfDay / 1440f * 360f
        if (sweep > 0f) {
            cv.drawArc(bar, -90f, sweep, false, stroke(c(0xFFB8261A), 18f).apply {
                setShadowLayer(8f, 0f, 0f, c(0xAAFF3A1A))
            })
            cv.drawArc(oval(CX, CY, 457f), -90f, sweep, false, stroke(c(0x55FFFFFF), 3f))
            // bright tip
            cv.radialLine(CX, CY, sweep, 441f, 463f, stroke(c(0xFFFFE6B0), 5f))
        }
        cv.drawCircle(CX, CY, 442f, stroke(c(0xFF6E3C0E), 2f))
        cv.drawCircle(CX, CY, 462f, stroke(c(0x886E3C0E), 1.5f))

        // Ticks every 15 minutes.
        for (i in 0 until 96) {
            val (len, w) = when {
                i % 4 == 0 -> 30f to 5f
                i % 2 == 0 -> 17f to 3f
                else -> 9f to 2f
            }
            cv.radialLine(CX, CY, i * 3.75f, 440f, 440f - len, stroke(INK, w))
        }

        // Numerals 1..24.
        val big = textPaint(INK, 44f, SERIF_BOLD).apply { setShadowLayer(2f, 0f, 1f, c(0x55FFF0C0)) }
        val small = textPaint(INK, 30f, SERIF_BOLD)
        for (h in 1..24) {
            val deg = h * 15f
            cv.textCentered(h.toString(), ax(CX, 380f, deg), ay(CY, 380f, deg), if (h % 2 == 0) big else small)
        }
    }

    private fun minuteLeds(cv: Canvas, s: DashState) {
        cv.drawCircle(CX, CY, 323f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 50f
            shader = RadialGradient(
                CX, CY, 348f,
                intArrayOf(c(0xFF0D0A08), c(0xFF0D0A08), c(0xFF2E241B)),
                floatArrayOf(0f, 298f / 348f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        cv.drawCircle(CX, CY, 348f, stroke(c(0xFFE4DCCB), 4f))
        cv.drawCircle(CX, CY, 298f, stroke(c(0xFF8E8578), 3f))

        // Glow first, so LEDs sit on top of their neighbours' halos.
        for (k in 0..s.minute) {
            val deg = k * 6f
            val x = ax(CX, 323f, deg); val y = ay(CY, 323f, deg)
            val cur = k == s.minute
            val r = if (cur) 30f else 19f
            cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    x, y, r, intArrayOf(if (cur) c(0xCCFFB060) else c(0x88FF5A1A), 0), null, Shader.TileMode.CLAMP,
                )
            })
        }
        for (k in 0 until 60) {
            val deg = k * 6f
            val x = ax(CX, 323f, deg); val y = ay(CY, 323f, deg)
            val r = if (k % 15 == 0) 10f else if (k % 5 == 0) 8.5f else 7f
            if (k <= s.minute) {
                val cur = k == s.minute
                cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        x - r * .3f, y - r * .3f, r * 1.3f,
                        intArrayOf(
                            if (cur) c(0xFFFFFFF0) else c(0xFFFFE0A8),
                            if (cur) c(0xFFFFC060) else c(0xFFFF5A12),
                            c(0xFFA82A06),
                        ),
                        floatArrayOf(0f, .5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                })
            } else {
                cv.drawCircle(x, y, r, fill(c(0xFF2B1610)))
                cv.drawCircle(x - r * .3f, y - r * .3f, r * .32f, fill(c(0x38FFFFFF)))
            }
        }
    }

    private fun tachDial(cv: Canvas, s: DashState, secSweep: Float) {
        val step = secSweep / 60f
        val full = secSweep >= 359f
        cv.drawCircle(CX, CY, 296f, stroke(c(0xFFCFC8BA), 6f))
        cv.drawCircle(CX, CY, 293f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                CX, CY - 80f, 360f,
                intArrayOf(c(0xFF2C2925), c(0xFF141311), c(0xFF050505)),
                floatArrayOf(0f, .6f, 1f),
                Shader.TileMode.CLAMP,
            )
        })

        // Colour band: black -> green -> yellow -> orange -> red, starting at second 0.
        val band = SweepGradient(
            CX, CY,
            intArrayOf(
                c(0xFF0E0E0E), c(0xFF173017), c(0xFF22B536), c(0xFF8BD62E),
                c(0xFFF2D21C), c(0xFFFF8A1A), RED, RED,
            ),
            floatArrayOf(0f, .1f, .25f, .45f, .6f, .74f, .84f, 1f).map { it * secSweep / 360f }.toFloatArray(),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(SEC_ZERO_DEG - 90f, CX, CY) }) }
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f
            shader = band
        }
        if (full) cv.drawCircle(CX, CY, 270f, bandPaint)
        else cv.drawArc(oval(CX, CY, 270f), SEC_ZERO_DEG - 90f, secSweep, false, bandPaint)
        // Redline seam where 60 meets 0.
        if (full) cv.radialLine(CX, CY, SEC_ZERO_DEG, 258f, 283f, stroke(c(0xFF050505), 4f))

        for (sec in 0 until (if (full) 60 else 61)) {
            val deg = SEC_ZERO_DEG + sec * step
            val major = sec % 5 == 0
            cv.radialLine(CX, CY, deg, 256f, if (major) 226f else 243f, stroke(CREAM, if (major) 5f else 2.2f))
        }
        val num = textPaint(CREAM, 40f, SANS_BOLD)
        for (k in 0..(if (full) 5 else 6)) {
            val deg = SEC_ZERO_DEG + k * 10 * step
            cv.textCentered((k * 10).toString(), ax(CX, 198f, deg), ay(CY, 198f, deg), num)
        }

        // Gear window.
        val gy = if (full) 0f else 10f
        val win = RectF(462f, 352f + gy, 538f, 414f + gy)
        cv.drawRoundRect(win, 10f, 10f, fill(c(0xFF060906)))
        cv.drawRoundRect(win, 10f, 10f, stroke(c(0xFF6B655C), 3f))
        val gearColor = if (s.gear == "N") GREEN else AMBER
        cv.textCentered(s.gear, CX, 383f + gy, textPaint(gearColor, 50f, MONO_BOLD).apply {
            setShadowLayer(12f, 0f, 0f, gearColor)
        })
        val topP = textPaint(if (s.topGear) AMBER else c(0x2EF4F0E6), 15f, SANS_BOLD).apply {
            letterSpacing = .15f
            if (s.topGear) setShadowLayer(8f, 0f, 0f, AMBER)
        }
        cv.textCentered("TOP GEAR", CX, 437f + gy * 0.6f, topP)

        // Head-light tell-tale beside the gear window.
        cv.headlightIcon(414f, 383f + gy, 34f, if (s.headlights) c(0xFF3D7BFF) else c(0x26F4F0E6))
        val dx = if (full) 0f else -22f // keep clear of the "50" in 270° mode
        val date = RectF(580f + dx, 481f, 680f + dx, 519f)
        cv.drawRoundRect(date, 5f, 5f, fill(c(0xFFF2EADA)))
        cv.drawRoundRect(date, 5f, 5f, stroke(c(0xFF8E8578), 3f))
        val windowText = textPaint(c(0xFF151210), 21f, MONO_BOLD)
        cv.textCentered(s.dateShort, 630f + dx, 500f, windowText)

        // Month and year windows, left of the hub.
        for ((r, txt) in listOf(RectF(340f, 481f, 398f, 519f) to s.month, RectF(402f, 481f, 464f, 519f) to s.year)) {
            cv.drawRoundRect(r, 5f, 5f, fill(c(0xFFF2EADA)))
            cv.drawRoundRect(r, 5f, 5f, stroke(c(0xFF8E8578), 3f))
            cv.textCentered(txt, r.centerX(), r.centerY(), windowText)
        }

        cv.textCentered("SEC", CX, 553f, textPaint(c(0xB3F4F0E6), 15f, SANS_BOLD).apply { letterSpacing = .12f })
        cv.textCentered("SPEEDOCLOCK", CX, 577f, textPaint(c(0xFFD9A441), 20f, SERIF_BOLD_ITALIC).apply { letterSpacing = .14f })

        fuelGauge(cv, s, 500f, 662f)
    }

    private fun fuelGauge(cv: Canvas, s: DashState, fx: Float, fy: Float) {
        val segs = 8
        val lit = if (s.fuel <= 0f) 0 else ceil(s.fuel * segs).toInt()
        val span = 110f / segs
        for (k in 0 until segs) {
            val d0 = -55f + k * span + 1.5f
            val color = when {
                k >= lit -> c(0xFF2A2622)
                k == 0 -> RED
                else -> AMBER
            }
            val p = fill(color).apply { if (k < lit) setShadowLayer(6f, 0f, 0f, color) }
            cv.drawPath(bandSegment(fx, fy, 38f, 52f, d0, d0 + span - 3f), p)
        }
        cv.textCentered("E", ax(fx, 46f, -75f), ay(fy, 46f, -75f), textPaint(RED, 17f, SANS_BOLD))
        cv.textCentered("F", ax(fx, 46f, 75f), ay(fy, 46f, 75f), textPaint(CREAM, 17f, SANS_BOLD))
        cv.pumpIcon(fx, fy + 4f, 20f, if (s.fuel < 0.15f) AMBER else c(0x66F4F0E6))
    }
}
