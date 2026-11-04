package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {

    private val out = File("build/previews").apply { mkdirs() }
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val bkk = ZoneId.of("Asia/Bangkok")

    private val weatherJson = """
        {"daily":{"time":["2026-11-04","2026-11-05"],
         "weather_code":[2,61],"temperature_2m_max":[33.4,31.0],"temperature_2m_min":[24.2,23.6],
         "precipitation_probability_max":[20,70]}}
    """.trimIndent()

    private val fxJson = """
        {"amount":1.0,"base":"EUR","rates":{
         "2026-10-21":{"THB":37.92},"2026-10-22":{"THB":38.05},"2026-10-23":{"THB":37.98},
         "2026-10-26":{"THB":38.10},"2026-10-27":{"THB":38.21},"2026-10-28":{"THB":38.15},
         "2026-10-29":{"THB":38.30},"2026-10-30":{"THB":38.26},"2026-11-02":{"THB":38.41},
         "2026-11-03":{"THB":38.37},"2026-11-04":{"THB":38.52}}}
    """.trimIndent()

    private fun state(t: LocalDateTime, slots: Map<Panels.Slot, Panels.Source> = Panels.DEFAULT_LAYOUT): DashState {
        val cfg = DashConfig(lat = 13.75, lon = 100.50)
        return DashState.at(t, cfg, bkk).copy(
            weather = Feeds.parseWeather(weatherJson, t),
            fx = Feeds.parseFx(fxJson, "EUR" to "THB"),
            steps = 6482,
            slots = slots,
        )
    }

    @Test
    fun parsers() {
        val day = Feeds.parseWeather(weatherJson, LocalDateTime.of(2026, 11, 4, 9, 27))!!
        assertEquals(33.4f, day.max, 0.01f)
        assertEquals(false, day.tomorrow)
        val eve = Feeds.parseWeather(weatherJson, LocalDateTime.of(2026, 11, 4, 19, 0))!!
        assertEquals(61, eve.code)
        assertEquals(true, eve.tomorrow)
        val fx = Feeds.parseFx(fxJson, "EUR" to "THB")!!
        assertEquals(38.52, fx.rate, 1e-9)
        assertEquals(11, fx.history.size)
    }

    @Test
    fun stepsBookkeeping() {
        val day = 20_000L
        Steps.record(ctx, 10_000, day)          // first reading ever: baseline
        Steps.record(ctx, 10_500, day)
        assertEquals(500, sp().getInt("st_today", -1))
        Steps.record(ctx, 200, day)             // reboot: counter restarted
        assertEquals(700, sp().getInt("st_today", -1))
        Steps.record(ctx, 260, day + 1)         // next day starts from yesterday's last reading
        assertEquals(60, sp().getInt("st_today", -1))
    }

    private fun sp() = ctx.getSharedPreferences("speedoclock", 0)

    @Test
    fun moonPhaseMatchesKnownFullMoon() {
        // Full moon 24 Nov 2026, 14:53 UTC
        val age = MoonPhase.age(Instant.parse("2026-11-24T14:53:00Z").toEpochMilli())
        assertTrue("age $age", age in 0.47..0.53)
        assertEquals("FULL MOON", MoonPhase.name(age))
    }

    @Test
    fun renderScreens() {
        val t = LocalDateTime.of(2026, 11, 4, 9, 27, 0)
        save(face(state(t), 17), "screens_default.png")

        val alt = mapOf(
            Panels.Slot.LEFT to Panels.Source.SUN,
            Panels.Slot.CENTER to Panels.Source.STEPS,
            Panels.Slot.RIGHT to Panels.Source.WORK,
            Panels.Slot.BOTTOM to Panels.Source.WEATHER,
        )
        save(face(state(LocalDateTime.of(2026, 11, 4, 14, 41, 0), alt), 40), "screens_alt.png")

        val alt2 = mapOf(
            Panels.Slot.LEFT to Panels.Source.CURRENCY,
            Panels.Slot.CENTER to Panels.Source.GEAR,
            Panels.Slot.RIGHT to Panels.Source.FUEL,
            Panels.Slot.BOTTOM to Panels.Source.FUEL,
        )
        save(face(state(LocalDateTime.of(2026, 11, 4, 16, 30, 0), alt2), 5), "screens_alt2.png")

        // No data yet (fresh install, no location)
        val bare = DashState.at(t, DashConfig()).copy(steps = null)
        save(face(bare, 17), "screens_nodata.png")
    }

    @Test
    fun earthPosition() {
        // At the March equinox the Sun is at 0° seen from Earth, so Earth is at 180° from the Sun.
        val lon = Orrery.longitude("EARTH", Instant.parse("2026-03-20T14:46:00Z").toEpochMilli())
        assertTrue("earth $lon", lon in 178.5..181.5)
    }

    @Test
    fun renderSolar() {
        val st = state(LocalDateTime.of(2026, 11, 4, 10, 10, 0))
        val face = ChronoFace.renderOuter(st, 1000)
        val fc = Canvas(face)
        fc.drawBitmap(Orrery.render(st.epochMs, 600), 200f, 200f, null)
        save(face, "widget_solar.png")
        save(Orrery.render(st.epochMs, 1200), "solar_big.png")
    }

    @Test
    fun renderMoon() {
        val dates = listOf(
            "crescent" to "2026-11-13T12:00:00Z",
            "quarter" to "2026-11-17T12:00:00Z",
            "gibbous" to "2026-11-20T12:00:00Z",
            "full" to "2026-11-24T15:00:00Z",
            "waning" to "2026-11-29T12:00:00Z",
            "today" to "2026-11-04T07:27:00Z",
        )
        val row = Bitmap.createBitmap(600 * dates.size, 600, Bitmap.Config.ARGB_8888)
        val rc = Canvas(row)
        dates.forEachIndexed { i, (_, iso) ->
            val m = MoonFace.render(Instant.parse(iso).toEpochMilli(), 13.75, 600)
            rc.drawBitmap(m, i * 600f, 0f, null)
        }
        save(row, "moon_phases.png")

        // The widget with the centre flipped to the moon.
        val st = state(LocalDateTime.of(2026, 11, 4, 9, 27, 0))
        val face = ChronoFace.renderOuter(st, 1000)
        val moon = MoonFace.render(st.epochMs, st.lat, 600)
        val fc = Canvas(face)
        fc.drawBitmap(moon, 200f, 200f, null)
        val glare = ctx.getDrawable(R.drawable.glare_chrono)!!
        glare.setBounds(0, 0, 1000, 1000)
        glare.draw(fc)
        save(face, "widget_moon.png")
    }

    /** Widget as the launcher would show it: outer face + inner dial + needles + glass. */
    private fun face(st: DashState, sec: Int): Bitmap {
        val bmp = ChronoFace.renderOuter(st, 1000)
        val cv = Canvas(bmp)
        cv.drawBitmap(ChronoFace.renderInner(st, 600), 200f, 200f, null)
        fun vec(res: Int, deg: Float) = ctx.getDrawable(res)!!.let {
            it.setBounds(0, 0, 1000, 1000)
            cv.save(); cv.rotate(deg, 500f, 500f); it.draw(cv); cv.restore()
        }
        vec(R.drawable.needle_chrono, sec * 6f)
        vec(R.drawable.pin_needle, 120f)
        vec(R.drawable.glare_chrono, 0f)
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(bmp)
    }
}
