package app.toque.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.media.SpotifyApi
import app.toque.media.SpotifyService
import app.toque.recipes.CookidooClient
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Enceinte Spotify du robot : morceau en cours, pochette, commandes ; appairage par code. */
class SpotifyScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Spotify")
    private lateinit var cover: ImageView
    private lateinit var name: TextView
    private lateinit var artist: TextView
    private lateinit var info: TextView
    private lateinit var progress: ProgressLine
    private lateinit var times: TextView
    private lateinit var controls: LinearLayout
    private lateinit var playBtn: LinearLayout
    private lateinit var pairBox: LinearLayout
    private lateinit var pairCode: TextView
    private lateinit var pairQr: ImageView
    private var coverUrl: String? = null
    private var shown = false
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (shown) view.postDelayed(this, 1000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        root.setPadding(Ui.u(30), Ui.u(6), Ui.u(30), Ui.u(18))
        cover = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.background = Ui.round(Ui.SURFACE, 22f)
        cover.clipToOutline = true
        cover.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.uf(22f))
        }
        root.addView(cover, LinearLayout.LayoutParams(Ui.u(400), Ui.u(400)).apply { rightMargin = Ui.u(34) })

        val col = Ui.vertical(c)
        name = Ui.ellipsize(Ui.text(c, "", 34f, Ui.TEXT, Ui.MEDIUM), 2)
        col.addView(name)
        artist = Ui.ellipsize(Ui.text(c, "", 22f, Ui.TEXT2), 1)
        col.addView(artist, Ui.lp(t = 6))
        info = Ui.text(c, "", 17f, Ui.TEXT3).apply { setLineSpacing(0f, 1.15f) }
        col.addView(info, Ui.lp(t = 10))
        progress = ProgressLine(c).apply { color = 0xFF1DB954.toInt() }
        col.addView(progress, Ui.lp(Ui.MATCH, Ui.u(6), t = 22))
        times = Ui.text(c, "", 15f, Ui.TEXT3)
        col.addView(times, Ui.lp(t = 6))
        controls = Ui.horizontal(c)
        controls.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 64) { act { SpotifyApi.volume(-10) } }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 18))
        controls.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.BACK, height = 72) { act { SpotifyApi.prev() } }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        playBtn = Ui.button(c, "", Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 84) { act { SpotifyApi.playPause() } }
        controls.addView(playBtn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        controls.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 72) { act { SpotifyApi.next() } }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 18))
        controls.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 64) { act { SpotifyApi.volume(10) } })
        col.addView(controls, Ui.lp(t = 22))

        pairBox = Ui.horizontal(c)
        pairQr = ImageView(c)
        pairBox.addView(pairQr, LinearLayout.LayoutParams(Ui.u(170), Ui.u(170)).apply { rightMargin = Ui.u(18) })
        val pc = Ui.vertical(c)
        pc.addView(Ui.text(c, tr("Première fois : scannez le code avec le téléphone, ou allez sur spotify.com/pair et saisissez :"), 17f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) })
        pairCode = Ui.text(c, "", 44f, Ui.TEXT, Ui.MEDIUM)
        pc.addView(pairCode, Ui.lp(t = 8))
        pairBox.addView(pc, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(pairBox, Ui.lp(t = 18))
        root.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return root
    }

    private fun act(f: () -> Boolean) {
        Thread { f(); view.post { refresh() } }.start()
    }

    private fun refresh() {
        Thread {
            val s = SpotifyApi.status()
            val pairing = if (s == null || !s.active) SpotifyApi.pairing() else null
            view.post { render(s, pairing) }
        }.start()
    }

    private fun render(s: SpotifyApi.Status?, p: SpotifyApi.Pairing?) {
        val t = s?.track
        pairBox.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) {
            pairCode.text = p.code
            if (pairQr.tag != p.url) {
                pairQr.tag = p.url
                pairQr.setImageBitmap(ShoppingScreen.qrBitmap(p.url, Ui.u(170)))
            }
        }
        controls.visibility = if (s?.active == true) View.VISIBLE else View.GONE
        when {
            !a.prefs.spotifyEnabled -> {
                name.text = tr("Spotify désactivé")
                artist.text = ""
                info.text = tr("Réglages › Spotify › Enceinte Spotify sur le robot.")
            }
            s == null -> {
                name.text = tr("Démarrage de Spotify…")
                artist.text = ""
                info.text = synchronized(SpotifyService.LAST) { SpotifyService.LAST.takeLast(2).joinToString("\n") }
            }
            t == null -> {
                name.text = tr("Prêt à jouer")
                artist.text = a.prefs.spotifyName
                info.text = tr("Dans l'appli Spotify du téléphone, touchez l'icône des appareils et choisissez « %s ». Le son sort du robot.", a.prefs.spotifyName)
            }
            else -> {
                name.text = t.name
                artist.text = t.artists
                info.text = listOf(t.album, s.context).filter { it.isNotBlank() }.distinct().joinToString(" · ") +
                    "\n" + tr("Volume %d %%", if (s.volumeMax > 0) s.volume * 100 / s.volumeMax else 0)
                progress.progress = if (t.duration > 0) t.position.toFloat() / t.duration else 0f
                times.text = Ui.clock(t.position) + " / " + Ui.clock(t.duration)
                (playBtn.getChildAt(0) as Icon).set(if (s.paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
            }
        }
        progress.visibility = if (t != null) View.VISIBLE else View.INVISIBLE
        times.visibility = progress.visibility
        val url = t?.cover
        if (url != coverUrl) {
            coverUrl = url
            if (url == null) cover.setImageDrawable(null) else Thread {
                val bmp = loadCover(url)
                view.post { if (coverUrl == url) cover.setImageBitmap(bmp) }
            }.start()
        }
    }

    private fun loadCover(url: String): Bitmap? = try {
        val conn = URL(url).openConnection()
        if (conn is HttpsURLConnection) conn.sslSocketFactory = CookidooClient.sslFactory(c)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.getInputStream().use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    override fun onShow() {
        app.toque.media.MediaRemote.spotifyScreenVisible = true
        SpotifyService.start(c)
        shown = true
        view.removeCallbacks(tick)
        view.post(tick)
    }

    override fun onHide() {
        app.toque.media.MediaRemote.spotifyScreenVisible = false
        shown = false
        view.removeCallbacks(tick)
    }

    override fun onKnob(delta: Int): Boolean {
        act { SpotifyApi.volume(delta * 5) }
        return true
    }

    override fun onKnobPress(): Boolean {
        act { SpotifyApi.playPause() }
        return true
    }
}
