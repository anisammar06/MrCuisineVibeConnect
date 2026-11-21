package app.toque.ui.screens

import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.media.SpotifyApi
import app.toque.media.SpotifyService
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Spotify sur le robot, à la manière de l'appli : recherche en haut, bibliothèque en rangées de
 * pochettes (playlists, titres likés, albums, artistes), petit lecteur en bas.
 */
class SpotifyScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Spotify")
    private val green = 0xFF1DB954.toInt()

    private lateinit var content: LinearLayout
    private lateinit var query: EditText
    private lateinit var clearBtn: View
    private lateinit var info: TextView
    // Mini-lecteur
    private lateinit var bar: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var name: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressLine
    private lateinit var playBtn: LinearLayout
    // Appairage
    private lateinit var pairBox: LinearLayout
    private lateinit var pairCode: TextView
    private lateinit var pairQr: ImageView

    private var coverUrl: String? = null
    private var shown = false
    private var libraryLoaded = false
    private var searching = false
    private var generation = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (shown) view.postDelayed(this, 1000)
        }
    }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF17301F.toInt(), 0xFF101214.toInt(), 0xFF0C0D0F.toInt()))

        // Recherche
        val top = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        top.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(4))
        val field = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        field.background = GradientDrawable().apply { cornerRadius = Ui.uf(24f); setColor(0xFF2A2D31.toInt()) }
        field.setPadding(Ui.u(14), 0, Ui.u(6), 0)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(8) })
        query = EditText(c).apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            setSingleLine()
            hint = tr("Que souhaitez-vous écouter ?")
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false
            }
        }
        field.addView(query, LinearLayout.LayoutParams(0, Ui.u(48), 1f))
        clearBtn = Icon(c, Icon.Kind.CLOSE, Ui.TEXT2).apply {
            setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
            setOnClickListener { query.setText(""); a.hideKeyboard(); searching = false; showLibrary(force = false) }
            visibility = View.GONE
        }
        field.addView(clearBtn, LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        top.addView(field, LinearLayout.LayoutParams(0, Ui.u(52), 1f))
        top.addView(Ui.button(c, tr("Chercher"), Ui.Style.PRIMARY, null, height = 52, textSize = 17f) { search() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        root.addView(top)

        // Contenu défilant
        content = Ui.vertical(c)
        content.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(10))
        info = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        root.addView(Ui.scroll(c, content), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Appairage (première fois)
        pairBox = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        pairBox.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(6))
        pairQr = ImageView(c)
        pairBox.addView(pairQr, LinearLayout.LayoutParams(Ui.u(120), Ui.u(120)).apply { rightMargin = Ui.u(16) })
        val pc = Ui.vertical(c)
        pc.addView(Ui.text(c, tr("Première fois : scannez le code avec le téléphone, ou allez sur spotify.com/pair et saisissez :"), 16f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) })
        pairCode = Ui.text(c, "", 36f, Ui.TEXT, Ui.MEDIUM)
        pc.addView(pairCode, Ui.lp(t = 4))
        pairBox.addView(pc, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        pairBox.visibility = View.GONE
        root.addView(pairBox)

        // Mini-lecteur
        val player = Ui.vertical(c)
        player.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2F2B.toInt(), 0xFF1B1E1C.toInt())).apply {
            cornerRadius = Ui.uf(16f)
        }
        player.elevation = Ui.uf(10f)
        bar = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        bar.setPadding(Ui.u(10), Ui.u(8), Ui.u(12), Ui.u(6))
        cover = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(0xFF333333.toInt(), 8f) }
        cover.clipToOutline = true
        cover.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.uf(8f))
        }
        bar.addView(cover, LinearLayout.LayoutParams(Ui.u(60), Ui.u(60)).apply { rightMargin = Ui.u(14) })
        val texts = Ui.vertical(c)
        name = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 1)
        artist = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT2), 1)
        texts.addView(name)
        texts.addView(artist, Ui.lp(t = 2))
        bar.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        fun ctl(kind: Icon.Kind, size: Int, f: () -> Boolean): View {
            val v = Icon(c, kind, Ui.TEXT)
            v.setPadding(Ui.u(12), Ui.u(12), Ui.u(12), Ui.u(12))
            v.background = Ui.pressable(Ui.round(0, 30f), 30f)
            v.setOnClickListener { act(f) }
            bar.addView(v, LinearLayout.LayoutParams(Ui.u(size), Ui.u(size)).apply { leftMargin = Ui.u(4) })
            return v
        }
        ctl(Icon.Kind.MINUS, 50) { SpotifyApi.volume(-10) }
        ctl(Icon.Kind.BACK, 56) { SpotifyApi.prev() }
        playBtn = Ui.horizontal(c, Gravity.CENTER)
        val pi = Icon(c, Icon.Kind.PLAY, 0xFF0C0D0F.toInt())
        playBtn.addView(pi, LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)))
        playBtn.background = Ui.pressable(Ui.round(Ui.TEXT, 32f), 32f)
        playBtn.setOnClickListener { act { SpotifyApi.playPause() } }
        bar.addView(playBtn, LinearLayout.LayoutParams(Ui.u(60), Ui.u(60)).apply { leftMargin = Ui.u(6); rightMargin = Ui.u(6) })
        ctl(Icon.Kind.CHEVRON, 56) { SpotifyApi.next() }
        ctl(Icon.Kind.PLUS, 50) { SpotifyApi.volume(10) }
        player.addView(bar)
        progress = ProgressLine(c).apply { color = green }
        player.addView(progress, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(4)).apply { leftMargin = Ui.u(14); rightMargin = Ui.u(14); bottomMargin = Ui.u(6) })
        root.addView(player, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            leftMargin = Ui.u(14); rightMargin = Ui.u(14); bottomMargin = Ui.u(10)
        })
        return root
    }

    // ------------------------------------------------------------------ bibliothèque

    private fun section(title: String): LinearLayout {
        content.addView(Ui.text(c, title, 22f, Ui.TEXT, Ui.MEDIUM), Ui.lp(t = 14, b = 8))
        val row = Ui.horizontal(c)
        content.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return row
    }

    /** Carte carrée (ronde pour un artiste), comme dans l'appli Spotify. */
    private fun card(item: SpotifyApi.Item, round: Boolean = false, onClick: () -> Unit): View {
        val size = Ui.u(150)
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(8), Ui.u(8), Ui.u(8), Ui.u(8))
        col.background = Ui.pressable(Ui.round(0, 12f), 12f)
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        img.background = Ui.round(0xFF2A2D31.toInt(), if (round) 75f else 8f)
        img.clipToOutline = true
        img.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) =
                if (round) o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, Ui.uf(8f))
        }
        img.elevation = Ui.uf(6f)
        col.addView(img, LinearLayout.LayoutParams(size, size))
        col.addView(Ui.ellipsize(Ui.text(c, item.name, 16f, Ui.TEXT, Ui.MEDIUM), 1).apply { maxWidth = size }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        if (item.sub.isNotBlank()) col.addView(Ui.ellipsize(Ui.text(c, item.sub, 14f, Ui.TEXT2), 1).apply { maxWidth = size }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        col.setOnClickListener { onClick() }
        Covers.load(c, img, item.image, size)
        return col
    }

    private fun likedCard(): View {
        val size = Ui.u(150)
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(8), Ui.u(8), Ui.u(8), Ui.u(8))
        col.background = Ui.pressable(Ui.round(0, 12f), 12f)
        val art = FrameLayout(c)
        art.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4A2BD8.toInt(), 0xFF8E8EE5.toInt())).apply { cornerRadius = Ui.uf(8f) }
        art.elevation = Ui.uf(6f)
        art.addView(Icon(c, Icon.Kind.STAR_FILLED, Ui.TEXT), FrameLayout.LayoutParams(Ui.u(56), Ui.u(56), Gravity.CENTER))
        col.addView(art, LinearLayout.LayoutParams(size, size))
        col.addView(Ui.text(c, tr("Titres likés"), 16f, Ui.TEXT, Ui.MEDIUM), Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        col.setOnClickListener { a.push(SpotifyTracksScreen(a, SpotifyApi.LIKED, tr("Titres likés"))) }
        return col
    }

    private fun open(it: SpotifyApi.Item) {
        if (it.kind == "track") act { SpotifyApi.play(it.uri) } else a.push(SpotifyTracksScreen(a, it.uri, it.name))
    }

    private fun showLibrary(force: Boolean) {
        if (libraryLoaded && !force) return
        val gen = ++generation
        content.removeAllViews()
        content.addView(info)
        info.text = tr("Chargement…")
        Thread {
            val playlists = SpotifyApi.playlists()
            val albums = SpotifyApi.albums()
            val artists = SpotifyApi.artists()
            view.post {
                if (gen != generation || searching) return@post
                content.removeAllViews()
                if (playlists == null) {
                    content.addView(info)
                    info.text = tr("Spotify ne répond pas : l'enceinte doit être reliée à votre compte.")
                    return@post
                }
                libraryLoaded = true
                val row = section(tr("Vos playlists"))
                row.addView(likedCard())
                for (p in playlists) row.addView(card(p) { open(p) })
                if (!albums.isNullOrEmpty()) {
                    val r2 = section(tr("Vos albums"))
                    for (al in albums) r2.addView(card(al) { open(al) })
                }
                if (!artists.isNullOrEmpty()) {
                    val r3 = section(tr("Vos artistes"))
                    for (ar in artists) r3.addView(card(ar, round = true) { open(ar) })
                }
            }
        }.start()
    }

    private fun search() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        a.hideKeyboard()
        searching = true
        clearBtn.visibility = View.VISIBLE
        val gen = ++generation
        content.removeAllViews()
        content.addView(info)
        info.text = tr("Recherche…")
        Thread {
            val r = SpotifyApi.search(c, q)
            view.post {
                if (gen != generation) return@post
                content.removeAllViews()
                if (r == null) {
                    content.addView(info)
                    info.text = if (SpotifyApi.canSearch(c)) tr("Recherche impossible pour le moment.")
                    else tr("Pour chercher dans tout Spotify, ajoutez les identifiants de votre appli Spotify depuis le téléphone (Réglages › Assistant, code QR).")
                    return@post
                }
                if (r.tracks.isNotEmpty()) {
                    content.addView(Ui.text(c, tr("Titres"), 22f, Ui.TEXT, Ui.MEDIUM), Ui.lp(t = 10, b = 4))
                    for (t in r.tracks.take(5)) content.addView(spotifyRow(c, t) { open(t) })
                }
                if (r.artists.isNotEmpty()) { val row = section(tr("Artistes")); for (x in r.artists) row.addView(card(x, round = true) { open(x) }) }
                if (r.albums.isNotEmpty()) { val row = section(tr("Albums")); for (x in r.albums) row.addView(card(x) { open(x) }) }
                if (r.playlists.isNotEmpty()) { val row = section(tr("Playlists")); for (x in r.playlists) row.addView(card(x) { open(x) }) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ lecteur

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
        pairBox.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) {
            pairCode.text = p.code
            if (pairQr.tag != p.url) {
                pairQr.tag = p.url
                pairQr.setImageBitmap(ShoppingScreen.qrBitmap(p.url, Ui.u(120)))
            }
        }
        if (s?.active == true && !libraryLoaded && !searching) showLibrary(force = false)
        val t = s?.track
        when {
            !a.prefs.spotifyEnabled -> { name.text = tr("Spotify désactivé"); artist.text = tr("Réglages › Spotify › Enceinte Spotify sur le robot.") }
            s == null -> { name.text = tr("Démarrage de Spotify…"); artist.text = "" }
            t == null -> { name.text = tr("Rien en lecture"); artist.text = tr("Choisissez une playlist ou un album ci-dessus.") }
            else -> {
                name.text = t.name
                artist.text = listOf(t.artists, s.context).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                progress.progress = if (t.duration > 0) t.position.toFloat() / t.duration else 0f
                (playBtn.getChildAt(0) as Icon).set(if (s.paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
            }
        }
        progress.visibility = if (t != null) View.VISIBLE else View.INVISIBLE
        val url = t?.cover
        if (url != coverUrl) {
            coverUrl = url
            Covers.load(c, cover, url, Ui.u(60))
        }
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
