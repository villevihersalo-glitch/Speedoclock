package fi.vihersalo.speedoclock

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.min

/** User-tunable day settings, all in minutes after midnight. */
data class DashConfig(
    val workStart: Int = 9 * 60,
    val workEnd: Int = 17 * 60,
    val dayStart: Int = 7 * 60,
    val dayEnd: Int = 23 * 60,
)

/** Everything a face needs to draw one minute of the day. Seconds are handled by the live needle. */
data class DashState(
    val hour: Int,
    val minute: Int,
    /** "N" outside work, "1".."5" through the working day. */
    val gear: String,
    val topGear: Boolean,
    /** 1 = full tank (start of day), 0 = empty (end of day). */
    val fuel: Float,
    val headlights: Boolean,
    val dateLong: String,
    val dateShort: String,
    /** ISO week, "WK 45" */
    val week: String,
    /** "NOV" */
    val month: String,
    /** "2026" */
    val year: String,
    /** Time left of the working day ("2:18"), or "FREE". */
    val workLeft: String,
) {
    val minuteOfDay: Int get() = hour * 60 + minute

    companion object {
        fun now(ctx: Context) = at(LocalDateTime.now(), Prefs.config(ctx))

        fun at(t: LocalDateTime, cfg: DashConfig): DashState {
            val mod = t.hour * 60 + t.minute
            val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
            val workLen = cfg.workEnd - cfg.workStart
            val atWork = !weekend && workLen > 0 && mod >= cfg.workStart && mod < cfg.workEnd

            val gearNo = if (atWork) min(5, 1 + ((mod - cfg.workStart) * 5) / workLen) else 0
            val dayLen = cfg.dayEnd - cfg.dayStart
            val fuel = when {
                dayLen <= 0 -> 1f - mod / 1440f
                mod < cfg.dayStart -> 1f
                mod >= cfg.dayEnd -> 0f
                else -> 1f - (mod - cfg.dayStart).toFloat() / dayLen
            }
            val dow = t.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
                .uppercase(Locale.US)
            val left = cfg.workEnd - mod
            return DashState(
                hour = t.hour,
                minute = t.minute,
                gear = if (gearNo == 0) "N" else gearNo.toString(),
                topGear = gearNo == 5,
                fuel = fuel.coerceIn(0f, 1f),
                headlights = t.hour >= 18 || t.hour < 6,
                dateLong = String.format(Locale.US, "%s %02d.%02d", dow, t.dayOfMonth, t.monthValue),
                dateShort = String.format(Locale.US, "%s %d", dow, t.dayOfMonth),
                week = "WK " + t.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                month = t.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US).uppercase(Locale.US),
                year = t.year.toString(),
                workLeft = if (atWork) String.format(Locale.US, "%d:%02d", left / 60, left % 60) else "FREE",
            )
        }
    }
}

object Prefs {
    const val WORK_START = "work_start"
    const val WORK_END = "work_end"
    const val DAY_START = "day_start"
    const val DAY_END = "day_end"

    private val defaults = DashConfig()

    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Int = sp(c).getInt(
        key,
        when (key) {
            WORK_START -> defaults.workStart
            WORK_END -> defaults.workEnd
            DAY_START -> defaults.dayStart
            else -> defaults.dayEnd
        }
    )

    fun set(c: Context, key: String, minutes: Int) = sp(c).edit().putInt(key, minutes).apply()

    fun config(c: Context) = DashConfig(
        workStart = get(c, WORK_START),
        workEnd = get(c, WORK_END),
        dayStart = get(c, DAY_START),
        dayEnd = get(c, DAY_END),
    )
}
