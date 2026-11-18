package app.toque.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.ui.Icon
import app.toque.ui.ReturnBubble
import app.toque.ui.Ui
import java.io.File

/**
 * Lecteur flottant (vidéo, musique, photo) affiché par-dessus tous les écrans : on peut le
 * déplacer, l'agrandir, le fermer. Pendant une alerte de la machine il s'efface et se met en
 * pause, pour ne jamais cacher un message de sécurité.
 */
class MiniPlayer : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var stage: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playBtn: Icon
    private var video: VideoView? = null
    private var kind = Received.Kind.OTHER
    private var large = false
    private var hiddenForAlert = false
    private var resumeAfterAlert = false
    private var unsub: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra(EXTRA_URI)
        if (uri == null) {
            if (root == null) stopSelf()
            return START_NOT_STICKY
        }
        if (!ReturnBubble.canShow(this)) {
            // Droit d'affichage par-dessus : accordé par root, hors du fil principal.
            Thread {
                ReturnBubble.ensurePermission(this)
                android.os.Handler(mainLooper).post { show(uri, intent) }
            }.start()
        } else show(uri, intent)
        return START_NOT_STICKY
    }

    private fun show(uri: String, intent: Intent) {
        if (!ReturnBubble.canShow(this)) {
            stopSelf()
            return
        }
        kind = Received.Kind.values().getOrElse(intent.getIntExtra(EXTRA_KIND, 0)) { Received.Kind.OTHER }
        if (root == null) build()
        titleView.text = intent.getStringExtra(EXTRA_TITLE) ?: uri.substringAfterLast('/')
        load(uri)
        if (unsub == null) {
            val machine = ToqueApp.of(this).machine
            machine.connect()
            unsub = machine.onSnapshot { s -> onAlert(s.hasFault) }
        }
    }

    private fun build() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)

        val v = Ui.vertical(this)
        v.background = Ui.round(0xF0101114.toInt(), 18f, Ui.STROKE, 1f)
        v.elevation = Ui.uf(10f)
        v.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(6))

        val bar = Ui.horizontal(this)
        bar.setPadding(Ui.u(8), 0, 0, 0)
        titleView = Ui.ellipsize(Ui.text(this, "", 15f, Ui.TEXT2))
        bar.addView(titleView, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        playBtn = button(Icon.Kind.PAUSE) { togglePlay() }
        bar.addView(playBtn, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        bar.addView(button(Icon.Kind.PLUS) { toggleSize() }, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        bar.addView(button(Icon.Kind.CLOSE) { stopSelf() }, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        v.addView(bar, Ui.lp(Ui.MATCH, Ui.u(44)))

        stage = FrameLayout(this)
        stage.setBackgroundColor(0xFF000000.toInt())
        v.addView(stage, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        lp = WindowManager.LayoutParams(0, 0, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        val pos = getSharedPreferences(POSITION, Context.MODE_PRIVATE)
        large = pos.getBoolean("large", false)
        lp.x = pos.getInt("x", dm.widthPixels - Ui.u(420))
        lp.y = pos.getInt("y", Ui.u(70))
        applySize()

        // Déplacement : on fait glisser la barre de titre.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, maxOf(0, dm.widthPixels - lp.width))
                    lp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, maxOf(0, dm.heightPixels - lp.height))
                    update()
                }
                MotionEvent.ACTION_UP -> pos.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
            }
            true
        }
        try {
            wm.addView(v, lp)
            root = v
        } catch (_: RuntimeException) {
            stopSelf()
        }
    }

    private fun button(kind: Icon.Kind, onClick: () -> Unit) = Icon(this, kind, Ui.TEXT).apply {
        setPadding(Ui.u(11), Ui.u(11), Ui.u(11), Ui.u(11))
        background = Ui.pressable(Ui.round(0, 22f), 22f)
        setOnClickListener { onClick() }
    }

    private fun applySize() {
        val dm = resources.displayMetrics
        val w = if (large) Ui.u(760) else Ui.u(400)
        val stageH = if (kind == Received.Kind.AUDIO) Ui.u(70) else w * 9 / 16
        lp.width = minOf(w, dm.widthPixels)
        lp.height = minOf(stageH + Ui.u(56), dm.heightPixels)
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - lp.height))
    }

    private fun update() {
        val v = root ?: return
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: RuntimeException) {
        }
    }

    private fun toggleSize() {
        large = !large
        getSharedPreferences(POSITION, Context.MODE_PRIVATE).edit().putBoolean("large", large).apply()
        applySize()
        update()
    }

    private fun load(uri: String) {
        video?.stopPlayback()
        video = null
        stage.removeAllViews()
        applySize()
        update()
        when (kind) {
            Received.Kind.IMAGE -> {
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                stage.addView(img, Ui.flp())
                playBtn.visibility = View.GONE
                if (uri.startsWith("/")) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(uri, opts)
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= 1280) sample *= 2
                    img.setImageBitmap(BitmapFactory.decodeFile(uri, BitmapFactory.Options().apply { inSampleSize = sample }))
                } else img.setImageURI(Uri.parse(uri))
            }
            else -> {
                playBtn.visibility = View.VISIBLE
                val vv = VideoView(this)
                stage.addView(vv, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
                if (kind == Received.Kind.AUDIO) {
                    val note = Ui.text(this, tr("Lecture audio"), 16f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
                    stage.addView(note, Ui.flp())
                }
                vv.setOnPreparedListener { it.isLooping = false; vv.start(); playBtn.set(Icon.Kind.PAUSE) }
                vv.setOnCompletionListener { playBtn.set(Icon.Kind.PLAY) }
                vv.setOnErrorListener { _, _, _ ->
                    titleView.text = tr("Lecture impossible sur le robot (format non pris en charge)")
                    true
                }
                vv.setVideoURI(if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri))
                video = vv
            }
        }
    }

    private fun togglePlay() {
        val v = video ?: return
        if (v.isPlaying) {
            v.pause(); playBtn.set(Icon.Kind.PLAY)
        } else {
            v.start(); playBtn.set(Icon.Kind.PAUSE)
        }
    }

    /** Alerte machine : le lecteur s'efface (et se met en pause) tant qu'elle dure. */
    private fun onAlert(fault: Boolean) {
        val v = root ?: return
        if (fault && !hiddenForAlert) {
            hiddenForAlert = true
            resumeAfterAlert = video?.isPlaying == true
            video?.pause()
            playBtn.set(Icon.Kind.PLAY)
            v.visibility = View.GONE
        } else if (!fault && hiddenForAlert) {
            hiddenForAlert = false
            v.visibility = View.VISIBLE
            if (resumeAfterAlert) {
                video?.start(); playBtn.set(Icon.Kind.PAUSE)
            }
        }
    }

    override fun onDestroy() {
        unsub?.invoke()
        unsub = null
        video?.stopPlayback()
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: RuntimeException) {
            }
        }
        root = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val POSITION = "lecteur"
        private const val TYPE_APPLICATION_OVERLAY = 2038

        fun play(c: Context, uri: String, kind: Received.Kind, title: String) {
            c.startService(Intent(c, MiniPlayer::class.java)
                .putExtra(EXTRA_URI, uri).putExtra(EXTRA_KIND, kind.ordinal).putExtra(EXTRA_TITLE, title))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, MiniPlayer::class.java))
        }
    }
}
