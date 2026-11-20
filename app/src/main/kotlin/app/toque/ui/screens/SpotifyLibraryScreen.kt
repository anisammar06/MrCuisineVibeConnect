package app.toque.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.media.SpotifyApi
import app.toque.recipes.CookidooClient
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Petites pochettes, chargées en arrière-plan et gardées en mémoire. */
internal object Covers {
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)

    fun load(c: Context, v: ImageView, url: String?, px: Int) {
        v.tag = url
        v.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let { v.setImageBitmap(it); return }
        pool.execute {
            val bmp = try {
                val conn = URL(url).openConnection()
                if (conn is HttpsURLConnection) conn.sslSocketFactory = CookidooClient.sslFactory(c)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bytes = conn.getInputStream().use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= px) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Exception) {
                null
            } ?: return@execute
            cache.put(url, bmp)
            v.post { if (v.tag == url) v.setImageBitmap(bmp) }
        }
    }
}

/** Ligne de liste : pochette, titre, sous-titre. */
internal fun spotifyRow(c: Context, item: SpotifyApi.Item, onClick: () -> Unit): View {
    val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
    row.setPadding(Ui.u(10), Ui.u(8), Ui.u(10), Ui.u(8))
    row.background = Ui.pressable(Ui.round(0, 14f), 14f)
    val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(Ui.SURFACE2, 8f) }
    row.addView(img, LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)).apply { rightMargin = Ui.u(16) })
    val col = Ui.vertical(c)
    col.addView(Ui.ellipsize(Ui.text(c, item.name, 19f, Ui.TEXT, Ui.MEDIUM), 1))
    if (item.sub.isNotBlank()) col.addView(Ui.ellipsize(Ui.text(c, item.sub, 15f, Ui.TEXT2), 1), Ui.lp(t = 2))
    row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
    row.setOnClickListener { onClick() }
    Covers.load(c, img, item.image, Ui.u(64))
    return row
}

/** Bibliothèque Spotify sur le robot : playlists, titres likés, albums, artistes, recherche. */
class SpotifyLibraryScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Ma musique Spotify")
    private enum class Tab { PLAYLISTS, LIKED, ALBUMS, ARTISTS, SEARCH }
    private var tab = Tab.PLAYLISTS
    private lateinit var tabs: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var query: EditText
    private var generation = 0

    override fun build(): View {
        val root = Ui.horizontal(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(12))
        tabs = Ui.vertical(c)
        root.addView(tabs, LinearLayout.LayoutParams(Ui.u(230), Ui.MATCH).apply { rightMargin = Ui.u(20) })
        val right = Ui.vertical(c)
        searchRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        query = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Ui.TEXT)
            setSingleLine()
            hint = tr("Titre, artiste, album, playlist…")
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false
            }
        }
        searchRow.addView(query, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        searchRow.addView(Ui.button(c, tr("Chercher"), Ui.Style.PRIMARY, Icon.Kind.SEARCH, height = 54) { search() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        right.addView(searchRow)
        status = Ui.text(c, "", 16f, Ui.TEXT2)
        right.addView(status, Ui.lp(t = 4))
        list = Ui.vertical(c)
        right.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6) })
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        renderTabs()
        load()
        return root
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for ((t, label) in listOf(Tab.PLAYLISTS to tr("Playlists"), Tab.LIKED to tr("Titres likés"), Tab.ALBUMS to tr("Albums"),
            Tab.ARTISTS to tr("Artistes"), Tab.SEARCH to tr("Recherche"))) {
            tabs.addView(Ui.toggleChip(c, label, tab == t) {
                tab = t
                renderTabs()
                load()
            }, Ui.lp(Ui.MATCH, Ui.WRAP, b = 10))
        }
    }

    private fun load() {
        searchRow.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        list.removeAllViews()
        val gen = ++generation
        when (tab) {
            Tab.SEARCH -> { status.text = tr("Cherchez dans tout Spotify."); return }
            Tab.LIKED -> { a.push(SpotifyTracksScreen(a, SpotifyApi.LIKED, tr("Titres likés"))); tab = Tab.PLAYLISTS; renderTabs(); load(); return }
            else -> Unit
        }
        status.text = tr("Chargement…")
        Thread {
            val items = when (tab) {
                Tab.PLAYLISTS -> SpotifyApi.playlists()
                Tab.ALBUMS -> SpotifyApi.albums()
                else -> SpotifyApi.artists()
            }
            view.post {
                if (gen != generation) return@post
                show(items)
            }
        }.start()
    }

    private fun show(items: List<SpotifyApi.Item>?) {
        list.removeAllViews()
        status.text = when {
            items == null -> tr("Spotify ne répond pas : l'enceinte doit être reliée à votre compte (écran Spotify).")
            items.isEmpty() -> tr("Rien ici pour l'instant.")
            else -> ""
        }
        for (it in items.orEmpty()) list.addView(spotifyRow(c, it) { open(it) })
    }

    private fun open(it: SpotifyApi.Item) {
        if (it.kind == "track") play(it.uri) else a.push(SpotifyTracksScreen(a, it.uri, it.name))
    }

    private fun play(uri: String) {
        Thread {
            val ok = SpotifyApi.play(uri)
            view.post { if (ok) a.back() else a.toast(tr("Lecture impossible"), Ui.DANGER) }
        }.start()
    }

    private fun search() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        a.hideKeyboard()
        val gen = ++generation
        status.text = tr("Recherche…")
        list.removeAllViews()
        Thread {
            val r = SpotifyApi.search(c, q)
            view.post {
                if (gen != generation) return@post
                if (r == null) {
                    status.text = tr("Recherche impossible pour le moment.")
                    return@post
                }
                status.text = ""
                for ((label, items) in listOf(tr("Titres") to r.tracks, tr("Artistes") to r.artists, tr("Albums") to r.albums, tr("Playlists") to r.playlists)) {
                    if (items.isEmpty()) continue
                    list.addView(Ui.text(c, label, 17f, Ui.TEXT2, Ui.MEDIUM), Ui.lp(t = 12, b = 4))
                    for (it in items.take(6)) list.addView(spotifyRow(c, it) { open(it) })
                }
            }
        }.start()
    }
}

/** Titres d'une playlist, d'un album, d'un artiste ou des titres likés. */
class SpotifyTracksScreen(a: MainActivity, private val uri: String, private val name: String) : Screen(a) {
    override val title = name
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var shown = false
    private var count = -1

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(12))
        val head = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        head.addView(Ui.button(c, tr("Tout lire"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 58) { play(null, shuffle = false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        head.addView(Ui.button(c, tr("Aléatoire"), Ui.Style.SECONDARY, null, height = 58) { play(null, shuffle = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 16))
        status = Ui.text(c, tr("Chargement…"), 16f, Ui.TEXT2)
        head.addView(status, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        root.addView(head)
        list = Ui.vertical(c)
        root.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        return root
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!shown) return
            Thread {
                val t = SpotifyApi.tracks(uri)
                view.post {
                    if (!shown) return@post
                    if (t == null) {
                        status.text = tr("Liste indisponible ; « Tout lire » fonctionne quand même.")
                        return@post
                    }
                    val named = t.items.count { it.name != "…" }
                    if (named != count) {
                        count = named
                        list.removeAllViews()
                        for (it in t.items) list.addView(spotifyRow(c, it) { play(it.uri, shuffle = false) })
                    }
                    status.text = if (t.ready) tr("%d titres", t.items.size) else tr("Chargement…")
                    if (!t.ready || !t.complete) view.postDelayed(this, 1500)
                }
            }.start()
        }
    }

    private fun play(track: String?, shuffle: Boolean) {
        Thread {
            SpotifyApi.shuffle(shuffle)
            val ok = SpotifyApi.play(uri, track)
            view.post {
                if (ok) { a.back(); a.back() } else a.toast(tr("Lecture impossible"), Ui.DANGER)
            }
        }.start()
    }

    override fun onShow() {
        shown = true
        view.post(poll)
    }

    override fun onHide() {
        shown = false
    }
}
