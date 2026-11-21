package app.toque.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.WebRecipes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Avancement du téléchargement des recettes polonaises (affiché dans les réglages et la liste). */
object WebProgress {
    @Volatile var text: String = ""
        private set
    @Volatile var running = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit): () -> Unit {
        main.post { listeners += l }
        return { main.post { listeners -= l } }
    }

    fun update(running: Boolean, text: String) {
        this.running = running
        this.text = text
        main.post { for (l in ArrayList(listeners)) l() }
    }
}

/**
 * Télécharge peu à peu les recettes d'aniagotuje.pl et de kwestiasmaku.com pour les lire hors ligne
 * (usage privé). Poli avec les sites : une page à la fois, pause entre deux pages (10 s demandées par
 * kwestiasmaku.com dans son robots.txt), reprise là où on s'était arrêté.
 */
object WebRecipesSync {
    @Volatile private var stopRequested = false
    @Volatile var busy = false
        private set

    private const val UA = "Mozilla/5.0 (Linux; Android 6.0; Monsieur Cuisine) Toque"

    fun stop() {
        stopRequested = true
    }

    /** Lance en arrière-plan ; [perSite] = nombre maximal de nouvelles recettes par site (0 = tout). */
    fun start(c: Context, perSite: Int = 0) {
        if (busy) return
        val app = c.applicationContext
        Thread({ run(app, perSite) }, "toque-web-recettes").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Version du lecteur de pages : quand elle change, les recettes déjà téléchargées sont relues. */
    private const val PARSER = 2
    private val status = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun show(site: WebRecipes.Site, text: String) {
        status[site.key] = text
        WebProgress.update(true, WebRecipes.Site.values().mapNotNull { status[it.key] }.joinToString("  ·  "))
    }

    fun run(c: Context, perSite: Int) {
        if (busy) return
        busy = true
        stopRequested = false
        status.clear()
        val app = ToqueApp.of(c)
        val prefs = c.getSharedPreferences("web-recettes", Context.MODE_MULTI_PROCESS)
        if (prefs.getInt("parser", 1) < PARSER) {
            // Recettes Ania lues avec l'ancien lecteur (groupes sans nom, boutons du site pris pour une étape).
            app.webAnia.removeAllExcept(emptySet())
            prefs.edit().putInt("parser", PARSER).commit()
        }
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        try {
            // Les deux sites en même temps (chacun à son rythme) : Kwestia n'attend pas la fin d'Ania.
            val threads = WebRecipes.Site.values().map { site ->
                Thread({ lines += try { syncSite(c, site, perSite) } catch (e: Exception) { "${site.label} : $e" } }, "web-" + site.key)
                    .apply { priority = Thread.MIN_PRIORITY; start() }
            }
            threads.forEach { it.join() }
        } finally {
            busy = false
            val result = lines.joinToString(" · ") + if (stopRequested) " · arrêté" else ""
            app.prefs.webResult = result
            app.log.write("recettes-web", result)
            WebProgress.update(false, result)
        }
    }

    private fun syncSite(c: Context, site: WebRecipes.Site, perSite: Int): String {
        val app = ToqueApp.of(c)
        val repo = app.web(site.key)
        val delay = if (site == WebRecipes.Site.KWESTIA) 10_000L else 2_000L
        show(site, "${site.label} : liste des recettes…")
        val have = repo.ids()
        var added = 0
        var failed = 0
        var left = 0
        val batch = ArrayList<Recipe>()
        val done = HashSet<String>()
        fun fetch(u: String): Boolean {
            if (stopRequested || (perSite > 0 && added + failed >= perSite)) return false
            show(site, "${site.label} : +$added (${repo.count + batch.size} au total)")
            try {
                val html = get(c, u)
                val p = when (site) {
                    WebRecipes.Site.ANIA -> WebRecipes.ania(html, u)
                    WebRecipes.Site.KWESTIA -> WebRecipes.kwestia(html, u)
                }
                if (p == null) failed++ else {
                    p.imageUrl?.let { img -> saveImage(c, img, File(repo.imagesDir, p.recipe.image)) }
                    batch += p.recipe
                    added++
                }
            } catch (e: Exception) {
                if (failed < 3) app.log.write("recettes-web", "${site.label} : $u → $e")
                failed++
            }
            // Enregistrées par petits paquets : elles apparaissent dans la liste pendant le téléchargement.
            if (batch.size >= 5) { repo.upsertAll(ArrayList(batch)); batch.clear() }
            sleep(delay)
            return true
        }
        fun missing(urls: List<String>) = urls.filter { u -> done.add(u) && site.prefix + u.trimEnd('/').substringAfterLast('/') !in have }
        try {
            if (site == WebRecipes.Site.ANIA) {
                val todo = missing(listUrls(c, site, delay))
                for (u in todo) if (!fetch(u)) break
                left = todo.size - added - failed
            } else {
                // Kwestia Smaku : page de liste après page de liste, ses recettes aussitôt (pas d'attente de la liste complète).
                var page = 0
                while (!stopRequested && page < 600) {
                    if (perSite > 0 && added + failed >= perSite) { left = 1; break }
                    show(site, "${site.label} : liste, page ${page + 1} (+$added)")
                    val t0 = System.currentTimeMillis()
                    val html = get(c, "${site.base}/przepisy" + if (page > 0) "?page=$page" else "")
                    val found = WebRecipes.kwestiaUrls(html)
                    if (page < 2) app.log.write("recettes-web", "Kwestia page ${page + 1} : ${found.size} recettes, ${html.length} car., ${System.currentTimeMillis() - t0} ms")
                    if (found.isEmpty()) break
                    sleep(delay)
                    for (u in missing(found)) if (!fetch(u)) { left = 1; break }
                    if (!WebRecipes.kwestiaHasNext(html, page)) break
                    page++
                }
            }
        } catch (e: Exception) {
            app.log.write("recettes-web", "${site.label} : erreur $e" + if (e is javax.net.ssl.SSLException) " — " + CookidooClient.lastTrustIssue else "")
            if (batch.isNotEmpty()) repo.upsertAll(batch)
            return "${site.label} : +$added, interrompu (${e.message})"
        }
        if (batch.isNotEmpty()) repo.upsertAll(batch)
        if (stopRequested) left = maxOf(left, 1)
        return "${site.label} : +$added" + (if (failed > 0) ", $failed illisibles" else "") + (if (left <= 0) ", complet" else if (site == WebRecipes.Site.ANIA) ", $left restantes" else ", à suivre")
    }

    /** Adresses de toutes les recettes du site (gardées 7 jours dans un fichier). */
    private fun listUrls(c: Context, site: WebRecipes.Site, delay: Long): List<String> {
        val f = File(c.filesDir, "web-${site.key}-urls.txt")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            val cached = f.readLines().filter { it.startsWith("https://") }
            if (cached.isNotEmpty()) return cached
        }
        val urls = when (site) {
            WebRecipes.Site.ANIA -> WebRecipes.aniaUrls(get(c, "${site.base}/sitemap.xml"))
            WebRecipes.Site.KWESTIA -> {
                val all = LinkedHashSet<String>()
                var page = 0
                while (!stopRequested && page < 400) {
                    WebProgress.update(true, "${site.label} : liste des recettes, page ${page + 1} (${all.size})")
                    val html = get(c, "${site.base}/przepisy" + if (page > 0) "?page=$page" else "")
                    val found = WebRecipes.kwestiaUrls(html)
                    val before = all.size
                    all += found
                    if (all.size == before || !WebRecipes.kwestiaHasNext(html, page)) break
                    page++
                    sleep(delay)
                }
                all.toList()
            }
        }
        if (urls.isEmpty()) throw IOException("aucune recette trouvée")
        if (!stopRequested) f.writeText(urls.joinToString("\n"))
        return urls
    }

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested) {
            Thread.sleep(minOf(left, 500))
            left -= 500
        }
    }

    private fun open(c: Context, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) conn.sslSocketFactory = CookidooClient.sslFactory(c)
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
        return conn
    }

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    private fun get(c: Context, url: String): String {
        val conn = open(c, url)
        // Garde-fou : une page qui ne finit jamais d'arriver est coupée au bout de 60 s.
        val guard = watchdog.schedule({ try { conn.disconnect() } catch (_: Exception) {} }, 60, java.util.concurrent.TimeUnit.SECONDS)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            guard.cancel(false)
            conn.disconnect()
        }
    }

    /** Photo principale réduite à ~640 px (JPEG 82 %) pour ménager le stockage du robot. */
    private fun saveImage(c: Context, url: String, f: File) {
        if (f.exists()) return
        val conn = open(c, url)
        try {
            if (conn.responseCode != 200) return
            val bytes = conn.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 640) s *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(out.toByteArray())
            tmp.renameTo(f)
        } catch (_: Exception) {
        } finally {
            conn.disconnect()
        }
    }
}
