package app.toque.recipes

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.recipes.Cookidoo
import app.toque.core.recipes.Recipe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Avancement de la synchro Cookidoo, visible sur l'accueil et dans les réglages. */
object CookidooProgress {
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
 * Synchro Cookidoo, à un rythme raisonnable pour ne pas faire bloquer le compte :
 *  1. toutes les recettes des listes du compte (listes perso remplies sur le téléphone, collections
 *     suivies), importées en premier et marquées en favori ;
 *  2. puis un lot de nouvelles recettes du catalogue (150 par langue et par nuit par défaut), en
 *     reprenant là où la synchro précédente s'était arrêtée.
 * Une requête toutes les ~1,5 s ; les recettes déjà importées ne sont pas retéléchargées.
 */
object CookidooSync {
    private const val PAUSE_MS = 1500L
    @Volatile private var busy = false

    fun langs(app: ToqueApp): List<String> = if (app.prefs.cookidooPolish) listOf("fr", "pl") else listOf("fr")

    /** Catalogue où ranger une recette : celui de sa langue (une recette polonaise va dans PL). */
    fun catalogOf(parsed: Cookidoo.Parsed, asked: String): String = when (parsed.language) {
        "pl" -> "pl"
        "fr" -> "fr"
        else -> asked
    }

    /** Importe une recette (recherche à la demande) ; renvoie la recette convertie et son catalogue. */
    fun importOne(c: Context, lang: String, id: String): Pair<Recipe, String> {
        val app = ToqueApp.of(c)
        val client = CookidooClient(c)
        val p = fetch(c, client, lang, id) ?: throw java.io.IOException(tr("Recette Cookidoo illisible"))
        val l = catalogOf(p, lang)
        app.tm(l).upsertAll(listOf(p.recipe))
        return p.recipe to l
    }

    private fun fetch(c: Context, client: CookidooClient, lang: String, id: String): Cookidoo.Parsed? {
        val parsed = Cookidoo.parseRecipe(client.recipe(lang, id)) ?: return null
        parsed.imageUrl?.let { url ->
            val repo = ToqueApp.of(c).tm(lang)
            val f = File(repo.imagesDir, parsed.recipe.image)
            if (!f.exists()) try {
                val bytes = client.download(url)
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            } catch (_: Exception) {
            }
        }
        return parsed
    }

    fun run(c: Context) {
        if (busy) return
        busy = true
        val app = ToqueApp.of(c)
        val client = CookidooClient(c)
        val lines = ArrayList<String>()
        try {
            if (!client.loggedIn) {
                lines += tr("Compte Cookidoo non connecté")
                return
            }
            // 0.9.0 rangeait tout en français : on refait le tri par langue une fois.
            if (app.prefs.cookidooSchema < 2) {
                for (l in listOf("fr", "pl")) app.tm(l).removeAllExcept(emptySet())
                app.prefs.cookidooSchema = 2
            }
            CookidooProgress.update(true, tr("Cookidoo : lecture de vos listes…"))
            // 1. Listes du compte (identiques quelle que soit la langue du site).
            val listed = LinkedHashSet<String>()
            for (kind in 0..1) {
                var page = 0
                var pages = 1
                while (page < pages && page < 20) {
                    val json = if (kind == 0) client.customLists("fr", page) else client.managedLists("fr", page)
                    val p = Cookidoo.parseCollections(json)
                    p.collections.forEach { listed += it.recipeIds }
                    pages = p.totalPages
                    page++
                    Thread.sleep(PAUSE_MS)
                }
            }
            val known = HashMap<String, String>() // id Cookidoo -> langue déjà importée
            for (l in listOf("fr", "pl")) app.tm(l).ids().forEach { id -> Cookidoo.cookidooId(id)?.let { known[it] = l } }
            var fromLists = 0
            var n = 0
            for (id in listed) {
                n++
                val already = known[id]
                if (already != null) {
                    app.tm(already).addFavorites(listOf(Cookidoo.toqueId(id)))
                    continue
                }
                CookidooProgress.update(true, tr("Cookidoo : vos listes %d/%d", n, listed.size))
                // Recette française, sinon polonaise (listes remplies depuis l'appli en polonais).
                var done = false
                for (l in listOf("fr", "pl")) {
                    val r = try {
                        fetch(c, client, l, id)
                    } catch (e: CookidooClient.HttpException) {
                        if (e.code == 404 || e.code == 403) null else throw e
                    }
                    Thread.sleep(PAUSE_MS)
                    if (r != null) {
                        val cat = catalogOf(r, l)
                        app.tm(cat).upsertAll(listOf(r.recipe), rebuild = false)
                        app.tm(cat).addFavorites(listOf(r.recipe.id))
                        known[id] = cat
                        fromLists++
                        // Les recettes apparaissent au fur et à mesure dans Toque.
                        if (fromLists % 20 == 0) for (x in listOf("fr", "pl")) app.tm(x).refresh()
                        done = true
                        break
                    }
                }
                if (!done) app.log.write("cookidoo", "recette $id introuvable")
            }
            lines += tr("%d recettes de vos listes (%d nouvelles)", listed.size, fromLists)

            // 2. Lot du catalogue, par langue.
            val batch = app.prefs.cookidooBatch
            for (l in langs(app)) {
                var added = 0
                var page = app.prefs.cookidooPage(l)
                var empty = 0
                while (added < batch && empty < 3) {
                    CookidooProgress.update(true, tr("Cookidoo %s : %d/%d nouvelles", l.uppercase(), added, batch))
                    val hits = Cookidoo.parseSearch(client.search(l, "", page)).hits
                    Thread.sleep(PAUSE_MS)
                    if (hits.isEmpty()) {
                        empty++
                        page = 0
                        continue
                    }
                    for (h in hits) {
                        if (added >= batch) break
                        if (known.containsKey(h.id)) continue
                        val r = try {
                            fetch(c, client, l, h.id)
                        } catch (e: CookidooClient.HttpException) {
                            if (e.code == 404 || e.code == 403) null else throw e
                        }
                        Thread.sleep(PAUSE_MS)
                        if (r == null) continue
                        val cat = catalogOf(r, l)
                        app.tm(cat).upsertAll(listOf(r.recipe), rebuild = false)
                        known[h.id] = cat
                        added++
                        if (added % 20 == 0) app.tm(cat).refresh()
                    }
                    page++
                    app.prefs.setCookidooPage(l, page)
                }
                app.tm(l).refresh()
                lines += tr("%s : %d nouvelles recettes (%d au total)", l.uppercase(), added, app.tm(l).count)
            }
        } catch (e: CookidooClient.AuthException) {
            lines += e.message ?: tr("Connexion Cookidoo refusée")
        } catch (e: Exception) {
            app.log.write("cookidoo", "synchro : $e")
            lines += tr("Synchro interrompue : %s", e.message ?: e.toString())
        } finally {
            for (l in listOf("fr", "pl")) app.tm(l).refresh()
            val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date())
            app.prefs.cookidooResult = "$stamp · " + lines.joinToString(" · ")
            app.prefs.cookidooLastMs = System.currentTimeMillis()
            app.log.write("cookidoo", app.prefs.cookidooResult)
            busy = false
            CookidooProgress.update(false, "")
        }
    }
}
