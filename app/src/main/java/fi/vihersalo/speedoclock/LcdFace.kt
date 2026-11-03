package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil

/**
 * Bike-style LCD cluster (design cues from 90s motorcycle dashes):
 *  - stepped "speedo" bar arc = hours 0..24
 *  - ring of 60 dots = minutes
 *  - centre tacho = seconds (red needle is the AnalogClock second hand, needle_lcd.xml)
 *  - digital HH:mm:ss = TextClock overlaid by the widget
 *  - left: lamps + big gear digit, right: work countdown + fuel bars
 * Drawn on a 1000 x 500 canvas, hub at the centre (500, 250).
 */
object LcdFace {
    const val W = 1000f
    const val H = 500f
    private const val CX = 500f
    private const val CY = 250f
    const val SEC_ZERO_DEG = 225f

    /** Where the overlaid digital clock sits, in face units below the hub, and its text size. */
    const val TIME_OFFSET_Y = 62f
    const val TIME_TEXT_SIZE = 27f

    private val GHOST = c(0xFF1B2A45)
    private val LABEL = c(0xFF8FA6C8)
    private val GREEN = c(0xFF5CFF7A)
    private val WHITE = c(0xFFEAF4FF)
    private val RED = c(0xFFFF3B3B)
    private val AMBER = c(0xFFFFB22E)

    fun render(s: DashState, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(wPx / W, hPx / H)
        draw(cv, s)
        return bmp
    }

    fun draw(cv: Canvas, s: DashState) {
        housing(cv)
        hourArc(cv, s)
        minuteDots(cv, s)
        secondsScale(cv)
        leftColumn(cv, s)
        rightColumn(cv, s)
    }

    private fun housing(cv: Canvas) {
        val outer = RectF(0f, 0f, W, H)
        cv.drawRoundRect(outer, 70f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, H, c(0xFF3A3D42), c(0xFF0F1012), Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(RectF(2f, 2f, W - 2f, H - 2f), 68f, 68f, stroke(c(0x44FFFFFF), 3f))
        val win = RectF(18f, 18f, W - 18f, H - 18f)
        cv.drawRoundRect(win, 52f, 52f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 18f, 0f, H - 18f, c(0xFF13233F), c(0xFF070E1C), Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(win, 52f, 52f, stroke(c(0xFF000000), 5f))
    }

    /** 24 stepped segments sweeping 270 deg from lower-left, over the top, to lower-right. */
    private fun hourArc(cv: Canvas, s: DashState) {
        val step = 270f / 24f
        for (k in 0 until 24) {
            val d0 = SEC_ZERO_DEG + k * step + 1f
            val path = bandSegment(CX, CY, 172f, 192f + k * 1.2f, d0, d0 + step - 2f)
            val color = when {
                k < 6 -> WHITE
                k < 12 -> c(0xFF54D8FF)
                k < 18 -> c(0xFFD3F24A)
                else -> AMBER
            }
            when {
                k < s.hour -> cv.drawPath(path, fill(color).apply { setShadowLayer(9f, 0f, 0f, color) })
                k == s.hour -> {
                    cv.drawPath(path, fill(GHOST))
                    val a = (90 + 165 * s.minute / 59).coerceIn(0, 255)
                    cv.drawPath(path, fill(color).apply { alpha = a })
                }
                else -> cv.drawPath(path, fill(GHOST))
            }
        }
        val lp = textPaint(LABEL, 19f, SANS_BOLD)
        for (v in 0..24 step 3) {
            val deg = SEC_ZERO_DEG + v * step
            cv.textCentered(v.toString(), ax(CX, 151f, deg), ay(CY, 151f, deg), lp)
        }
    }

    private fun minuteDots(cv: Canvas, s: DashState) {
        for (k in 0 until 60) {
            val deg = k * 6f
            val x = ax(CX, 126f, deg); val y = ay(CY, 126f, deg)
            val r = if (k % 5 == 0) 5.5f else 4f
            if (k <= s.minute) {
                val col = if (k == s.minute) WHITE else GREEN
                cv.drawCircle(x, y, r, fill(col).apply { setShadowLayer(6f, 0f, 0f, GREEN) })
            } else {
                cv.drawCircle(x, y, r, fill(GHOST))
            }
        }
    }

    private fun secondsScale(cv: Canvas) {
        for (sec in 0 until 60 step 5) {
            val deg = SEC_ZERO_DEG + sec * 6f
            val len = if (sec % 15 == 0) 15f else 10f
            cv.radialLine(CX, CY, deg, 106f, 106f - len, stroke(if (sec >= 50) RED else LABEL, 3.5f))
        }
        // Red zone, last 10 seconds.
        val start = SEC_ZERO_DEG + 50 * 6f - 90f
        cv.drawArc(oval(CX, CY, 108f), start, 60f, false, stroke(RED, 5f).apply { setShadowLayer(6f, 0f, 0f, RED) })
        cv.textCentered("×10s", CX, 206f, textPaint(LABEL, 14f, SANS_BOLD).apply { letterSpacing = .1f })
    }

    private fun leftColumn(cv: Canvas, s: DashState) {
        // Head-light lamp
        val hl = RectF(48f, 44f, 138f, 96f)
        if (s.headlights) {
            cv.drawRoundRect(hl, 10f, 10f, fill(c(0xFF1E5BFF)).apply { setShadowLayer(10f, 0f, 0f, c(0xFF1E5BFF)) })
            cv.headlightIcon(hl.centerX(), hl.centerY(), 36f, WHITE)
        } else {
            cv.drawRoundRect(hl, 10f, 10f, stroke(GHOST, 3f))
            cv.headlightIcon(hl.centerX(), hl.centerY(), 36f, GHOST)
        }
        // TOP GEAR lamp
        val tg = RectF(150f, 44f, 262f, 96f)
        val tgText = if (s.topGear) c(0xFF1A1200) else GHOST
        if (s.topGear) {
            cv.drawRoundRect(tg, 10f, 10f, fill(c(0xFFFFD21E)).apply { setShadowLayer(10f, 0f, 0f, c(0xFFFFD21E)) })
        } else {
            cv.drawRoundRect(tg, 10f, 10f, stroke(GHOST, 3f))
        }
        val tp = textPaint(tgText, 18f, SANS_BOLD)
        cv.textCentered("TOP", tg.centerX(), tg.centerY() - 10f, tp)
        cv.textCentered("GEAR", tg.centerX(), tg.centerY() + 11f, tp)

        cv.textCentered("GEAR", 155f, 142f, textPaint(LABEL, 18f, SANS_BOLD).apply { letterSpacing = .3f })
        val ch = s.gear.firstOrNull() ?: 'N'
        SevenSeg.draw(cv, ch, 155f, 250f, 92f, 166f, 19f, if (ch == 'N') GREEN else AMBER, c(0xFF14233A))

        cv.textCentered(s.dateLong, 155f, 404f, textPaint(LABEL, 25f, MONO_BOLD))
    }

    private fun rightColumn(cv: Canvas, s: DashState) {
        cv.textCentered("hrs", 856f, 76f, textPaint(WHITE, 34f, SANS_BOLD_ITALIC))

        val atWork = s.workLeft != "FREE"
        cv.textCentered(if (atWork) "WORK LEFT" else "AFTER WORK", 856f, 152f,
            textPaint(LABEL, 16f, SANS_BOLD).apply { letterSpacing = .15f })
        val vColor = if (atWork) WHITE else GREEN
        cv.textCentered(s.workLeft, 856f, 200f, textPaint(vColor, 46f, MONO_BOLD).apply {
            setShadowLayer(8f, 0f, 0f, vColor)
        })

        // Fuel: 8 bars rising from E (red) to F.
        val segs = 8
        val lit = if (s.fuel <= 0f) 0 else ceil(s.fuel * segs).toInt()
        val base = 418f
        for (k in 0 until segs) {
            val x0 = 762f + k * 24f
            val h = 18f + k * 4.6f
            val col = when {
                k >= lit -> GHOST
                k == 0 -> RED
                else -> WHITE
            }
            val p = fill(col).apply { if (k < lit) setShadowLayer(6f, 0f, 0f, col) }
            cv.drawRect(RectF(x0, base - h, x0 + 19f, base), p)
        }
        cv.textCentered("E", 744f, base - 10f, textPaint(RED, 24f, SANS_BOLD))
        cv.textCentered("F", 968f, base - 24f, textPaint(WHITE, 24f, SANS_BOLD))
        cv.pumpIcon(790f, 452f, 26f, if (s.fuel < 0.15f) AMBER else LABEL)
        cv.textCentered("FUEL", 860f, 452f, textPaint(LABEL, 16f, SANS_BOLD).apply { letterSpacing = .3f })
    }
}
