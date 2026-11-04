package fi.vihersalo.speedoclock

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val REQ_LOCATION = 7
        private const val REQ_STEPS = 8
    }

    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            renderPreviews()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    private val slotButtons = mapOf(
        Panels.Slot.LEFT to R.id.slot_left,
        Panels.Slot.CENTER to R.id.slot_center,
        Panels.Slot.RIGHT to R.id.slot_right,
        Panels.Slot.BOTTOM to R.id.slot_bottom,
    )

    private val timeRows = listOf(
        Triple(R.id.work_start, "Work starts", Prefs.WORK_START),
        Triple(R.id.work_end, "Work ends", Prefs.WORK_END),
        Triple(R.id.day_start, "Day starts (tank full)", Prefs.DAY_START),
        Triple(R.id.day_end, "Day ends (tank empty)", Prefs.DAY_END),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.add_chrono).setOnClickListener { pin(ChronoWidget::class.java) }
        findViewById<Button>(R.id.add_lcd).setOnClickListener { pin(LcdWidget::class.java) }

        for ((id, _, key) in timeRows) {
            findViewById<Button>(id).setOnClickListener {
                val v = Prefs.get(this, key)
                TimePickerDialog(this, { _, h, m ->
                    Prefs.set(this, key, h * 60 + m)
                    refreshLabels()
                    renderPreviews()
                    WidgetUpdater.updateAll(this)
                }, v / 60, v % 60, true).show()
            }
        }

        findViewById<Button>(R.id.ss_face).setOnClickListener {
            val next = if (ScreensaverPrefs.face(this) == GaugeView.Face.CHRONO) GaugeView.Face.LCD else GaugeView.Face.CHRONO
            ScreensaverPrefs.setFace(this, next)
            refreshLabels()
        }
        findViewById<Button>(R.id.nightstand).setOnClickListener {
            startActivity(Intent(this, NightstandActivity::class.java))
        }
        findViewById<Button>(R.id.dream_settings).setOnClickListener { openDreamSettings() }

        findViewById<Button>(R.id.sun_loc).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fetchLocation()
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            }
        }

        for ((slot, id) in slotButtons) {
            findViewById<Button>(id).setOnClickListener { pickSource(slot) }
        }
        findViewById<Button>(R.id.fx_pair).setOnClickListener { pickCurrency(base = true) }

        // Preview: tapping the centre flips it, like on the home screen.
        findViewById<FrameLayout>(R.id.chrono_box).findViewById<android.widget.ViewFlipper>(R.id.flip)
            .setOnClickListener {
                WidgetUpdater.toggleMoon(this)
                applyFlip(animate = true)
                WidgetUpdater.updateAll(this)
            }

        findViewById<Button>(R.id.exact).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLabels()
        handler.post(minuteTick)
        WidgetUpdater.updateAll(this)
        WidgetUpdater.refreshInBackground(this) { runOnUiThread { renderPreviews() } }
    }

    private fun pickSource(slot: Panels.Slot) {
        val sources = Panels.Source.values()
        val current = Panels.layout(this).getValue(slot)
        android.app.AlertDialog.Builder(this)
            .setTitle(slot.label)
            .setSingleChoiceItems(sources.map { it.label }.toTypedArray(), sources.indexOf(current)) { d, which ->
                val src = sources[which]
                Panels.set(this, slot, src)
                d.dismiss()
                when {
                    src == Panels.Source.STEPS && !Steps.available(this) ->
                        Toast.makeText(this, "This phone has no step counter.", Toast.LENGTH_LONG).show()
                    src == Panels.Source.STEPS && !Steps.hasPermission(this) ->
                        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_STEPS)
                    (src == Panels.Source.TEMP || src == Panels.Source.WEATHER) && Prefs.config(this).lat == null ->
                        Toast.makeText(this, "Tap \"Use my location\" below so the weather knows where you are.", Toast.LENGTH_LONG).show()
                }
                settingsChanged()
            }
            .show()
    }

    /** Two steps: "1 ___ =" then "___". */
    private fun pickCurrency(base: Boolean, chosenBase: String? = null) {
        val list = Feeds.CURRENCIES.toTypedArray()
        val (b, q) = Feeds.fxPair(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (base) "Convert from" else "1 ${chosenBase} in")
            .setSingleChoiceItems(list, list.indexOf(if (base) b else q)) { d, which ->
                d.dismiss()
                if (base) pickCurrency(base = false, chosenBase = list[which])
                else {
                    Feeds.setFxPair(this, chosenBase!!, list[which])
                    settingsChanged()
                }
            }
            .show()
    }

    private fun settingsChanged() {
        refreshLabels()
        renderPreviews()
        WidgetUpdater.updateAll(this)
        WidgetUpdater.refreshInBackground(this) { runOnUiThread { renderPreviews() } }
    }

    override fun onPause() {
        handler.removeCallbacks(minuteTick)
        super.onPause()
    }

    private fun pin(cls: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Speedoclock", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_LOCATION && ok) fetchLocation()
        if (requestCode == REQ_STEPS) settingsChanged()
    }

    /** One rough fix is enough: sunrise barely moves within a city. Saved rounded to ~1 km. */
    private fun fetchLocation() {
        val lm = getSystemService(LocationManager::class.java)
        try {
            val last = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null) return saveLocation(last)
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) }
            if (provider == null) {
                Toast.makeText(this, "Turn on Location in quick settings, then try again.", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "Finding your location…", Toast.LENGTH_SHORT).show()
            lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                if (loc != null) saveLocation(loc)
                else Toast.makeText(this, "Couldn't get a location. Try again outdoors or with Wi-Fi on.", Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
        }
    }

    private fun saveLocation(loc: Location) {
        val r = { v: Double -> Math.round(v * 100) / 100.0 }
        Prefs.setLocation(this, r(loc.latitude), r(loc.longitude))
        Feeds.clearWeather(this)
        settingsChanged()
    }

    private fun sunText(): String {
        val cfg = Prefs.config(this)
        if (cfg.lat == null || cfg.lon == null) {
            return "Tap below and the 24 h ring shows today's sky (night, blue hour, golden hour, daylight) and the screens can show your weather. Rough location only, asked once."
        }
        val e = DashState.sunFor(cfg.lat, cfg.lon, java.time.LocalDate.now())
        fun hm(m: Int?) = if (m == null) "--:--" else String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
        val rise = Sun.crossing(e, -0.833f, rising = true)
        val set = Sun.crossing(e, -0.833f, rising = false)
        return "Sunrise " + hm(rise) + "  ·  Sunset " + hm(set) + "\n" +
            "Blue hour from " + hm(Sun.crossing(e, -6f, true)) + ", golden hour until " + hm(Sun.crossing(e, 6f, true)) + "\n" +
            "Golden hour from " + hm(Sun.crossing(e, 6f, false)) + ", blue hour until " + hm(Sun.crossing(e, -6f, false)) + "\n" +
            String.format(Locale.US, "Location %.2f, %.2f", cfg.lat, cfg.lon)
    }

    /** Some phone skins (often ColorOS / realme UI) hide the screen saver page. */
    private fun openDreamSettings() {
        val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "This phone hides Android's screen saver setting. Use nightstand mode instead.", Toast.LENGTH_LONG).show()
    }

    private fun refreshLabels() {
        val layout = Panels.layout(this)
        for ((slot, id) in slotButtons) {
            findViewById<Button>(id).text = slot.label + "   " + layout.getValue(slot).label
        }
        val (fb, fq) = Feeds.fxPair(this)
        findViewById<Button>(R.id.fx_pair).text = "Currency   1 $fb → $fq"
        findViewById<TextView>(R.id.sun_note).text = sunText()
        findViewById<Button>(R.id.sun_loc).text =
            if (Prefs.config(this).lat == null) "Use my location" else "Update location"
        val faceName = if (ScreensaverPrefs.face(this) == GaugeView.Face.CHRONO) "Chrono" else "LCD"
        findViewById<Button>(R.id.ss_face).text = "Face   " + faceName + "   (tap to switch)"
        for ((id, label, key) in timeRows) {
            val v = Prefs.get(this, key)
            findViewById<Button>(id).text = String.format(Locale.US, "%s   %02d:%02d", label, v / 60, v % 60)
        }
        val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        findViewById<TextView>(R.id.exact_note).text = if (exact) {
            "Exact timing is on: minute LEDs flip right on the minute."
        } else {
            "The seconds needle is always live. Without exact timing, Android may flip the minute LEDs up to ~20 s late."
        }
        findViewById<Button>(R.id.exact).visibility = if (exact) Button.GONE else Button.VISIBLE
    }

    private fun applyFlip(animate: Boolean) {
        val chrono = findViewById<FrameLayout>(R.id.chrono_box)
        val flip = chrono.findViewById<android.widget.ViewFlipper>(R.id.flip)
        val moon = WidgetUpdater.moonShown(this)
        val want = if (moon) 1 else 0
        if (flip.displayedChild != want) {
            if (!animate) { flip.inAnimation = null; flip.outAnimation = null }
            flip.displayedChild = want
            if (!animate) {
                flip.setInAnimation(this, R.anim.flip_in)
                flip.setOutAnimation(this, R.anim.flip_out)
            }
        }
        val v = if (moon) android.view.View.GONE else android.view.View.VISIBLE
        chrono.findViewById<android.view.View>(R.id.needle).visibility = v
        chrono.findViewById<android.view.View>(R.id.pin).visibility = v
    }

    private fun renderPreviews() {
        val st = DashState.now(this)

        val chrono = findViewById<FrameLayout>(R.id.chrono_box)
        chrono.findViewById<ImageView>(R.id.face).setImageBitmap(ChronoFace.renderOuter(st, 800))
        chrono.findViewById<ImageView>(R.id.dial).setImageBitmap(ChronoFace.renderInner(st, 480))
        chrono.findViewById<ImageView>(R.id.moon).setImageBitmap(MoonFace.render(st.epochMs, st.lat, 480))
        chrono.post {
            val side = min(chrono.width, chrono.height)
            val flip = chrono.findViewById<android.widget.ViewFlipper>(R.id.flip)
            val lp = flip.layoutParams
            lp.width = (side * ChronoFace.INNER_FRACTION).toInt()
            lp.height = lp.width
            flip.layoutParams = lp
        }
        applyFlip(animate = false)

        val lcd = findViewById<FrameLayout>(R.id.lcd_box)
        lcd.findViewById<ImageView>(R.id.face).setImageBitmap(LcdFace.render(st, 1000, 500))
        lcd.post {
            val s = min(lcd.width / LcdFace.W, lcd.height / LcdFace.H) // px per face unit
            if (s <= 0f) return@post
            val time = lcd.findViewById<TextView>(R.id.time)
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, LcdFace.TIME_TEXT_SIZE * s)
            val lp = time.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = (LcdFace.TIME_OFFSET_Y * s).toInt()
            time.layoutParams = lp
        }
    }
}
