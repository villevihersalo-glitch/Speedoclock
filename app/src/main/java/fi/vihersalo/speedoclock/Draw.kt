package fi.vihersalo.speedoclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/*
 * Shared drawing helpers. Angles are "clock degrees": 0 = 12 o'clock, growing clockwise,
 * which is also how AnalogClock rotates its hands.
 */

internal fun c(v: Long): Int = v.toInt()

internal fun ax(cx: Float, r: Float, deg: Float) =
    cx + r * sin(Math.toRadians(deg.toDouble())).toFloat()

internal fun ay(cy: Float, r: Float, deg: Float) =
    cy - r * cos(Math.toRadians(deg.toDouble())).toFloat()

internal fun oval(cx: Float, cy: Float, r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)

internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

internal fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = width
    this.color = color
}

internal fun textPaint(
    color: Int,
    size: Float,
    tf: Typeface = Typeface.DEFAULT,
    align: Paint.Align = Paint.Align.CENTER,
) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    this.color = color
    textSize = size
    typeface = tf
    textAlign = align
}

internal val SERIF_BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
internal val SERIF_BOLD_ITALIC: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
internal val SANS_BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
internal val SANS_BOLD_ITALIC: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
internal val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

/** Draws text whose visual centre sits on (x, y). */
internal fun Canvas.textCentered(text: String, x: Float, y: Float, p: Paint) {
    val fm = p.fontMetrics
    drawText(text, x, y - (fm.ascent + fm.descent) / 2f, p)
}

internal fun Canvas.radialLine(cx: Float, cy: Float, deg: Float, r0: Float, r1: Float, p: Paint) {
    drawLine(ax(cx, r0, deg), ay(cy, r0, deg), ax(cx, r1, deg), ay(cy, r1, deg), p)
}

/** A ring segment between two radii and two clock angles. */
internal fun bandSegment(cx: Float, cy: Float, rIn: Float, rOut: Float, deg0: Float, deg1: Float): Path {
    val start = deg0 - 90f
    val sweep = deg1 - deg0
    return Path().apply {
        arcTo(oval(cx, cy, rOut), start, sweep, true)
        arcTo(oval(cx, cy, rIn), start + sweep, -sweep, false)
        close()
    }
}

/** Head-light tell-tale: a "D" lamp with three beams. */
internal fun Canvas.headlightIcon(x: Float, y: Float, size: Float, color: Int) {
    val p = fill(color)
    val r = size * 0.42f
    val lamp = Path().apply {
        moveTo(x + size * 0.05f, y - r)
        arcTo(RectF(x + size * 0.05f - r, y - r, x + size * 0.05f + r, y + r), -90f, 180f, false)
        close()
    }
    drawPath(lamp, p)
    val beam = stroke(color, size * 0.11f).apply { strokeCap = Paint.Cap.ROUND }
    for (i in -1..1) {
        val by = y + i * size * 0.3f
        drawLine(x - size * 0.5f, by, x - size * 0.12f, by, beam)
    }
}

/** Fuel-pump tell-tale. */
internal fun Canvas.pumpIcon(x: Float, y: Float, size: Float, color: Int) {
    val p = fill(color)
    drawRoundRect(RectF(x - size * 0.4f, y - size * 0.5f, x + size * 0.12f, y + size * 0.5f), size * 0.06f, size * 0.06f, p)
    drawRect(RectF(x - size * 0.3f, y - size * 0.38f, x + size * 0.02f, y - size * 0.1f), fill(0x55000000))
    val hose = stroke(color, size * 0.1f).apply { strokeCap = Paint.Cap.ROUND }
    drawLine(x + size * 0.12f, y - size * 0.2f, x + size * 0.36f, y - size * 0.05f, hose)
    drawLine(x + size * 0.36f, y - size * 0.05f, x + size * 0.36f, y + size * 0.35f, hose)
}

/** Seven-segment style character, with dim "ghost" segments like a real LCD. Supports 1-5, 8 and N. */
internal object SevenSeg {
    private val lit = mapOf(
        '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '8' to "abcdefg", 'N' to "efbc",
    )

    fun draw(cv: Canvas, ch: Char, cx: Float, cy: Float, w: Float, h: Float, t: Float, on: Int, ghost: Int) {
        cv.save()
        cv.translate(cx, cy)
        cv.skew(-0.08f, 0f)
        val x0 = -w / 2; val x1 = w / 2; val y0 = -h / 2; val y1 = h / 2
        val segs = mapOf(
            'a' to hSeg(x0, x1, y0, t), 'g' to hSeg(x0, x1, 0f, t), 'd' to hSeg(x0, x1, y1, t),
            'f' to vSeg(x0, y0, 0f, t), 'e' to vSeg(x0, 0f, y1, t),
            'b' to vSeg(x1, y0, 0f, t), 'c' to vSeg(x1, 0f, y1, t),
        )
        val ghostP = fill(ghost)
        segs.values.forEach { cv.drawPath(it, ghostP) }
        val onP = fill(on).apply { setShadowLayer(t * 0.8f, 0f, 0f, on) }
        val active = lit[ch] ?: ""
        active.forEach { segs[it]?.let { path -> cv.drawPath(path, onP) } }
        if (ch == 'N') {
            val diag = stroke(on, t * 0.95f).apply {
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(t * 0.8f, 0f, 0f, on)
            }
            cv.drawLine(x0 + t * 0.7f, y0 + t * 0.9f, x1 - t * 0.7f, y1 - t * 0.9f, diag)
        }
        cv.restore()
    }

    private fun hSeg(xa: Float, xb: Float, y: Float, t: Float): Path {
        val g = t * 0.18f
        val a = xa + g; val b = xb - g; val h = t / 2
        return Path().apply {
            moveTo(a, y); lineTo(a + h, y - h); lineTo(b - h, y - h)
            lineTo(b, y); lineTo(b - h, y + h); lineTo(a + h, y + h); close()
        }
    }

    private fun vSeg(x: Float, ya: Float, yb: Float, t: Float): Path {
        val g = t * 0.18f
        val a = ya + g; val b = yb - g; val h = t / 2
        return Path().apply {
            moveTo(x, a); lineTo(x + h, a + h); lineTo(x + h, b - h)
            lineTo(x, b); lineTo(x - h, b - h); lineTo(x - h, a + h); close()
        }
    }
}
