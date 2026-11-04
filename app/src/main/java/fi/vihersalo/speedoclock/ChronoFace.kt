package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient

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

    /** Where second 0 sits: at the top for the widget (needle_chrono.xml rests pointing up)... */
    const val SEC_ZERO_FULL = 0f
    /** ...and lower left for the 270° screensaver sweep (GaugeView turns the needle to match). */
    const val SEC_ZERO_270 = 225f

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

    fun draw(cv: Canvas, s: DashState, secSweep: Float = 360f, inner: Boolean = true) {
        bezel(cv)
        hourRing(cv, s)
        minuteLeds(cv, s)
        if (inner) tachDial(cv, s, secSweep)
        else cv.drawCircle(CX, CY, 297f, fill(c(0xFF030303))) // backdrop for the flip
    }

    /** The face with an empty centre: the widget puts the flipping dial (seconds <-> moon) there. */
    fun renderOuter(s: DashState, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / SIZE, px / SIZE)
        draw(cv, s, inner = false)
        return bmp
    }

    /** Fraction of the face the inner dial square covers (600 of 1000 units). */
    const val INNER_FRACTION = 0.6f

    /** Just the seconds dial, on a 600 x 600 square centred on the hub. */
    fun renderInner(s: DashState, px: Int, secSweep: Float = 360f): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / 600f, px / 600f)
        cv.translate(-200f, -200f)
        tachDial(cv, s, secSweep)
        return bmp
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
        val sweep = s.minuteOfDay / 1440f * 360f
        val sun = s.sunElev
        if (sun != null) {
            // The sky through the day: night, blue hour, golden hour, daylight.
            val step = 360f / Sun.STEPS
            for (i in 0 until Sun.STEPS) {
                val col = Sun.skyColor((sun[i] + sun[i + 1]) / 2f)
                cv.drawArc(oval(CX, CY, 455f), -90f + i * step, step + 0.4f, false, stroke(col, 20f))
            }
            // Hours still to come sit a little in shadow, so the band also reads as progress.
            if (sweep < 360f) {
                cv.drawArc(oval(CX, CY, 455f), -90f + sweep, 360f - sweep, false, stroke(c(0x59000000), 20f))
            }
            cv.drawCircle(CX, CY, 465f, stroke(c(0x99301A08), 1.5f))
            cv.drawCircle(CX, CY, 445f, stroke(c(0x99301A08), 1.5f))
            // Elapsed day as a thin red line just inside the sky.
            cv.drawCircle(CX, CY, 440f, stroke(c(0x33301A08), 5f))
            if (sweep > 0f) {
                cv.drawArc(oval(CX, CY, 440f), -90f, sweep, false, stroke(c(0xFFB8261A), 5f).apply {
                    setShadowLayer(5f, 0f, 0f, c(0xAAFF3A1A))
                })
            }
        } else {
            cv.drawCircle(CX, CY, 452f, stroke(c(0x33301A08), 18f))
            if (sweep > 0f) {
                cv.drawArc(oval(CX, CY, 452f), -90f, sweep, false, stroke(c(0xFFB8261A), 18f).apply {
                    setShadowLayer(8f, 0f, 0f, c(0xAAFF3A1A))
                })
                cv.drawArc(oval(CX, CY, 457f), -90f, sweep, false, stroke(c(0x55FFFFFF), 3f))
            }
        }
        if (sweep > 0f) {
            // bright "now" tip
            cv.radialLine(CX, CY, sweep, 437f, 466f, stroke(c(0xFFFFFFFF), 4f).apply {
                setShadowLayer(4f, 0f, 0f, c(0xAA000000))
            })
        }
        if (sun == null) {
            cv.drawCircle(CX, CY, 442f, stroke(c(0xFF6E3C0E), 2f))
            cv.drawCircle(CX, CY, 462f, stroke(c(0x886E3C0E), 1.5f))
        }

        // Ticks every 15 minutes.
        for (i in 0 until 96) {
            val (len, w) = when {
                i % 4 == 0 -> 30f to 5f
                i % 2 == 0 -> 17f to 3f
                else -> 9f to 2f
            }
            val t0 = if (sun != null) 435f else 440f
            cv.radialLine(CX, CY, i * 3.75f, t0, t0 - len, stroke(INK, w))
        }

        // Numerals 0..23 (0 at the top, where the day starts).
        val big = textPaint(INK, 44f, SERIF_BOLD).apply { setShadowLayer(2f, 0f, 1f, c(0x55FFF0C0)) }
        val small = textPaint(INK, 30f, SERIF_BOLD)
        for (h in 1..24) {
            val deg = h * 15f
            cv.textCentered((h % 24).toString(), ax(CX, 380f, deg), ay(CY, 380f, deg), if (h % 2 == 0) big else small)
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
        val zero = if (full) SEC_ZERO_FULL else SEC_ZERO_270
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
        ).apply { setLocalMatrix(Matrix().apply { setRotate(zero - 90f, CX, CY) }) }
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f
            shader = band
        }
        if (full) cv.drawCircle(CX, CY, 270f, bandPaint)
        else cv.drawArc(oval(CX, CY, 270f), zero - 90f, secSweep, false, bandPaint)
        // Redline seam where 60 meets 0.
        if (full) cv.radialLine(CX, CY, zero, 258f, 283f, stroke(c(0xFF050505), 4f))

        for (sec in 0 until (if (full) 60 else 61)) {
            val deg = zero + sec * step
            val major = sec % 5 == 0
            cv.radialLine(CX, CY, deg, 256f, if (major) 226f else 243f, stroke(CREAM, if (major) 5f else 2.2f))
        }
        val num = textPaint(CREAM, 40f, SANS_BOLD)
        for (k in 0..(if (full) 5 else 6)) {
            val deg = zero + k * 10 * step
            cv.textCentered((k * 10).toString(), ax(CX, 198f, deg), ay(CY, 198f, deg), num)
        }

        // Three small LCD screens in a row above the hub.
        val wy = if (full) 0f else 10f
        val (w, gap) = if (full) 74f to 15f else 66f to 11f
        val top = 348f + wy
        val xs = listOf(CX - w * 1.5f - gap, CX - w / 2, CX + w / 2 + gap)
        listOf(Panels.Slot.LEFT, Panels.Slot.CENTER, Panels.Slot.RIGHT).forEachIndexed { i, slot ->
            Panels.small(cv, RectF(xs[i], top, xs[i] + w, top + 64f), s.slots.getValue(slot), s)
        }

        // Windows: day and week on the left, month and year on the right.
        val windowText = textPaint(c(0xFF151210), 20f, MONO_BOLD)
        val windows = listOf(
            RectF(352f, 462f, 452f, 498f) to s.dateShort,
            RectF(352f, 504f, 452f, 540f) to s.week,
            RectF(548f, 462f, 648f, 498f) to s.month,
            RectF(548f, 504f, 648f, 540f) to s.year,
        )
        for ((r, txt) in windows) {
            cv.drawRoundRect(r, 5f, 5f, fill(c(0xFFF2EADA)))
            cv.drawRoundRect(r, 5f, 5f, stroke(c(0xFF8E8578), 3f))
            cv.textCentered(txt, r.centerX(), r.centerY(), windowText)
        }

        cv.textCentered("SEC", CX, 553f, textPaint(c(0xB3F4F0E6), 15f, SANS_BOLD).apply { letterSpacing = .12f })
        cv.textCentered("SPEEDOCLOCK", CX, 577f, textPaint(c(0xFFD9A441), 20f, SERIF_BOLD_ITALIC).apply { letterSpacing = .14f })

        // Wide screen below the hub.
        Panels.wide(cv, RectF(398f, 594f, 602f, 658f), s.slots.getValue(Panels.Slot.BOTTOM), s)
    }
}
