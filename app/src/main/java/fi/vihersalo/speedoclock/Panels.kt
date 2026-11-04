package fi.vihersalo.speedoclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Chrono face's little LCD screens. Each screen (slot) can show any source; the user picks in the app.
 * Small screens sit in a row above the hub, the wide one below it.
 */
object Panels {

    enum class Slot(val label: String) { LEFT("Left screen"), CENTER("Middle screen"), RIGHT("Right screen"), BOTTOM("Bottom screen") }

    enum class Source(val label: String) {
        GEAR("Gear"),
        STEPS("Steps today"),
        TEMP("Min / max temperature"),
        WEATHER("Weather forecast"),
        CURRENCY("Currency exchange"),
        FUEL("Fuel (day left)"),
        WORK("Work time left"),
        SUN("Sunrise / sunset"),
    }

    private val defaults = mapOf(
        Slot.LEFT to Source.TEMP,
        Slot.CENTER to Source.GEAR,
        Slot.RIGHT to Source.WEATHER,
        Slot.BOTTOM to Source.CURRENCY,
    )

    val DEFAULT_LAYOUT: Map<Slot, Source> = defaults

    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)

    fun layout(c: Context): Map<Slot, Source> = Slot.values().associateWith { slot ->
        sp(c).getString("slot_" + slot.name, null)?.let { n -> Source.values().firstOrNull { it.name == n } }
            ?: defaults.getValue(slot)
    }

    fun set(c: Context, slot: Slot, src: Source) = sp(c).edit().putString("slot_" + slot.name, src.name).apply()

    // ---------------------------------------------------------------- drawing

    private val BG = c(0xFF060906)
    private val RIM = c(0xFF6B655C)
    private val CAPTION = c(0x8CF4F0E6)
    private val DIM = c(0x40F4F0E6)
    private val GREEN = c(0xFF39E46A)
    private val AMBER = c(0xFFFFB43A)
    private val RED = c(0xFFFF4A2E)
    private val HOT = c(0xFFFF7A45)
    private val COLD = c(0xFF5CC8FF)
    private val WHITE = c(0xFFEFF4F2)

    fun windowFrame(cv: Canvas, r: RectF) {
        cv.drawRoundRect(r, 10f, 10f, fill(BG))
        cv.drawRoundRect(r, 10f, 10f, stroke(RIM, 3f))
        // faint glass sheen on the top half
        cv.drawRoundRect(RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.centerY()), 7f, 7f, fill(c(0x0DFFFFFF)))
    }

    /** Short caption shown above a small screen. */
    fun caption(src: Source, s: DashState): String = when (src) {
        Source.GEAR -> "GEAR"
        Source.STEPS -> "STEPS"
        Source.TEMP -> if (s.weather?.tomorrow == true) "TMRW °C" else "TEMP °C"
        Source.WEATHER -> if (s.weather?.tomorrow == true) "TMRW" else "TODAY"
        Source.CURRENCY -> s.fx?.let { "${it.base}·${it.quote}" } ?: "FX"
        Source.FUEL -> "FUEL"
        Source.WORK -> if (s.workLeft == "FREE") "WORK" else "WORK LEFT"
        Source.SUN -> sunEvent(s)?.first ?: "SUN"
    }

    /** A small screen with its caption above. */
    fun small(cv: Canvas, r: RectF, src: Source, s: DashState) {
        cv.textCentered(caption(src, s), r.centerX(), r.top - 13f, textPaint(CAPTION, 12.5f, SANS_BOLD).apply { letterSpacing = .14f })
        windowFrame(cv, r)
        val inner = RectF(r.left + 6f, r.top + 4f, r.right - 6f, r.bottom - 4f)
        when (src) {
            Source.GEAR -> {
                val col = when (s.gear) { "N" -> GREEN; "5" -> RED; else -> AMBER }
                glowText(cv, s.gear, inner, 50f, col)
            }
            Source.TEMP -> {
                val w = s.weather
                if (w == null) glowText(cv, "--", inner, 34f, DIM) else {
                    val top = RectF(inner.left, inner.top, inner.right, inner.centerY())
                    val bot = RectF(inner.left, inner.centerY(), inner.right, inner.bottom)
                    glowText(cv, "▲" + deg(w.max), top, 24f, HOT)
                    glowText(cv, "▼" + deg(w.min), bot, 24f, COLD)
                }
            }
            Source.WEATHER -> {
                val w = s.weather
                if (w == null) glowText(cv, "--", inner, 34f, DIM)
                else WeatherIcons.draw(cv, w.code, inner.centerX(), inner.centerY() + 1f, inner.height() * 0.92f)
            }
            Source.FUEL -> fuelBars(cv, RectF(inner.left + 4f, inner.top + 8f, inner.right - 4f, inner.bottom - 6f), s.fuel, 5)
            else -> glowText(cv, value(src, s), inner, 34f, valueColor(src, s))
        }
    }

    /** The wide screen below the hub. */
    fun wide(cv: Canvas, r: RectF, src: Source, s: DashState) {
        windowFrame(cv, r)
        val cap = textPaint(CAPTION, 12.5f, SANS_BOLD, Paint.Align.LEFT).apply { letterSpacing = .14f }
        val left = r.left + 12f
        when (src) {
            Source.CURRENCY -> {
                val fx = s.fx
                cv.drawText(fx?.let { "${it.base} → ${it.quote}" } ?: "CURRENCY", left, r.top + 18f, cap)
                if (fx == null) {
                    glowText(cv, "--", RectF(left, r.top + 20f, r.right - 12f, r.bottom - 4f), 30f, DIM)
                    return
                }
                val valueBox = RectF(left - 2f, r.top + 20f, r.left + r.width() * 0.6f, r.bottom - 4f)
                glowText(cv, rate(fx.rate), valueBox, 32f, GREEN, Paint.Align.LEFT)
                // Week trend as a little LCD sparkline.
                val chart = RectF(r.left + r.width() * 0.62f, r.top + 10f, r.right - 12f, r.bottom - 12f)
                sparkline(cv, chart, fx.history)
            }
            Source.FUEL -> {
                cv.drawText("FUEL · DAY LEFT", left, r.top + 18f, cap)
                fuelBars(cv, RectF(left + 14f, r.top + 24f, r.right - 26f, r.bottom - 10f), s.fuel, 10)
                cv.drawText("E", left, r.bottom - 12f, textPaint(RED, 15f, SANS_BOLD, Paint.Align.LEFT))
                cv.drawText("F", r.right - 20f, r.bottom - 12f, textPaint(WHITE, 15f, SANS_BOLD, Paint.Align.LEFT))
            }
            Source.WEATHER, Source.TEMP -> {
                val w = s.weather
                cv.drawText(if (w?.tomorrow == true) "TOMORROW" else "TODAY", left, r.top + 18f, cap)
                if (w == null) {
                    glowText(cv, "--", RectF(left, r.top + 20f, r.right - 12f, r.bottom - 4f), 30f, DIM)
                    return
                }
                WeatherIcons.draw(cv, w.code, r.left + 40f, r.centerY() + 6f, r.height() * 0.62f)
                val box = RectF(r.left + 74f, r.top + 18f, r.right - 10f, r.bottom - 4f)
                val t = "▲" + deg(w.max) + " ▼" + deg(w.min)
                glowText(cv, t, RectF(box.left, box.top, box.right, box.bottom - (if (w.rainPct != null) 14f else 0f)), 24f, WHITE)
                w.rainPct?.let {
                    cv.textCentered("RAIN $it%", box.centerX(), box.bottom - 9f, textPaint(COLD, 12.5f, SANS_BOLD).apply { letterSpacing = .1f })
                }
            }
            else -> {
                cv.drawText(caption(src, s), left, r.top + 18f, cap)
                glowText(cv, value(src, s), RectF(left, r.top + 18f, r.right - 12f, r.bottom - 4f), 32f, valueColor(src, s))
            }
        }
    }

    private fun value(src: Source, s: DashState): String = when (src) {
        Source.GEAR -> s.gear
        Source.STEPS -> s.steps?.toString() ?: "--"
        Source.CURRENCY -> s.fx?.let { rate(it.rate) } ?: "--"
        Source.FUEL -> "${(s.fuel * 100).roundToInt()}%"
        Source.WORK -> s.workLeft
        Source.SUN -> sunEvent(s)?.second ?: "--"
        Source.TEMP -> s.weather?.let { deg(it.max) + "/" + deg(it.min) } ?: "--"
        Source.WEATHER -> "--"
    }

    private fun valueColor(src: Source, s: DashState): Int = when (src) {
        Source.GEAR -> if (s.gear == "N") GREEN else if (s.gear == "5") RED else AMBER
        Source.STEPS -> if (s.steps == null) DIM else WHITE
        Source.WORK -> if (s.workLeft == "FREE") GREEN else AMBER
        Source.SUN -> AMBER
        else -> GREEN
    }

    /** Next sun event today: ("SUNRISE", "06:15") before sunrise, ("SUNSET", "17:50") during the day. */
    private fun sunEvent(s: DashState): Pair<String, String>? {
        val e = s.sunElev ?: return null
        val rise = Sun.crossing(e, -0.833f, true)
        val set = Sun.crossing(e, -0.833f, false)
        val m = s.minuteOfDay
        fun hm(v: Int) = String.format(Locale.US, "%02d:%02d", v / 60, v % 60)
        return when {
            rise != null && m < rise -> "SUNRISE" to hm(rise)
            set != null && m < set -> "SUNSET" to hm(set)
            rise != null -> "SUNRISE" to hm(rise) // tomorrow's is within a minute or two of today's
            else -> null
        }
    }

    private fun deg(v: Float) = "${v.roundToInt()}°"

    fun rate(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.0f", v)
        v >= 100 -> String.format(Locale.US, "%.1f", v)
        v >= 10 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.3f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }

    /** Text that shrinks to fit [box], with an LCD glow. */
    private fun glowText(cv: Canvas, text: String, box: RectF, maxSize: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        val p = textPaint(color, maxSize, MONO_BOLD, align)
        while (p.textSize > 10f && (p.measureText(text) > box.width() || p.textSize > box.height() * 0.95f)) p.textSize -= 1f
        p.setShadowLayer(p.textSize * 0.28f, 0f, 0f, color)
        val x = when (align) { Paint.Align.LEFT -> box.left; Paint.Align.RIGHT -> box.right; else -> box.centerX() }
        cv.textCentered(text, x, box.centerY(), p)
    }

    private fun fuelBars(cv: Canvas, box: RectF, fuel: Float, segs: Int) {
        val lit = if (fuel <= 0f) 0 else ceil(fuel * segs).toInt()
        val gap = box.width() * 0.06f / segs * 2
        val w = (box.width() - gap * (segs - 1)) / segs
        for (k in 0 until segs) {
            val x0 = box.left + k * (w + gap)
            val h = box.height() * (0.45f + 0.55f * k / (segs - 1).coerceAtLeast(1))
            val col = when {
                k >= lit -> c(0xFF1E211E)
                k == 0 -> RED
                else -> AMBER
            }
            val p = fill(col).apply { if (k < lit) setShadowLayer(5f, 0f, 0f, col) }
            cv.drawRect(RectF(x0, box.bottom - h, x0 + w, box.bottom), p)
        }
    }

    private fun sparkline(cv: Canvas, box: RectF, values: List<Double>) {
        // faint LCD grid
        val grid = stroke(c(0x1A39E46A), 1f)
        for (i in 0..2) cv.drawLine(box.left, box.top + box.height() * i / 2, box.right, box.top + box.height() * i / 2, grid)
        if (values.size < 2) return
        val lo = values.min(); val hi = values.max()
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = box.left + box.width() * i / (values.size - 1)
            val y = box.bottom - ((v - lo) / span * box.height()).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val up = values.last() >= values[values.size - 2]
        val col = if (up) GREEN else RED
        cv.drawPath(path, stroke(col, 3f).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(4f, 0f, 0f, col)
        })
        val ly = box.bottom - ((values.last() - lo) / span * box.height()).toFloat()
        cv.drawCircle(box.right, ly, 4f, fill(col))
        // change vs the previous day
        val prev = values[values.size - 2]
        val pct = if (prev != 0.0) (values.last() - prev) / prev * 100 else 0.0
        val txt = (if (up) "▲" else "▼") + String.format(Locale.US, "%.1f%%", abs(pct))
        cv.drawText(txt, box.right, box.bottom + 11f, textPaint(col, 11f, SANS_BOLD, Paint.Align.RIGHT))
    }
}

/** LCD-style weather symbols for WMO weather codes (as used by Open-Meteo). */
object WeatherIcons {
    private val SUN = c(0xFFFFB43A)
    private val CLOUD = c(0xFFE6ECEF)
    private val DARK_CLOUD = c(0xFF9AA3AA)
    private val RAIN = c(0xFF5CC8FF)
    private val BOLT = c(0xFFFFE04A)

    fun draw(cv: Canvas, code: Int, cx: Float, cy: Float, size: Float) {
        val u = size / 10f // icon drawn on a 10 x 10 grid
        when (code) {
            0 -> sun(cv, cx, cy, u * 2.6f, u)
            1, 2 -> { sun(cv, cx - u * 1.4f, cy - u * 1.3f, u * 1.9f, u); cloud(cv, cx + u * 0.6f, cy + u * 1.0f, u * 0.8f, CLOUD) }
            3 -> { cloud(cv, cx - u * 1.2f, cy - u * 0.9f, u * 0.6f, DARK_CLOUD); cloud(cv, cx + u * 0.5f, cy + u * 0.7f, u * 0.85f, CLOUD) }
            45, 48 -> fog(cv, cx, cy, u)
            in 51..57 -> { cloud(cv, cx, cy - u * 1.2f, u * 0.85f, CLOUD); drops(cv, cx, cy + u * 2.2f, u, 3, short = true) }
            in 61..67 -> { cloud(cv, cx, cy - u * 1.2f, u * 0.85f, DARK_CLOUD); drops(cv, cx, cy + u * 2.2f, u, 3, short = false) }
            in 71..77, 85, 86 -> { cloud(cv, cx, cy - u * 1.2f, u * 0.85f, CLOUD); flakes(cv, cx, cy + u * 2.4f, u) }
            in 80..82 -> {
                sun(cv, cx - u * 1.6f, cy - u * 2.0f, u * 1.6f, u)
                cloud(cv, cx + u * 0.4f, cy - u * 0.6f, u * 0.8f, CLOUD)
                drops(cv, cx + u * 0.4f, cy + u * 2.6f, u, 2, short = false)
            }
            in 95..99 -> { cloud(cv, cx, cy - u * 1.3f, u * 0.85f, DARK_CLOUD); bolt(cv, cx, cy + u * 2.1f, u) }
            else -> cloud(cv, cx, cy, u * 0.85f, CLOUD)
        }
    }

    private fun sun(cv: Canvas, x: Float, y: Float, r: Float, u: Float) {
        val p = fill(SUN).apply { setShadowLayer(u * 0.8f, 0f, 0f, SUN) }
        cv.drawCircle(x, y, r * 0.55f, p)
        val ray = stroke(SUN, u * 0.42f).apply { strokeCap = Paint.Cap.ROUND; setShadowLayer(u * 0.5f, 0f, 0f, SUN) }
        for (i in 0 until 8) cv.radialLine(x, y, i * 45f, r * 0.75f, r * 1.0f, ray)
    }

    /** A cloud roughly 6 x 3.6 grid units at scale 1, centred on (x, y). */
    private fun cloud(cv: Canvas, x: Float, y: Float, k: Float, color: Int) {
        val u = k * 1f
        val p = fill(color).apply { setShadowLayer(u * 0.6f, 0f, 0f, c(0x66000000)) }
        val path = Path().apply {
            addCircle(x - u * 1.5f, y + u * 0.4f, u * 1.4f, Path.Direction.CW)
            addCircle(x + u * 0.2f, y - u * 0.6f, u * 2.0f, Path.Direction.CW)
            addCircle(x + u * 1.9f, y + u * 0.5f, u * 1.3f, Path.Direction.CW)
            addRoundRect(RectF(x - u * 2.9f, y + u * 0.2f, x + u * 3.2f, y + u * 1.8f), u * 0.8f, u * 0.8f, Path.Direction.CW)
        }
        cv.drawPath(path, p)
    }

    private fun drops(cv: Canvas, x: Float, y: Float, u: Float, n: Int, short: Boolean) {
        val p = stroke(RAIN, u * 0.42f).apply { strokeCap = Paint.Cap.ROUND; setShadowLayer(u * 0.5f, 0f, 0f, RAIN) }
        val len = if (short) u * 0.6f else u * 1.3f
        for (i in 0 until n) {
            val dx = (i - (n - 1) / 2f) * u * 1.6f
            cv.drawLine(x + dx + u * 0.3f, y - len / 2, x + dx - u * 0.3f, y + len / 2, p)
        }
    }

    private fun flakes(cv: Canvas, x: Float, y: Float, u: Float) {
        val p = stroke(CLOUD, u * 0.28f).apply { strokeCap = Paint.Cap.ROUND }
        for (i in -1..1) {
            val fx = x + i * u * 1.7f
            for (a in 0 until 3) cv.radialLine(fx, y, a * 60f, -u * 0.6f, u * 0.6f, p)
        }
    }

    private fun bolt(cv: Canvas, x: Float, y: Float, u: Float) {
        val path = Path().apply {
            moveTo(x + u * 0.4f, y - u * 1.4f); lineTo(x - u * 0.9f, y + u * 0.2f); lineTo(x, y + u * 0.2f)
            lineTo(x - u * 0.5f, y + u * 1.6f); lineTo(x + u * 1.0f, y - u * 0.3f); lineTo(x + u * 0.1f, y - u * 0.3f)
            close()
        }
        cv.drawPath(path, fill(BOLT).apply { setShadowLayer(u * 0.7f, 0f, 0f, BOLT) })
    }

    private fun fog(cv: Canvas, x: Float, y: Float, u: Float) {
        val p = stroke(DARK_CLOUD, u * 0.55f).apply { strokeCap = Paint.Cap.ROUND }
        for (i in -1..1) {
            val off = if (i == 0) u * 0.6f else 0f
            cv.drawLine(x - u * 3f + off, y + i * u * 1.4f, x + u * 3f - off, y + i * u * 1.4f, p)
        }
    }
}
