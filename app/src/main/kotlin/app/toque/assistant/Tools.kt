package app.toque.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.str
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.SearchIndex
import app.toque.kitchen.Cooked
import app.toque.kitchen.Shopping
import app.toque.kitchen.Timers
import app.toque.media.MediaRemote
import app.toque.recipes.RecipeRepo
import app.toque.ui.MainActivity
import app.toque.ui.screens.RecipeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Outils que Claude peut appeler. Aucun ne démarre le moteur. */
internal class Tools(private val c: Context) {
    private val app = ToqueApp.of(c)
    private val repos: List<Pair<String, RecipeRepo>> = listOf(
        "Lidl FR" to app.recipesFr, "Lidl PL" to app.recipesPl, "Thermomix FR" to app.tmFr, "Thermomix PL" to app.tmPl,
    )

    fun run(name: String, input: Map<String, Any?>): String = Json.write(when (name) {
        "search_recipes" -> search(input)
        "recipes_with_ingredients" -> withIngredients(input)
        "get_recipe" -> recipe(input.str("id").orEmpty())
        "cooking_history" -> history((input["limit"] as? Number)?.toInt() ?: 20)
        "open_recipe" -> open(input.str("id").orEmpty())
        "start_timer" -> startTimer(input)
        "stop_timer" -> stopTimer(input.str("name").orEmpty())
        "shopping_list" -> shopping(input)
        "media" -> media(input.str("action").orEmpty())
        "machine" -> machine(input.str("action").orEmpty())
        else -> mapOf("error" to "outil inconnu : $name")
    })

    private fun entry(source: String, e: SearchIndex.Entry) = linkedMapOf<String, Any?>(
        "id" to e.id, "title" to e.title, "source" to source,
        "minutes" to e.totalMinutes.takeIf { it > 0 }, "favorite" to e.favorite,
        "rating" to e.rating.takeIf { it > 0 }, "category" to e.category.ifEmpty { null },
    )

    private fun search(input: Map<String, Any?>): Any {
        val q = input.str("query").orEmpty()
        val limit = ((input["limit"] as? Number)?.toInt() ?: 8).coerceIn(1, 20)
        val filter = RecipeFilter(
            favoritesOnly = input["favorites_only"] == true,
            maxMinutes = (input["max_minutes"] as? Number)?.toInt() ?: 0,
        )
        val wanted = input.str("source").orEmpty().lowercase()
        val hits = repos.filter { wanted.isEmpty() || it.first.lowercase().contains(wanted) }
            .flatMap { (label, repo) -> repo.index.search(q, filter, limit).map { Triple(label, it.entry, it.score) } }
        val sorted = if (q.isBlank()) hits.shuffled() else hits.sortedByDescending { it.third }
        return mapOf("results" to sorted.take(limit).map { entry(it.first, it.second) }, "total_found" to hits.size)
    }

    private fun withIngredients(input: Map<String, Any?>): Any {
        val list = input["ingredients"].list().mapNotNull { it?.toString() }
        val hits = repos.flatMap { (label, repo) -> repo.index.withIngredients(list).take(10).map { Triple(label, it.entry, it.matched) } }
        return mapOf("results" to hits.sortedByDescending { it.third }.take(10).map { entry(it.first, it.second) + ("ingredients_matched" to it.third) })
    }

    private fun find(id: String): Pair<String, RecipeRepo>? = repos.firstOrNull { it.second.get(id) != null }

    private fun recipe(id: String): Any {
        val (label, repo) = find(id) ?: return mapOf("error" to "recette introuvable")
        val r = repo.get(id) ?: return mapOf("error" to "recette introuvable")
        return linkedMapOf(
            "id" to r.id, "title" to r.title, "source" to label, "servings" to r.servings,
            "total_minutes" to r.totalMinutes, "prep_minutes" to r.prepMinutes, "difficulty" to r.difficulty,
            "ingredients" to r.allIngredients.map { it.display },
            "steps" to r.steps.mapIndexed { i, s -> "${i + 1}. ${s.text.take(240)}" + (s.machine?.let { " [${it.summary}]" } ?: "") }.take(30),
            "notes" to r.notes.take(400),
        )
    }

    private fun history(limit: Int): Any {
        val fmt = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
        val all = Cooked.all(c)
        return mapOf(
            "cooked" to all.takeLast(limit.coerceIn(1, 100)).reversed().map { linkedMapOf("title" to it.title, "id" to it.id, "date" to fmt.format(Date(it.at))) },
            "count" to all.size,
            "note" to if (all.isEmpty()) "historique vide : il se remplit quand une recette est cuisinée sur le robot" else null,
        )
    }

    private fun open(id: String): Any {
        val (_, repo) = find(id) ?: return mapOf("error" to "recette introuvable")
        val r = repo.get(id) ?: return mapOf("error" to "recette introuvable")
        val a = MainActivity.instance
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                if (a != null) {
                    if (!a.resumed) c.startActivity(Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    a.push(RecipeScreen(a, r, repo))
                }
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return if (a != null) mapOf("ok" to true, "opened" to r.title) else mapOf("error" to "écran de Toque indisponible")
    }

    private fun startTimer(input: Map<String, Any?>): Any {
        val seconds = ((input["minutes"] as? Number)?.toDouble() ?: 0.0) * 60 + ((input["seconds"] as? Number)?.toDouble() ?: 0.0)
        if (seconds < 1 || seconds > 24 * 3600) return mapOf("error" to "durée invalide")
        val name = input.str("name").orEmpty().ifBlank { "Minuteur" }
        Timers.edit(c) { b, now -> b.start(name, (seconds * 1000).toLong(), now) }
        return mapOf("ok" to true, "name" to name, "seconds" to seconds.toInt())
    }

    private fun stopTimer(name: String): Any {
        val n = Timers.edit(c) { b, now ->
            val active = b.timers.filter { !it.dismissed && (it.leftMs(now) > 0 || b.ringing(now).any { r -> r.id == it.id }) }
            val target = if (name.isBlank()) active else active.filter { it.name.contains(name, ignoreCase = true) }
            target.forEach { b.remove(it.id) }
            target.size
        }
        return mapOf("stopped" to n)
    }

    private fun shopping(input: Map<String, Any?>): Any {
        when (input.str("action")) {
            "add" -> Shopping.edit(c) { l -> input["items"].list().forEach { l.addText(it.toString()) } }
            "add_recipe" -> {
                val id = input.str("recipe_id").orEmpty()
                val r = find(id)?.second?.get(id) ?: return mapOf("error" to "recette introuvable")
                val servings = (input["servings"] as? Number)?.toInt() ?: r.servings
                val n = Shopping.edit(c) { it.addIngredients(r.title, r.allIngredients, r.servings, servings) }
                return mapOf("ok" to true, "added" to n)
            }
            "remove" -> Shopping.edit(c) { l ->
                val names = input["items"].list().map { it.toString().lowercase() }
                l.items.filter { i -> names.any { i.name.lowercase().contains(it) } }.forEach { l.remove(it.id) }
            }
            "clear_done" -> Shopping.edit(c) { it.clearDone() }
        }
        return mapOf("items" to Shopping.get(c).items.map { (if (it.done) "[pris] " else "") + it.display })
    }

    private fun media(action: String): Any {
        val a = when (action) {
            "play" -> MediaRemote.Action.PLAY
            "pause" -> MediaRemote.Action.PAUSE
            "next" -> MediaRemote.Action.NEXT
            "previous" -> MediaRemote.Action.PREV
            "volume_up" -> MediaRemote.Action.VOLUME_UP
            "volume_down" -> MediaRemote.Action.VOLUME_DOWN
            else -> return mapOf("error" to "action inconnue")
        }
        MediaRemote.control(c, a)
        return mapOf("ok" to true)
    }

    private fun machine(action: String): Any = when (action) {
        "pause" -> { app.machine.pause(); mapOf("ok" to true) }
        "stop" -> { app.machine.stop(); mapOf("ok" to true) }
        else -> mapOf("error" to "seuls « pause » et « stop » sont permis : la personne démarre elle-même")
    }

    companion object {
        private fun tool(name: String, description: String, props: Map<String, Any?>, required: List<String> = emptyList()) = linkedMapOf(
            "name" to name, "description" to description,
            "input_schema" to linkedMapOf("type" to "object", "properties" to props, "required" to required),
        )

        private fun p(type: String, description: String, extra: Map<String, Any?> = emptyMap()) =
            linkedMapOf<String, Any?>("type" to type, "description" to description) + extra

        val DEFINITIONS: List<Map<String, Any?>> = listOf(
            tool("search_recipes", "Cherche dans les recettes du robot (Lidl et Thermomix, FR et PL). Requête en mots-clés (plat, ingrédient, type) ; vide = recettes au hasard (avec favorites_only pour les favoris).", mapOf(
                "query" to p("string", "mots-clés, ex. « poulet curry », « soupe », « dessert chocolat » ; en polonais pour les recettes PL"),
                "max_minutes" to p("integer", "durée totale maximale"),
                "favorites_only" to p("boolean", "seulement les favoris de la famille"),
                "source" to p("string", "filtre : « Lidl », « Thermomix », « FR » ou « PL »"),
                "limit" to p("integer", "nombre de résultats (8 par défaut)"),
            )),
            tool("recipes_with_ingredients", "Recettes réalisables avec des ingrédients donnés (« j'ai des courgettes et du riz »).", mapOf(
                "ingredients" to p("array", "ingrédients", mapOf("items" to mapOf("type" to "string"))),
            ), listOf("ingredients")),
            tool("get_recipe", "Détail d'une recette : ingrédients, étapes, durées.", mapOf("id" to p("string", "id de la recette")), listOf("id")),
            tool("cooking_history", "Recettes cuisinées récemment sur le robot, de la plus récente à la plus ancienne.", mapOf(
                "limit" to p("integer", "nombre d'entrées (20 par défaut)"),
            )),
            tool("open_recipe", "Ouvre une recette à l'écran du robot (la personne lance ensuite la cuisson elle-même).", mapOf("id" to p("string", "id de la recette")), listOf("id")),
            tool("start_timer", "Lance un minuteur de cuisine.", mapOf(
                "name" to p("string", "nom, ex. « Pâtes »"), "minutes" to p("number", "minutes"), "seconds" to p("number", "secondes en plus"),
            )),
            tool("stop_timer", "Arrête un minuteur (tous si aucun nom).", mapOf("name" to p("string", "nom du minuteur"))),
            tool("shopping_list", "Liste de courses : lire, ajouter des articles, ajouter les ingrédients d'une recette, retirer, vider les articles pris. Renvoie la liste.", mapOf(
                "action" to p("string", "read, add, add_recipe, remove ou clear_done", mapOf("enum" to listOf("read", "add", "add_recipe", "remove", "clear_done"))),
                "items" to p("array", "articles (add, remove)", mapOf("items" to mapOf("type" to "string"))),
                "recipe_id" to p("string", "recette (add_recipe)"),
                "servings" to p("integer", "nombre de personnes (add_recipe)"),
            ), listOf("action")),
            tool("media", "Commande la musique ou la vidéo en cours (Spotify, NewPipe…).", mapOf(
                "action" to p("string", "action", mapOf("enum" to listOf("play", "pause", "next", "previous", "volume_up", "volume_down"))),
            ), listOf("action")),
            tool("machine", "Met en pause ou arrête le programme de cuisson en cours. Démarrer est impossible.", mapOf(
                "action" to p("string", "pause ou stop", mapOf("enum" to listOf("pause", "stop"))),
            ), listOf("action")),
        )
    }
}
