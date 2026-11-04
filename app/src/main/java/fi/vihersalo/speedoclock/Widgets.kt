package fi.vihersalo.speedoclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.min

/**
 * How the widget stays alive cheaply:
 *  - The seconds needle (and the LCD's HH:mm:ss) are AnalogClock / TextClock views. The launcher
 *    animates those itself, so the app does no per-second work at all.
 *  - Everything else is a bitmap re-rendered once a minute by a non-waking alarm. When the screen
 *    is off nothing runs; the alarm fires as soon as the phone wakes up.
 */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = WidgetUpdater.updateAll(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        WidgetUpdater.updateAll(ctx)

    override fun onEnabled(ctx: Context) = WidgetUpdater.updateAll(ctx)
    override fun onDisabled(ctx: Context) = WidgetUpdater.scheduleNext(ctx)
    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetUpdater.scheduleNext(ctx)
}

class ChronoWidget : BaseWidget()
class LcdWidget : BaseWidget()

/** Minute tick, system time changes, boot, and taps on the Chrono's centre. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetUpdater.ACTION_FLIP) {
            WidgetUpdater.toggleMoon(ctx)
            WidgetUpdater.updateAll(ctx)
            return
        }
        // Draw from cached data right away, then refresh steps / weather / rates in the background.
        WidgetUpdater.updateAll(ctx)
        val pending = goAsync()
        WidgetUpdater.refreshInBackground(ctx) { pending.finish() }
    }
}

object WidgetUpdater {
    const val ACTION_FLIP = "fi.vihersalo.speedoclock.FLIP"
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)
    /** Centre page: 0 = seconds dial, 1 = moon, 2 = solar system. */
    fun centerPage(c: Context) = sp(c).getInt("center_page", 0).coerceIn(0, 2)
    fun moonShown(c: Context) = centerPage(c) != 0
    fun toggleMoon(c: Context) = sp(c).edit().putInt("center_page", (centerPage(c) + 1) % 3).apply()

    /** Reads the step counter and refreshes stale feeds off the main thread, then redraws if anything changed. */
    fun refreshInBackground(context: Context, done: () -> Unit = {}) {
        val ctx = context.applicationContext
        if (!refreshing.compareAndSet(false, true)) {
            done(); return
        }
        Thread {
            try {
                val usesSteps = Panels.Source.STEPS in Panels.layout(ctx).values
                val a = usesSteps && Steps.read(ctx)
                val b = Feeds.refresh(ctx)
                if (a || b) updateAll(ctx)
            } catch (_: Exception) {
            } finally {
                refreshing.set(false)
                done()
            }
        }.start()
    }

    fun updateAll(context: Context) {
        val ctx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val st = DashState.now(ctx)
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, ChronoWidget::class.java))) {
            mgr.updateAppWidget(id, chrono(ctx, mgr, id, st))
        }
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, LcdWidget::class.java))) {
            mgr.updateAppWidget(id, lcd(ctx, mgr, id, st))
        }
        scheduleNext(ctx)
    }

    private fun chrono(ctx: Context, mgr: AppWidgetManager, id: Int, st: DashState): RemoteViews {
        val size = sizeDp(mgr, id, SizeF(180f, 180f))
        val density = ctx.resources.displayMetrics.density
        val sideDp = min(size.width, size.height)
        val px = (sideDp * density).toInt().coerceIn(240, 760)
        val innerPx = (px * ChronoFace.INNER_FRACTION).toInt()
        val moon = moonShown(ctx)
        return RemoteViews(ctx.packageName, R.layout.widget_chrono).apply {
            setImageViewBitmap(R.id.face, ChronoFace.renderOuter(st, px))
            setImageViewBitmap(R.id.dial, ChronoFace.renderInner(st, innerPx))
            setImageViewBitmap(R.id.moon, MoonFace.render(st.epochMs, st.lat, innerPx))
            setImageViewBitmap(R.id.solar, Orrery.render(st.epochMs, innerPx))
            val flipDp = sideDp * ChronoFace.INNER_FRACTION
            setViewLayoutWidth(R.id.flip, flipDp, TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.flip, flipDp, TypedValue.COMPLEX_UNIT_DIP)
            setDisplayedChild(R.id.flip, centerPage(ctx))
            val hands = if (moon) android.view.View.GONE else android.view.View.VISIBLE
            setViewVisibility(R.id.needle, hands)
            setViewVisibility(R.id.pin, hands)
            // Centre flips to the moon; anywhere else (the hour ring) opens the app.
            setOnClickPendingIntent(R.id.flip, flipIntent(ctx))
            setOnClickPendingIntent(R.id.root, openApp(ctx))
        }
    }

    private fun lcd(ctx: Context, mgr: AppWidgetManager, id: Int, st: DashState): RemoteViews {
        val size = sizeDp(mgr, id, SizeF(320f, 160f))
        // dp per face unit once the face is fitted (aspect kept) into the widget.
        val s = min(size.width / LcdFace.W, size.height / LcdFace.H)
        val density = ctx.resources.displayMetrics.density
        val wPx = (LcdFace.W * s * density).toInt().coerceIn(400, 1100)
        return RemoteViews(ctx.packageName, R.layout.widget_lcd).apply {
            setImageViewBitmap(R.id.face, LcdFace.render(st, wPx, wPx / 2))
            setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_DIP, LcdFace.TIME_TEXT_SIZE * s)
            setViewLayoutMargin(R.id.time, RemoteViews.MARGIN_TOP, LcdFace.TIME_OFFSET_Y * s, TypedValue.COMPLEX_UNIT_DIP)
            setOnClickPendingIntent(R.id.root, openApp(ctx))
        }
    }

    @Suppress("DEPRECATION")
    private fun sizeDp(mgr: AppWidgetManager, id: Int, fallback: SizeF): SizeF {
        val o = mgr.getAppWidgetOptions(id)
        val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        if (!sizes.isNullOrEmpty()) return sizes[0]
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (w > 0 && h > 0) SizeF(w.toFloat(), h.toFloat()) else fallback
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun flipIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 2,
        Intent(ctx, TickReceiver::class.java).setAction(ACTION_FLIP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, TickReceiver::class.java).setAction("fi.vihersalo.speedoclock.TICK"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun hasWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return mgr.getAppWidgetIds(ComponentName(ctx, ChronoWidget::class.java)).isNotEmpty() ||
            mgr.getAppWidgetIds(ComponentName(ctx, LcdWidget::class.java)).isNotEmpty()
    }

    fun scheduleNext(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = tickIntent(ctx)
        if (!hasWidgets(ctx)) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val next = (now / 60_000 + 1) * 60_000 + 30
        // RTC (not _WAKEUP): never wakes the phone, so no battery cost while the screen is off.
        if (am.canScheduleExactAlarms()) {
            am.setExact(AlarmManager.RTC, next, pi)
        } else {
            am.setWindow(AlarmManager.RTC, next, 20_000, pi)
        }
    }
}
