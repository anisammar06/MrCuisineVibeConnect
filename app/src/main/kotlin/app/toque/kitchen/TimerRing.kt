package app.toque.kitchen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.ui.Icon
import app.toque.ui.ReturnBubble
import app.toque.ui.Ui

/**
 * Sonnerie d'un minuteur : carte en haut de l'écran, par-dessus toutes les applis (NewPipe, lecteur…),
 * son fort sur le canal Alarme. Elle laisse libre le bas de l'écran (barre machine et alertes de
 * cuisson de Toque). Arrêt automatique au bout de 3 minutes.
 */
class TimerRing : Service() {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private lateinit var rows: LinearLayout
    private var tone: ToneGenerator? = null
    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var savedVolume = -1
    private var unsub: (() -> Unit)? = null
    private val beep = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - startedAt > 3 * 60_000L) {
                Timers.edit(this@TimerRing) { b, now -> b.ringing(now).forEach { b.dismiss(it.id) } }
                return
            }
            try {
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            } catch (_: RuntimeException) {
            }
            main.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root == null) {
            if (!ReturnBubble.canShow(this)) {
                Thread {
                    ReturnBubble.ensurePermission(this)
                    main.post { build() }
                }.start()
            } else build()
        }
        return START_NOT_STICKY
    }

    private fun build() {
        if (root != null) return
        startedAt = System.currentTimeMillis()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: RuntimeException) {
            null
        }
        main.post(beep)
        unsub = Timers.onChange { render() }

        if (!ReturnBubble.canShow(this)) return // son seul : la carte s'affichera dans Toque
        val m = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        val v = Ui.vertical(this)
        v.background = Ui.round(0xF2301A08.toInt(), 22f, Ui.ACCENT, 2f)
        v.elevation = Ui.uf(12f)
        v.setPadding(Ui.u(20), Ui.u(14), Ui.u(16), Ui.u(14))
        rows = Ui.vertical(this)
        v.addView(rows)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(minOf(Ui.u(700), dm.widthPixels), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = Ui.u(64)
        try {
            m.addView(v, lp)
            wm = m
            root = v
        } catch (_: RuntimeException) {
        }
        render()
    }

    private fun render() {
        if (root == null) return
        val now = System.currentTimeMillis()
        val ringing = Timers.get(this).ringing(now)
        if (ringing.isEmpty()) {
            stopSelf()
            return
        }
        rows.removeAllViews()
        for ((i, t) in ringing.withIndex()) {
            val row = Ui.horizontal(this)
            row.addView(Icon(this, Icon.Kind.TIMER, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(38), Ui.u(38)).apply { rightMargin = Ui.u(14) })
            val col = Ui.vertical(this)
            col.addView(Ui.ellipsize(Ui.text(this, t.name, 24f, Ui.TEXT, Ui.MEDIUM)))
            col.addView(Ui.text(this, tr("Terminé (%s)", app.toque.core.kitchen.TimerBoard.format(t.durationMs)), 16f, Ui.TEXT2))
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(Ui.button(this, tr("+1 min"), Ui.Style.SECONDARY, height = 60, textSize = 18f) {
                Timers.edit(this) { b, n -> b.add(t.id, 60_000, n) }
            }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
            row.addView(Ui.button(this, tr("Arrêter"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 60, textSize = 18f) {
                Timers.edit(this) { b, _ -> b.dismiss(t.id) }
            })
            rows.addView(row, Ui.lp(t = if (i > 0) 12 else 0))
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(beep)
        unsub?.invoke()
        tone?.release()
        if (savedVolume >= 0) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        }
        root?.let {
            try {
                wm?.removeView(it)
            } catch (_: RuntimeException) {
            }
        }
        root = null
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false

        fun start(c: Context) {
            if (running) return
            running = true
            c.startService(Intent(c, TimerRing::class.java))
        }

        fun stop(c: Context) {
            if (!running) return
            running = false
            c.stopService(Intent(c, TimerRing::class.java))
        }
    }
}
