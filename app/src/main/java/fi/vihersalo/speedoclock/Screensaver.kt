package fi.vihersalo.speedoclock

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** Which face the screensaver and nightstand mode show. */
object ScreensaverPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("speedoclock", Context.MODE_PRIVATE)

    fun face(c: Context): GaugeView.Face =
        if (sp(c).getString("ss_face", "CHRONO") == "LCD") GaugeView.Face.LCD else GaugeView.Face.CHRONO

    fun setFace(c: Context, f: GaugeView.Face) = sp(c).edit().putString("ss_face", f.name).apply()
}

/**
 * Android's own screensaver (Settings → Display → Screen saver), shown while charging or docked.
 * Some phone skins hide that menu; NightstandActivity is the way in that works everywhere.
 */
class SpeedoDreamService : DreamService() {
    private var gauge: GaugeView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any touch ends it
        isFullscreen = true
        gauge = GaugeView(this, ScreensaverPrefs.face(this)).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        gauge?.start()
    }

    override fun onDreamingStopped() {
        gauge?.stop()
        super.onDreamingStopped()
    }
}

/** Full-screen gauge started from the app. Keeps the screen on; tap anywhere to leave. */
class NightstandActivity : Activity() {
    private lateinit var gauge: GaugeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gauge = GaugeView(this, ScreensaverPrefs.face(this))
        gauge.setOnClickListener { finish() }
        setContentView(gauge)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        gauge.start()
    }

    override fun onPause() {
        gauge.stop()
        super.onPause()
    }
}
