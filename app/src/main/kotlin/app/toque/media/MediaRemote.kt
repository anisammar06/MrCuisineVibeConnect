package app.toque.media

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.machine.RootShell
import app.toque.recipes.CookidooClient
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ReturnBubble
import app.toque.ui.Ui
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Nécessaire pour lire les lecteurs des autres applis (NewPipe, Legimi…) : titre, image, commandes. */
class MediaListener : NotificationListenerService()

/** Appli au premier plan, pour masquer la télécommande quand on est déjà dans le lecteur. */
class ForegroundWatcher : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            // Fenêtres flottantes et clavier ne changent pas l'appli au premier plan.
            if (p == "com.android.systemui" || p.contains("inputmethod") || p.contains(".latin")) return
            foreground = p
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var foreground: String = ""
    }
}

/**
 * Télécommande flottante : pochette, titre, précédent, lecture/pause, suivant, volume − et +.
 * Elle pilote Spotify (enceinte du robot) ou le lecteur d'une autre appli (NewPipe…), et ne
 * s'affiche que quand on n'est pas déjà dans ce lecteur. Petite, déplaçable (position gardée),
 * repliable en simple pastille ; elle se cache pendant une alerte de cuisson.
 */
class MediaRemote : Service() {
    private enum class Source { SPOTIFY, SESSION }

    private class Now(val source: Source, val pkg: String, val title: String, val artist: String, val playing: Boolean,
                      val coverUrl: String?, val coverBitmap: Bitmap?, val controller: MediaController?)

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var cover: ImageView
    private lateinit var texts: LinearLayout
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var playBtn: Icon
    private var now: Now? = null
    private var coverKey: String? = null
    private var collapsed = false
    private var visible = false
    @Volatile private var alive = true
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var unsubSnapshot: (() -> Unit)? = null
    private var fault = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (!started) {
            started = true
            Thread {
                ensureAccess(this)
                main.post { build() }
                poll()
            }.start()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ données

    private fun poll() {
        while (alive) {
            val n = try {
                read()
            } catch (_: Exception) {
                null
            }
            main.post { show(n) }
            Thread.sleep(1000)
        }
    }

    private fun read(): Now? {
        val sessions = sessionNow()
        val spotify = spotifyNow()
        // Le lecteur qui joue passe en premier ; sinon le dernier en pause.
        return listOfNotNull(spotify, sessions).sortedByDescending { it.playing }.firstOrNull()
    }

    private fun spotifyNow(): Now? {
        if (!app.toque.Prefs(this).spotifyEnabled) return null
        val s = SpotifyApi.status() ?: return null
        val t = s.track ?: return null
        if (s.stopped) return null
        return Now(Source.SPOTIFY, packageName, t.name, t.artists, !s.paused, t.cover, null, null)
    }

    private fun sessionNow(): Now? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = try {
            msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
        } catch (_: SecurityException) {
            return null
        }
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED } ?: return null
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return Now(Source.SESSION, c.packageName, title, artist, c.playbackState?.state == PlaybackState.STATE_PLAYING, null, art, c)
    }

    // ------------------------------------------------------------------ vue

    private fun build() {
        if (root != null) return
        if (!ReturnBubble.canShow(this)) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        val v = Ui.horizontal(this)
        v.background = Ui.round(0xF0181A1F.toInt(), 30f, Ui.STROKE, 1f)
        v.elevation = Ui.uf(8f)
        v.setPadding(Ui.u(5), Ui.u(4), Ui.u(8), Ui.u(4))
        cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.background = Ui.round(Ui.SURFACE2, 24f)
        cover.clipToOutline = true
        cover.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) = o.setOval(0, 0, view.width, view.height)
        }
        v.addView(cover, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
        texts = Ui.vertical(this)
        title = Ui.ellipsize(Ui.text(this, "", 14f, Ui.TEXT, Ui.MEDIUM))
        artist = Ui.ellipsize(Ui.text(this, "", 12f, Ui.TEXT3))
        texts.addView(title)
        texts.addView(artist)
        v.addView(texts, LinearLayout.LayoutParams(Ui.u(130), Ui.WRAP).apply { leftMargin = Ui.u(10); rightMargin = Ui.u(4) })
        buttons = Ui.horizontal(this)
        buttons.addView(btn(Icon.Kind.BACK) { act { prev() } })
        playBtn = btn(Icon.Kind.PAUSE) { act { playPause() } }
        buttons.addView(playBtn)
        buttons.addView(btn(Icon.Kind.CHEVRON) { act { next() } })
        buttons.addView(btn(Icon.Kind.MINUS) { act { volume(-1) } })
        buttons.addView(btn(Icon.Kind.PLUS) { act { volume(1) } })
        v.addView(buttons)

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        val pos = getSharedPreferences(POSITION, Context.MODE_PRIVATE)
        // Par défaut : dans la barre du haut, entre le titre de l'écran et les indicateurs.
        lp.x = pos.getInt("x", Ui.u(300))
        lp.y = pos.getInt("y", Ui.u(4))
        collapsed = pos.getBoolean("collapsed", false)
        applyCollapsed()

        // Glisser la pochette ou le titre pour déplacer ; toucher la pochette pour replier/déplier ;
        // toucher le titre pour ouvrir le lecteur.
        fun drag(view: View, onTap: () -> Unit) {
            var dx = 0f
            var dy = 0f
            var sx = 0
            var sy = 0
            var moved = false
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - dx) + Math.abs(e.rawY - dy) > Ui.u(10)) moved = true
                        if (moved) {
                            lp.x = (sx + (e.rawX - dx).toInt()).coerceIn(0, maxOf(0, dm.widthPixels - v.width))
                            lp.y = (sy + (e.rawY - dy).toInt()).coerceIn(0, maxOf(0, dm.heightPixels - v.height))
                            try { wm.updateViewLayout(v, lp) } catch (_: RuntimeException) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) pos.edit().putInt("x", lp.x).putInt("y", lp.y).apply() else onTap()
                    }
                }
                true
            }
        }
        drag(cover) {
            collapsed = !collapsed
            pos.edit().putBoolean("collapsed", collapsed).apply()
            applyCollapsed()
        }
        drag(texts) { openPlayer() }
        v.visibility = View.GONE
        try {
            wm.addView(v, lp)
            root = v
        } catch (_: RuntimeException) {
            return
        }
        unsubSnapshot = app.toque.ToqueApp.of(this).machine.onSnapshot { s ->
            val f = s.hasFault
            if (f != fault) {
                fault = f
                main.post { show(now) }
            }
        }
    }

    private fun btn(kind: Icon.Kind, onClick: () -> Unit) = Icon(this, kind, Ui.TEXT).apply {
        setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        background = Ui.pressable(Ui.round(0, 22f), 22f)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(Ui.u(44), Ui.u(44))
    }

    private fun applyCollapsed() {
        val g = if (collapsed) View.GONE else View.VISIBLE
        texts.visibility = g
        buttons.visibility = g
    }

    private fun show(n: Now?) {
        now = n
        val v = root ?: return
        val fg = ForegroundWatcher.foreground
        val hide = n == null || fault ||
            (n.source == Source.SPOTIFY && spotifyScreenVisible) ||
            (n.source == Source.SESSION && fg == n.pkg)
        if (hide) {
            if (visible) { v.visibility = View.GONE; visible = false }
            return
        }
        n!!
        title.text = n.title.ifEmpty { if (n.source == Source.SPOTIFY) "Spotify" else n.pkg.substringAfterLast('.') }
        artist.text = n.artist
        playBtn.set(if (n.playing) Icon.Kind.PAUSE else Icon.Kind.PLAY)
        val key = n.coverUrl ?: n.coverBitmap?.let { "bmp:" + n.pkg + n.title }
        if (key != coverKey) {
            coverKey = key
            when {
                n.coverBitmap != null -> cover.setImageBitmap(n.coverBitmap)
                n.coverUrl != null -> {
                    val url = n.coverUrl
                    Thread {
                        val b = loadCover(url)
                        main.post { if (coverKey == url) cover.setImageBitmap(b) }
                    }.start()
                }
                else -> cover.setImageDrawable(null)
            }
        }
        if (!visible) { v.visibility = View.VISIBLE; visible = true }
    }

    private fun loadCover(url: String): Bitmap? = try {
        val conn = URL(url).openConnection()
        if (conn is HttpsURLConnection) conn.sslSocketFactory = CookidooClient.sslFactory(this)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        conn.getInputStream().use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ commandes

    private fun act(f: () -> Unit) {
        Thread {
            try { f() } catch (_: Exception) {}
            val n = try { read() } catch (_: Exception) { null }
            main.post { show(n) }
        }.start()
    }

    private fun playPause() {
        val n = now ?: return
        if (n.source == Source.SPOTIFY) SpotifyApi.playPause()
        else n.controller?.transportControls?.let { if (n.playing) it.pause() else it.play() }
    }

    private fun next() {
        val n = now ?: return
        if (n.source == Source.SPOTIFY) SpotifyApi.next() else n.controller?.transportControls?.skipToNext()
    }

    private fun prev() {
        val n = now ?: return
        if (n.source == Source.SPOTIFY) SpotifyApi.prev() else n.controller?.transportControls?.skipToPrevious()
    }

    private fun volume(dir: Int) {
        val n = now ?: return
        if (n.source == Source.SPOTIFY) SpotifyApi.volume(dir * 5)
        else (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    private fun openPlayer() {
        val n = now ?: return
        if (n.source == Source.SPOTIFY) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_OPEN, "spotify"))
        } else {
            val pi = n.controller?.sessionActivity
            if (pi != null) try { pi.send(); return } catch (_: Exception) {}
            packageManager.getLaunchIntentForPackage(n.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        alive = false
        unsubSnapshot?.invoke()
        root?.let { try { wm.removeView(it) } catch (_: RuntimeException) {} }
        root = null
        super.onDestroy()
    }

    enum class Action { PLAY, PAUSE, NEXT, PREV, VOLUME_UP, VOLUME_DOWN }

    /** Commande venue d'ailleurs (voix) : appliquée au lecteur actif. */
    private fun command(a: Action) {
        act {
            val n = now ?: read().also { now = it }
            if (n == null) {
                // Aucun lecteur : le volume agit quand même sur le son du robot.
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (a == Action.VOLUME_UP || a == Action.VOLUME_DOWN) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (a == Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return@act
            }
            when (a) {
                Action.PLAY -> if (!n.playing) playPause()
                Action.PAUSE -> if (n.playing) playPause()
                Action.NEXT -> next()
                Action.PREV -> prev()
                Action.VOLUME_UP -> volume(2)
                Action.VOLUME_DOWN -> volume(-2)
            }
        }
    }

    companion object {
        private const val POSITION = "telecommande"
        @Volatile private var instance: MediaRemote? = null

        fun control(c: Context, a: Action) {
            val r = instance
            if (r != null) r.command(a) else {
                start(c)
                Handler(Looper.getMainLooper()).postDelayed({ instance?.command(a) }, 1500)
            }
        }
        /** Écran Spotify de Toque affiché : la télécommande Spotify est inutile. */
        @Volatile var spotifyScreenVisible = false

        fun start(c: Context) {
            c.startService(Intent(c, MediaRemote::class.java))
        }

        /** Autorise (en root) la lecture des lecteurs des autres applis et le suivi de l'appli au premier plan. */
        fun ensureAccess(c: Context) {
            val listener = "${c.packageName}/${MediaListener::class.java.name}"
            val watcher = "${c.packageName}/${ForegroundWatcher::class.java.name}"
            val r = RootShell.run("settings get secure enabled_notification_listeners; settings get secure enabled_accessibility_services")
            if (!r.ok) return
            val lines = r.out.lines()
            val listeners = lines.getOrNull(0)?.trim().orEmpty().takeIf { it != "null" }.orEmpty()
            val services = lines.getOrNull(1)?.trim().orEmpty().takeIf { it != "null" }.orEmpty()
            val cmds = ArrayList<String>()
            if (!listeners.contains(listener)) cmds += "settings put secure enabled_notification_listeners '" +
                listOf(listeners, listener).filter { it.isNotEmpty() }.joinToString(":") + "'"
            if (!services.contains(watcher)) {
                cmds += "settings put secure enabled_accessibility_services '" + listOf(services, watcher).filter { it.isNotEmpty() }.joinToString(":") + "'"
                cmds += "settings put secure accessibility_enabled 1"
            }
            if (cmds.isNotEmpty()) RootShell.run(cmds.joinToString("; "))
        }
    }
}
