package fi.vihersalo.speedoclock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Today's (or, in the evening, tomorrow's) forecast. Temperatures in °C. */
data class Weather(val min: Float, val max: Float, val code: Int, val rainPct: Int?, val tomorrow: Boolean)

/** Exchange rate: 1 [base] = [rate] [quote]. [history] is the last ~2 weeks of daily rates, oldest first. */
data class Fx(val base: String, val quote: String, val rate: Double, val history: List<Double>)

/**
 * Small online feeds, both free and keyless:
 *  - weather: Open-Meteo (open-meteo.com)
 *  - currency: Frankfurter (frankfurter.dev), European Central Bank reference rates, updated on working days
 * Each is fetched only if a screen shows it, and cached so the widget never waits for the network.
 */
object Feeds {
    private const val WX_MAX_AGE = 30 * 60_000L
    private const val FX_MAX_AGE = 3 * 60 * 60_000L
    private const val RETRY = 5 * 60_000L

    val CURRENCIES = listOf(
        "EUR", "THB", "USD", "GBP", "SEK", "NOK", "DKK", "CHF", "JPY", "CNY",
        "SGD", "MYR", "AUD", "CAD", "INR", "KRW", "HKD", "IDR", "PHP", "PLN",
    )

    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)

    fun fxPair(c: Context): Pair<String, String> =
        (sp(c).getString("fx_base", "EUR") ?: "EUR") to (sp(c).getString("fx_quote", "THB") ?: "THB")

    fun setFxPair(c: Context, base: String, quote: String) =
        sp(c).edit().putString("fx_base", base).putString("fx_quote", quote)
            .remove("fx_json").remove("fx_time").remove("fx_try").apply()

    /** Forget the cached forecast (e.g. after moving). */
    fun clearWeather(c: Context) = sp(c).edit().remove("wx_json").remove("wx_time").remove("wx_try").apply()

    // ---- reading the cache (fast, used on every redraw) ----

    fun weather(c: Context, t: LocalDateTime): Weather? = parseWeather(sp(c).getString("wx_json", null), t)

    fun parseWeather(json: String?, t: LocalDateTime): Weather? = try {
        val d = JSONObject(json ?: return null).getJSONObject("daily")
        val days = d.getJSONArray("time")
        // After 18:00 the rest of today is mostly behind you: show tomorrow.
        val tomorrow = t.hour >= 18
        val want = (if (tomorrow) t.toLocalDate().plusDays(1) else t.toLocalDate()).toString()
        val i = (0 until days.length()).firstOrNull { days.getString(it) == want }
        if (i == null) null else Weather(
            min = d.getJSONArray("temperature_2m_min").getDouble(i).toFloat(),
            max = d.getJSONArray("temperature_2m_max").getDouble(i).toFloat(),
            code = d.getJSONArray("weather_code").getInt(i),
            rainPct = d.optJSONArray("precipitation_probability_max")?.let { if (it.isNull(i)) null else it.getInt(i) },
            tomorrow = tomorrow,
        )
    } catch (_: Exception) {
        null
    }

    fun fx(c: Context): Fx? = parseFx(sp(c).getString("fx_json", null), fxPair(c))

    fun parseFx(json: String?, pair: Pair<String, String>): Fx? = try {
        val rates = JSONObject(json ?: return null).getJSONObject("rates")
        val days = rates.keys().asSequence().sorted().toList()
        val hist = days.mapNotNull { day -> rates.getJSONObject(day).optDouble(pair.second).takeIf { !it.isNaN() } }
        if (hist.isEmpty()) null else Fx(pair.first, pair.second, hist.last(), hist)
    } catch (_: Exception) {
        null
    }

    // ---- refreshing (blocking; call from a background thread) ----

    /** Returns true if anything new arrived. */
    fun refresh(c: Context): Boolean {
        val now = System.currentTimeMillis()
        val sp = sp(c)
        val cfg = Prefs.config(c)
        val used = Panels.layout(c).values.toSet()
        var changed = false

        if (cfg.lat != null && cfg.lon != null && (Panels.Source.TEMP in used || Panels.Source.WEATHER in used) &&
            now - sp.getLong("wx_time", 0) > WX_MAX_AGE && now - sp.getLong("wx_try", 0) > RETRY
        ) {
            sp.edit().putLong("wx_try", now).apply()
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2",
                cfg.lat, cfg.lon,
            )
            get(url)?.takeIf { it.contains("\"daily\"") }?.let {
                sp.edit().putString("wx_json", it).putLong("wx_time", now).apply()
                changed = true
            }
        }

        if (Panels.Source.CURRENCY in used &&
            now - sp.getLong("fx_time", 0) > FX_MAX_AGE && now - sp.getLong("fx_try", 0) > RETRY
        ) {
            sp.edit().putLong("fx_try", now).apply()
            val (base, quote) = fxPair(c)
            val start = LocalDate.now().minusDays(16)
            val body = if (base == quote) {
                """{"rates":{"${LocalDate.now()}":{"$quote":1.0}}}"""
            } else {
                get("https://api.frankfurter.dev/v1/$start..?base=$base&symbols=$quote")
                    ?: get("https://api.frankfurter.app/$start..?from=$base&to=$quote")
            }
            if (body != null && parseFx(body, base to quote) != null) {
                sp.edit().putString("fx_json", body).putLong("fx_time", now).apply()
                changed = true
            }
        }
        return changed
    }

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "Speedoclock/0.1 (Android)")
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Daily steps from the phone's own step counter (no account, no Google Fit). The sensor counts since
 * the last reboot, so we keep a baseline per day and carry steps over a reboot.
 */
object Steps {
    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun available(c: Context) =
        c.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Steps today, or null if we can't count (no sensor / no permission yet). */
    fun today(c: Context): Int? {
        if (!hasPermission(c) || !available(c)) return null
        val sp = sp(c)
        return if (sp.getLong("st_day", -1) == LocalDate.now().toEpochDay()) sp.getInt("st_today", 0) else 0
    }

    /** Reads the sensor once (waits up to 3 s). Returns true if today's count changed. */
    fun read(c: Context): Boolean {
        if (!hasPermission(c)) return false
        val sm = c.getSystemService(SensorManager::class.java) ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        val latch = CountDownLatch(1)
        var value = -1f
        val thread = HandlerThread("steps").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                value = e.values[0]
                latch.countDown()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
        latch.await(3, TimeUnit.SECONDS)
        sm.unregisterListener(listener)
        thread.quitSafely()
        if (value < 0) return false
        return record(c, value.toLong(), LocalDate.now().toEpochDay())
    }

    /** Pure bookkeeping, separate so it can be unit-tested. */
    fun record(c: Context, counter: Long, day: Long): Boolean {
        val sp = sp(c)
        var base = sp.getLong("st_base", counter)
        var acc = sp.getInt("st_acc", 0)
        val last = sp.getLong("st_last", -1)
        val lastDay = sp.getLong("st_last_day", -1)
        val storedDay = sp.getLong("st_day", -1)

        if (last in 0..Long.MAX_VALUE && counter < last) { // phone rebooted: counter restarted at 0
            acc += (last - base).toInt().coerceAtLeast(0)
            base = 0
        }
        if (storedDay != day) {
            // New day: count from the last reading of yesterday if we have one, else from now.
            base = if (lastDay == day - 1 && last in 0..counter) last else counter
            acc = 0
        }
        val today = acc + (counter - base).toInt().coerceAtLeast(0)
        val old = if (storedDay == day) sp.getInt("st_today", -1) else -1
        sp.edit()
            .putLong("st_base", base).putInt("st_acc", acc)
            .putLong("st_last", counter).putLong("st_last_day", day)
            .putLong("st_day", day).putInt("st_today", today)
            .apply()
        return today != old
    }
}
