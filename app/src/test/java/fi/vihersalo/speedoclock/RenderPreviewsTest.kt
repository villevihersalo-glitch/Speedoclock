package fi.vihersalo.speedoclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/**
 * Renders both faces to app/build/previews so the designs can be checked without a phone.
 * CI uploads that folder as the "previews" artifact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPreviewsTest {

    private val out = File("build/previews").apply { mkdirs() }
    private val ctx get() = RuntimeEnvironment.getApplication()

    private val samples = listOf(
        "workday" to LocalDateTime.of(2026, 11, 3, 14, 41, 0) to 40,
        "topgear" to LocalDateTime.of(2026, 11, 3, 16, 12, 0) to 55,
        "evening" to LocalDateTime.of(2026, 11, 3, 21, 5, 0) to 12,
    )

    @Test
    fun gearAndFuelLogic() {
        val cfg = DashConfig()
        assertEquals("N", DashState.at(LocalDateTime.of(2026, 11, 3, 8, 0), cfg).gear)
        assertEquals("1", DashState.at(LocalDateTime.of(2026, 11, 3, 9, 0), cfg).gear)
        assertEquals("5", DashState.at(LocalDateTime.of(2026, 11, 3, 16, 59), cfg).gear)
        assertEquals("N", DashState.at(LocalDateTime.of(2026, 11, 3, 17, 0), cfg).gear)
        assertEquals("N", DashState.at(LocalDateTime.of(2026, 11, 7, 12, 0), cfg).gear) // Saturday
        assertEquals(1f, DashState.at(LocalDateTime.of(2026, 11, 3, 6, 0), cfg).fuel, 0.001f)
        assertEquals(0.5f, DashState.at(LocalDateTime.of(2026, 11, 3, 15, 0), cfg).fuel, 0.001f)
        assertEquals(0f, DashState.at(LocalDateTime.of(2026, 11, 3, 23, 30), cfg).fuel, 0.001f)
    }

    @Test
    fun renderChrono() {
        for ((pair, sec) in samples) {
            val (name, t) = pair
            val st = DashState.at(t, DashConfig())
            val bmp = ChronoFace.render(st, 1000)
            val cv = Canvas(bmp)
            overlay(cv, R.drawable.needle_chrono, 0, 0, 1000, 1000, 500f, 500f, sec)
            overlayDeg(cv, R.drawable.pin_needle, 0, 0, 1000, 1000, 500f, 500f, 120f)
            overlay(cv, R.drawable.glare_chrono, 0, 0, 1000, 1000, 500f, 500f, null)
            save(bmp, "chrono_$name.png")
        }
    }

    @Test
    fun renderLcd() {
        for ((pair, sec) in samples) {
            val (name, t) = pair
            val st = DashState.at(t, DashConfig())
            val bmp = LcdFace.render(st, 1000, 500)
            val cv = Canvas(bmp)
            // Stand-in for the TextClock overlay.
            val tp = textPaint(c(0xFF5CFF7A), LcdFace.TIME_TEXT_SIZE * 1.15f, MONO_BOLD).apply {
                setShadowLayer(8f, 0f, 0f, c(0xAA2BFF5A))
            }
            val txt = String.format("%02d:%02d:%02d", t.hour, t.minute, sec)
            cv.textCentered(txt, 500f, 250f + LcdFace.TIME_OFFSET_Y, tp)
            overlay(cv, R.drawable.needle_lcd, 350, 100, 650, 400, 500f, 250f, sec)
            overlay(cv, R.drawable.glare_lcd, 0, 0, 1000, 500, 500f, 250f, null)
            save(bmp, "lcd_$name.png")
        }
    }

    @Test
    fun renderScreensaver() {
        val t = LocalDateTime.of(2026, 11, 3, 14, 41, 0)
        val st = DashState.at(t, DashConfig())
        val sec = 40
        val angle = sec * 4.5f
        val chrono = ChronoFace.render(st, 1000, 270f)
        Canvas(chrono).let {
            overlayDeg(it, R.drawable.needle_chrono, 0, 0, 1000, 1000, 500f, 500f, ChronoFace.SEC_ZERO_270 + angle)
            overlayDeg(it, R.drawable.pin_needle, 0, 0, 1000, 1000, 500f, 500f, 120f)
            overlayDeg(it, R.drawable.glare_chrono, 0, 0, 1000, 1000, 500f, 500f, 0f)
        }
        save(chrono, "screensaver_chrono.png")
        val lcd = LcdFace.render(st, 1000, 500, 270f)
        Canvas(lcd).let {
            val tp = textPaint(c(0xFF5CFF7A), LcdFace.TIME_TEXT_SIZE * 1.15f, MONO_BOLD)
            it.textCentered("14:41:40", 500f, 250f + LcdFace.TIME_OFFSET_Y, tp)
            overlayDeg(it, R.drawable.needle_lcd, 350, 100, 650, 400, 500f, 250f, angle)
        }
        save(lcd, "screensaver_lcd.png")
    }

    private fun overlayDeg(cv: Canvas, res: Int, l: Int, t: Int, r: Int, b: Int, px: Float, py: Float, deg: Float) {
        val d = ctx.getDrawable(res) ?: return
        d.setBounds(l, t, r, b)
        cv.save(); cv.rotate(deg, px, py); d.draw(cv); cv.restore()
    }

    @Test
    fun renderSunRing() {
        val places = listOf(
            Triple("bangkok", 13.75 to 100.50, java.time.ZoneId.of("Asia/Bangkok")),
            Triple("helsinki", 60.17 to 24.94, java.time.ZoneId.of("Europe/Helsinki")),
        )
        for ((name, ll, zone) in places) {
            val cfg = DashConfig(lat = ll.first, lon = ll.second)
            val st = DashState.at(LocalDateTime.of(2026, 11, 3, 18, 57, 0), cfg, zone)
            val e = st.sunElev!!
            println("$name sunrise ${Sun.crossing(e, -0.833f, true)} sunset ${Sun.crossing(e, -0.833f, false)}")
            val bmp = ChronoFace.render(st, 1000)
            Canvas(bmp).let {
                overlayDeg(it, R.drawable.needle_chrono, 0, 0, 1000, 1000, 500f, 500f, 15 * 6f)
                overlayDeg(it, R.drawable.pin_needle, 0, 0, 1000, 1000, 500f, 500f, 40f)
                overlayDeg(it, R.drawable.glare_chrono, 0, 0, 1000, 1000, 500f, 500f, 0f)
            }
            save(bmp, "sun_$name.png")
        }
    }

    @Test
    fun sunTimesBangkok() {
        // Bangkok, 3 Nov 2026: sunrise about 06:15, sunset about 17:50.
        val e = DashState.sunFor(13.75, 100.50, java.time.LocalDate.of(2026, 11, 3), java.time.ZoneId.of("Asia/Bangkok"))
        val rise = Sun.crossing(e, -0.833f, true)!!
        val set = Sun.crossing(e, -0.833f, false)!!
        org.junit.Assert.assertTrue("rise $rise", rise in 6 * 60 + 5..6 * 60 + 25)
        org.junit.Assert.assertTrue("set $set", set in 17 * 60 + 40..18 * 60)
    }

    /** Draws a vector over the face, rotated like AnalogClock rotates its second hand. */
    private fun overlay(
        cv: Canvas, res: Int, l: Int, t: Int, r: Int, b: Int, px: Float, py: Float, sec: Int?,
    ) {
        try {
            val d = ctx.getDrawable(res) ?: return
            d.setBounds(l, t, r, b)
            cv.save()
            if (sec != null) cv.rotate(sec * 6f, px, py)
            d.draw(cv)
            cv.restore()
        } catch (e: Throwable) {
            println("overlay $res failed: $e")
            cv.drawText("overlay failed", 20f, 40f, Paint().apply { textSize = 30f })
        }
    }

    private fun save(bmp: Bitmap, name: String) {
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${File(out, name).absolutePath}")
    }
}
