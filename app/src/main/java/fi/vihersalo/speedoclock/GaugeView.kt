package fi.vihersalo.speedoclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-screen gauge for the screensaver and nightstand mode. Unlike the widget, this view animates its
 * own needle, so it can do a real 270° tacho sweep: smooth climb through the minute, then the needle
 * drops back to 0 like the revs falling when you lift off.
 */
@SuppressLint("ViewConstructor")
class GaugeView(context: Context, private val face: Face) : View(context) {

    enum class Face { CHRONO, LCD }

    private val sweep = 270f
    private val needle: Drawable =
        context.getDrawable(if (face == Face.CHRONO) R.drawable.needle_chrono else R.drawable.needle_lcd)!!
    private val glare: Drawable =
        context.getDrawable(if (face == Face.CHRONO) R.drawable.glare_chrono else R.drawable.glare_lcd)!!
    private val timePaint = textPaint(c(0xFF5CFF7A), 10f, MONO_BOLD)

    private var faceBmp: Bitmap? = null
    private var faceKey = -1L
    private var state: DashState? = null
    private val rect = RectF()
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 33) // ~30 fps is plenty for a needle
        }
    }

    fun start() {
        if (running) return
        running = true
        post(frame)
    }

    fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val aspect = if (face == Face.CHRONO) 1f else LcdFace.W / LcdFace.H
        val availW = w * 0.92f
        val availH = h * 0.92f
        val fw = min(availW, availH * aspect)
        val fh = fw / aspect
        rect.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
        faceKey = -1L // re-render at the new size
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (rect.isEmpty) return
        val now = System.currentTimeMillis()

        // Redraw the face bitmap once a minute (or after a resize / settings change).
        val key = now / 60_000 * 31 + Prefs.config(context).hashCode()
        if (key != faceKey || faceBmp == null) {
            val st = DashState.now(context)
            state = st
            faceBmp?.recycle()
            faceBmp = if (face == Face.CHRONO) {
                ChronoFace.render(st, rect.width().roundToInt(), sweep)
            } else {
                LcdFace.render(st, rect.width().roundToInt(), rect.height().roundToInt(), sweep)
            }
            faceKey = key
        }

        // Burn-in protection: wander a few pixels every minute.
        val drift = burnInOffset(now)
        canvas.save()
        canvas.translate(drift.first, drift.second)

        canvas.drawBitmap(faceBmp!!, rect.left, rect.top, null)

        val unit = rect.width() / (if (face == Face.CHRONO) ChronoFace.SIZE else LcdFace.W)
        val hubX = rect.centerX()
        val hubY = rect.centerY()

        if (face == Face.LCD) {
            val cal = Calendar.getInstance()
            timePaint.textSize = LcdFace.TIME_TEXT_SIZE * 1.15f * unit
            timePaint.setShadowLayer(8f * unit, 0f, 0f, c(0xAA2BFF5A))
            val txt = String.format(
                Locale.US, "%02d:%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
            )
            canvas.textCentered(txt, hubX, hubY + LcdFace.TIME_OFFSET_Y * unit, timePaint)
        }

        // Needle: the drawables rest at second 0, so rotate by the sweep angle.
        val half = (if (face == Face.CHRONO) 500f else 150f) * unit
        needle.setBounds((hubX - half).toInt(), (hubY - half).toInt(), (hubX + half).toInt(), (hubY + half).toInt())
        canvas.save()
        canvas.rotate(needleSeconds(now) * sweep / 60f, hubX, hubY)
        needle.draw(canvas)
        canvas.restore()

        glare.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        glare.draw(canvas)
        canvas.restore()

        // Headlights on = night: dim the whole screen.
        if (state?.headlights == true) canvas.drawColor(c(0x8C000000))
    }

    /** 0..60, smooth. The first [DROP] seconds of a minute the needle falls back from 60 to 0. */
    private fun needleSeconds(now: Long): Float {
        val t = (now % 60_000) / 1000f
        return if (t < DROP) {
            val p = t / DROP
            60f * (1f - p).pow(3) // fast at first, settling into 0
        } else {
            (t - DROP) / (60f - DROP) * 60f
        }
    }

    private fun burnInOffset(now: Long): Pair<Float, Float> {
        val r = 6f * resources.displayMetrics.density
        val step = (now / 60_000 % 8).toInt()
        val dx = floatArrayOf(0f, 1f, 1f, 0f, -1f, -1f, 0f, 0.5f)[step] * r
        val dy = floatArrayOf(0f, 0f, 1f, 1f, 0f, -1f, -1f, 0.5f)[step] * r
        return dx to dy
    }

    companion object {
        private const val DROP = 0.6f
    }
}
