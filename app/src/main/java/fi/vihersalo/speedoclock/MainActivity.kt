package fi.vihersalo.speedoclock

import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
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

    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            renderPreviews()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

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

    private fun renderPreviews() {
        val st = DashState.now(this)

        val chrono = findViewById<FrameLayout>(R.id.chrono_box)
        chrono.findViewById<ImageView>(R.id.face).setImageBitmap(ChronoFace.render(st, 800))

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
