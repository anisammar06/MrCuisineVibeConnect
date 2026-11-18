package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.double
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.Limits
import app.toque.core.protocol.Mcc
import kotlin.math.ceil

/**
 * Recettes Thermomix (Cookidoo) converties pour le Monsieur Cuisine Connect.
 *
 * Cookidoo écrit les réglages dans le texte des étapes, entre balises <nobr> :
 * « 6 min/100°C/vitesse 2 », « 15 sek./obr. 8 », « 20 min/Varoma//vitesse 1 » (le caractère
 * spécial  = sens inverse), « Turbo/1 sec/3 fois », « 2 min/ » (pétrin). Chaque réglage devient une
 * étape machine (temps, température, vitesse, sens, vapeur, pétrissage, rissolage, Turbo) ; les
 * pesées « 200 g sucre » deviennent des étapes de pesée. Les limites du robot (vitesse 3 en chauffe
 * ou en sens inverse, 130 °C, 2 min de pétrissage…) sont appliquées avec une note, et chaque étape
 * convertie reste « à vérifier » avant de lancer le moteur, comme les recettes importées de Lidl.
 */
object Cookidoo {
    const val ID_PREFIX = "tm-"

    fun toqueId(id: String) = ID_PREFIX + id
    fun cookidooId(toqueId: String): String? = if (toqueId.startsWith(ID_PREFIX)) toqueId.removePrefix(ID_PREFIX) else null
    fun imageName(id: String) = "$ID_PREFIX$id.jpg"

    /** Image (modèle avec « {transformation} ») en taille fiche ou vignette. */
    fun imageUrl(template: String, small: Boolean = false): String =
        template.replace("{transformation}", if (small) "t_web_shared_recipe_221x240" else "t_web_rdp_recipe_584x480_1_5x")

    /** [language] : langue de la recette selon Cookidoo (« pl », « fr »…), vide si inconnue. */
    class Parsed(val recipe: Recipe, val imageUrl: String?, val language: String = "")
    class Hit(val id: String, val title: String, val totalMinutes: Int, val imageUrl: String?)
    class SearchPage(val hits: List<Hit>, val total: Int)
    class Collection(val id: String, val title: String, val recipeIds: List<String>)
    class CollectionPage(val collections: List<Collection>, val totalPages: Int)

    // ------------------------------------------------------------------ JSON

    private fun firstImage(assets: Any?): String? {
        for (a in assets.list()) {
            val m = a.obj()
            val img = m.str("square") ?: m.str("landscape") ?: m.str("portrait")
            if (!img.isNullOrBlank()) return img
        }
        return null
    }

    fun parseSearch(json: String): SearchPage {
        val root = Json.parse(json).obj()
        val raw = (root["data"] ?: root["recipes"]).list()
        val hits = raw.mapNotNull { a ->
            val m = a.obj()
            val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (m.str("title") ?: m.str("name")).orEmpty()
            val total = m.double("totalTime") ?: 0.0
            Hit(id, title, (total / 60).toInt(), firstImage(m["descriptiveAssets"]) ?: m["assets"].obj()["images"].obj().str("square"))
        }
        return SearchPage(hits, root.int("total") ?: hits.size)
    }

    /** Listes de recettes : « managedlists » (collections Cookidoo suivies) ou « customlists » (listes perso). */
    fun parseCollections(json: String): CollectionPage {
        val root = Json.parse(json).obj()
        val raw = (root["customlists"] ?: root["managedlists"]).list()
        val cols = raw.map { a ->
            val m = a.obj()
            val ids = ArrayList<String>()
            for (ch in m["chapters"].list()) {
                val c = ch.obj()
                c["recipeIds"].list().forEach { (it as? String)?.let(ids::add) }
                c["recipes"].list().forEach { r -> r.obj().str("id")?.let(ids::add) }
            }
            Collection(m.str("id").orEmpty(), m.str("title").orEmpty(), ids.distinct())
        }
        return CollectionPage(cols, root["page"].obj().int("totalPages") ?: 1)
    }

    fun parseRecipe(json: String): Parsed? {
        val m = Json.parse(json).obj()
        val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = m.str("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        fun time(type: String) = m["times"].list().map { it.obj() }.firstOrNull { it.str("type") == type }
            ?.get("quantity")?.obj()?.double("value")?.let { (it / 60).toInt() } ?: 0

        val groups = m["recipeIngredientGroups"].list().mapNotNull { g ->
            val gm = g.obj()
            val items = gm["recipeIngredients"].list().mapNotNull { i ->
                val im = i.obj()
                val name = (im.str("ingredientNotation") ?: im.str("ingredient")).orEmpty().trim()
                if (name.isEmpty()) return@mapNotNull null
                val q = im["quantity"].obj()
                val qty = when {
                    q.double("value") != null -> num(q.double("value")!!)
                    q.double("from") != null && q.double("to") != null -> num(q.double("from")!!) + "-" + num(q.double("to")!!)
                    q.double("from") != null -> num(q.double("from")!!)
                    else -> ""
                }
                val notes = listOfNotNull(im.str("preparation")?.trim()?.takeIf { it.isNotEmpty() },
                    if (im.bool("optional") == true) tr("facultatif") else null)
                Ingredient(qty, im.str("unitNotation").orEmpty().trim(), name, notes.joinToString(", "))
            }
            if (items.isEmpty()) null else IngredientGroup(gm.str("title").orEmpty().trim(), items)
        }
        val rawSteps = m["recipeStepGroups"].list().flatMap { g -> g.obj()["recipeSteps"].list().map { it.obj().str("formattedText").orEmpty() } }
        val tips = m["additionalInformation"].list().mapNotNull { it.obj().str("content")?.let(::plain) }.filter { it.isNotBlank() }
        val difficulty = when (m.str("difficulty")?.lowercase()) {
            "easy" -> 1
            "medium" -> 2
            "advanced", "hard", "difficult" -> 3
            else -> 1
        }
        val image = firstImage(m["descriptiveAssets"])
        val recipe = Recipe(
            id = toqueId(id),
            title = title,
            category = m["categories"].list().firstOrNull()?.obj()?.str("title").orEmpty(),
            tags = m["tags"].list().mapNotNull { it.obj().str("name") }.take(10),
            servings = m["servingSize"].obj()["quantity"].obj().double("value")?.toInt() ?: 0,
            prepMinutes = time("activeTime"),
            totalMinutes = time("totalTime"),
            difficulty = difficulty,
            ingredients = groups,
            steps = convertSteps(rawSteps),
            image = if (image != null) imageName(id) else "",
            notes = tips.joinToString("\n\n"),
            author = "Cookidoo",
        )
        return Parsed(recipe, image?.let { imageUrl(it) }, m.str("language")?.lowercase()?.substringBefore('-').orEmpty())
    }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString().replace('.', ',')

    // ------------------------------------------------------------------ étapes

    private val NOBR = Regex("(?is)<nobr>(.*?)</nobr>")

    /** Texte sans balises ni caractères spéciaux de la police Thermomix. */
    fun plain(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("[\uE000-\uF8FF]"), "")
        .replace(Regex("[ \\t\u00a0]{2,}"), " ")
        .trim()

    fun convertSteps(raw: List<String>): List<Step> {
        val out = ArrayList<Step>()
        for (html in raw) {
            if (html.isBlank()) continue
            val weighs = ArrayList<Weigh>()
            val machines = ArrayList<Pair<StepMachine?, String>>() // réglage, note
            // Le texte affiché remplace chaque réglage Thermomix par son équivalent sur le robot.
            val shown = NOBR.replace(html) { mr ->
                val token = mr.groupValues[1]
                val tokenText = plain(token)
                weigh(tokenText)?.let { weighs += it; return@replace tokenText }
                val conv = settings(token) ?: return@replace tokenText
                machines += conv
                conv.first?.summary ?: tokenText
            }
            val text = plain(shown)
            if (weighs.isEmpty()) fallbackWeigh(text)?.let { weighs += it }
            for (w in weighs) out += Step(tr("Peser %d g : %s", w.grams, w.label), weigh = w)
            if (machines.isEmpty()) {
                if (text.isNotEmpty()) out += Step(text)
                continue
            }
            for ((k, mc) in machines.withIndex()) {
                val (machine, note) = mc
                val base = if (k == 0) text else tr("Puis : %s", machine?.summary ?: "")
                val body = if (note.isEmpty()) base else "$base\n$note"
                out += if (machine == null) Step(body) else Step(body, machine, needsReview = true)
            }
        }
        return out
    }

    private val WEIGHT = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|g)\\s+(?:de\\s+|d['’]\\s*)?(.{2,60})$", RegexOption.IGNORE_CASE)

    private fun weigh(token: String): Weigh? {
        val m = WEIGHT.find(token) ?: return null
        val grams = toGrams(m.groupValues[1], m.groupValues[2]) ?: return null
        if (grams < 5) return null
        return Weigh(m.groupValues[3].trim().take(40), grams)
    }

    private val WEIGHT_IN_TEXT = Regex("(\\d+)\\s*g\\s+(?:de\\s+|d['’]\\s*)?([\\p{L}][\\p{L}'’-]*(?:\\s+[\\p{L}][\\p{L}'’-]*){0,2})")

    /** « Mettre 500 g d'eau dans le bol » : pesée trouvée dans le texte, quand rien n'est balisé. */
    private fun fallbackWeigh(text: String): Weigh? {
        val m = WEIGHT_IN_TEXT.find(text) ?: return null
        val g = m.groupValues[1].toIntOrNull() ?: return null
        if (g < 5) return null
        return Weigh(m.groupValues[2].trim().take(40), g)
    }

    private fun toGrams(v: String, unit: String): Int? {
        val d = v.replace(',', '.').toDoubleOrNull() ?: return null
        return (if (unit.equals("kg", true)) d * 1000 else d).toInt()
    }

    private val TIME = Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|godz\\.?|std\\.?|min\\.?|sek\\.?|sec\\.?|s)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val TEMP = Regex("(\\d+)\\s*°\\s*C", RegexOption.IGNORE_CASE)
    private val SPEED = Regex("(?:vitesse|obr\\.?|stufe|speed|velocidad|velocità|vel\\.)\\s*(\\d+(?:[.,]5)?)(?:\\s*[-–]\\s*(\\d+(?:[.,]5)?))?", RegexOption.IGNORE_CASE)
    private val PULSES = Regex("(\\d+)\\s*(?:fois|razy|raz|x|mal|times|veces|volte)", RegexOption.IGNORE_CASE)
    private val PUA = Regex("[\uE000-\uF8FF]")
    private val SPOON_WORDS = Regex("mijotage|łyżk|sanftrühr|cuchara|soft|mexer", RegexOption.IGNORE_CASE)
    private val REVERSE_WORDS = Regex("sens inverse|inverse|linkslauf|wsteczn|reverse|giro a la izquierda", RegexOption.IGNORE_CASE)
    private val KNEAD_WORDS = Regex("pétri|petri|knet|zagniat|wyrabia|ugniat|amasar|impast|dough", RegexOption.IGNORE_CASE)

    /**
     * Un réglage Thermomix → étape machine du robot (ou null si ce n'est pas un réglage).
     * La note explique ce qui a été adapté aux limites du robot.
     */
    fun settings(token: String): Pair<StepMachine?, String>? {
        val raw = token.replace(Regex("<[^>]+>"), "")
        val text = raw.replace(PUA, " ").replace('\u00a0', ' ')
        val seconds = seconds(text)
        val turbo = text.contains("turbo", ignoreCase = true)
        val varoma = text.contains("varoma", ignoreCase = true)
        val temp = TEMP.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sm = SPEED.find(text)
        val hasPua = PUA.containsMatchIn(raw)
        val spoon = SPOON_WORDS.containsMatchIn(text) || (sm == null && hasPua && Regex("(?:vitesse|obr\\.?|stufe)\\s*[\uE000-\uF8FF]", RegexOption.IGNORE_CASE).containsMatchIn(raw))
        var speedF = when {
            sm != null -> (sm.groupValues[2].ifEmpty { sm.groupValues[1] }).replace(',', '.').toDouble()
            spoon -> 1.0
            else -> 0.0
        }
        val knead = KNEAD_WORDS.containsMatchIn(text) || (hasPua && sm == null && !spoon && temp == 0 && !varoma && !turbo && seconds > 0)
        val spoonGlyph = Regex("(?:vitesse|obr\\.?|stufe)\\s*[\uE000-\uF8FF]", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val otherGlyphs = PUA.findAll(raw).count() - (if (spoonGlyph) 1 else 0)
        val reverse = REVERSE_WORDS.containsMatchIn(text) || (!knead && otherGlyphs > 0)
        if (!turbo && !knead && !varoma && sm == null && !spoon && temp == 0) return null
        if (seconds == 0 && !turbo) {
            // « vitesse 1 » seul : mélange sans durée, à régler à la main.
            return null to tr("Réglage Thermomix « %s » sans durée : à faire en cuisson manuelle.", plain(token))
        }
        val notes = ArrayList<String>()
        if (turbo) {
            val pulses = PULSES.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return StepMachine(program = StepProgram.TURBO, seconds = 0, pulses = pulses.coerceIn(1, 20)) to ""
        }
        if (knead) {
            val s = seconds.coerceAtMost((Limits.MAX_MS_KNEAD / 1000).toInt())
            if (s < seconds) notes += tr("Pétrissage limité à %d min sur le robot : relancez pour le temps restant (%s au total).",
                (Limits.MAX_MS_KNEAD / 60_000).toInt(), SafetyLabel.duration(seconds))
            return StepMachine(program = StepProgram.KNEAD, seconds = s) to notes.joinToString("\n")
        }
        if (varoma) {
            if (speedF > 0) notes += tr("Vapeur du robot sans rotation (Thermomix : vitesse %s).", num(speedF))
            return StepMachine(program = StepProgram.STEAM, seconds = seconds.coerceAtMost(99 * 60)) to notes.joinToString("\n")
        }
        if (temp > Mcc.TEMPERATURE_LEVELS.last()) {
            notes += tr("%d °C (Thermomix) : programme Rissoler du robot (130 °C).", temp)
            val s = seconds.coerceAtMost((Limits.MAX_MS_TOAST / 1000).toInt())
            return StepMachine(program = StepProgram.TOAST, seconds = s) to notes.joinToString("\n")
        }
        var speed = ceil(speedF).toInt().coerceIn(0, 10)
        if (speedF != speed.toDouble()) notes += tr("Vitesse %s arrondie à %d.", num(speedF), speed)
        if (temp > 0 && speed > Limits.MAX_SPEED_HEATED) {
            notes += tr("Vitesse %d ramenée à %d : limite du robot en chauffe.", speed, Limits.MAX_SPEED_HEATED)
            speed = Limits.MAX_SPEED_HEATED
        }
        if (reverse && speed > Limits.MAX_SPEED_REVERSE) {
            notes += tr("Vitesse %d ramenée à %d : limite du robot en sens inverse.", speed, Limits.MAX_SPEED_REVERSE)
            speed = Limits.MAX_SPEED_REVERSE
        }
        if (temp > 0 && Mcc.levelForCelsius(temp) < 0) {
            val lvl = Mcc.levelAtMost(temp)
            notes += tr("%d °C réglé à %d °C (palier du robot).", temp, Mcc.celsiusForLevel(lvl))
        }
        var s = seconds
        val maxS = (if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000
        if (s > maxS) {
            notes += tr("Durée limitée à %s sur le robot (Thermomix : %s) : relancez pour le temps restant.",
                SafetyLabel.duration(maxS.toInt()), SafetyLabel.duration(s))
            s = maxS.toInt()
        }
        val tempC = if (temp > 0) Mcc.celsiusForLevel(Mcc.levelAtMost(temp).coerceAtLeast(1)) else 0
        return StepMachine(program = StepProgram.MANUAL, seconds = s, speed = speed.coerceAtLeast(1), tempC = tempC, reverse = reverse) to
            notes.joinToString("\n")
    }

    private fun seconds(text: String): Int {
        var total = 0.0
        for (m in TIME.findAll(text)) {
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val u = m.groupValues[2].lowercase()
            total += when {
                u.startsWith("h") || u.startsWith("godz") || u.startsWith("std") -> v * 3600
                u.startsWith("min") -> v * 60
                else -> v
            }
        }
        return Math.round(total).toInt()
    }

    private object SafetyLabel {
        fun duration(seconds: Int): String = app.toque.core.machine.SafetyPolicy.formatDuration(seconds * 1000L)
    }
}
