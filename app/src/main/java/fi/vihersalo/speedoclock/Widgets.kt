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

/** Minute tick + system time changes + boot. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = WidgetUpdater.updateAll(ctx)
}

object WidgetUpdater {

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
        val px = (min(size.width, size.height) * density).toInt().coerceIn(240, 760)
        return RemoteViews(ctx.packageName, R.layout.widget_chrono).apply {
            setImageViewBitmap(R.id.face, ChronoFace.render(st, px))
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
