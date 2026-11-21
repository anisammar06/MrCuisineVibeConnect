package app.toque.core.recipes

/**
 * Recettes des sites polonais aniagotuje.pl et kwestiasmaku.com, lues depuis leurs pages publiques
 * pour être consultées hors ligne sur le robot (usage privé). Pas de réglages machine : texte,
 * ingrédients, photos.
 */
object WebRecipes {
    enum class Site(val key: String, val label: String, val prefix: String, val base: String) {
        ANIA("ania", "Ania Gotuje", "ania-", "https://aniagotuje.pl"),
        KWESTIA("kwestia", "Kwestia Smaku", "kw-", "https://www.kwestiasmaku.com"),
    }

    class Parsed(val recipe: Recipe, val imageUrl: String?)

    // ------------------------------------------------------------------ HTML

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "oacute" to "ó", "Oacute" to "Ó", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "bdquo" to "„", "rdquo" to "”", "ldquo" to "“", "rsquo" to "’", "lsquo" to "‘", "deg" to "°",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "times" to "×", "eacute" to "é", "egrave" to "è",
    )

    fun decode(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTITIES[e] ?: m.value
        }
    }

    /** Texte d'un morceau de HTML, sur une ligne. */
    fun text(html: String): String = decode(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun between(h: String, start: String, end: String, from: Int = 0): String? {
        val i = h.indexOf(start, from)
        if (i < 0) return null
        val j = h.indexOf(end, i + start.length)
        return if (j < 0) h.substring(i + start.length) else h.substring(i + start.length, j)
    }

    private fun meta(h: String, prop: String): String? =
        Regex("<meta[^>]*itemprop=\"$prop\"[^>]*content=\"([^\"]*)\"").find(h)?.groupValues?.get(1)?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() }

    /** Durée ISO 8601 (PT1H30M) en minutes. */
    fun minutes(iso: String?): Int {
        if (iso == null) return 0
        val h = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    /** Nombre de portions si le texte en parle (« 4 porcje », « dla 6 osób »), sinon 0. */
    fun servings(s: String?): Int {
        if (s == null) return 0
        val low = s.lowercase()
        if (!Regex("porcj|osob|osób|sztuk|szt").containsMatchIn(low) && !Regex("^\\s*\\d+\\s*$").matches(low)) return 0
        return Regex("\\d+").find(low)?.value?.toIntOrNull()?.takeIf { it in 1..60 } ?: 0
    }

    private fun slug(url: String) = url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    /** Photo de taille moyenne (~720 px) prise dans un srcset, sinon l'adresse donnée. */
    private fun mid(srcset: String?, fallback: String?): String? {
        if (srcset != null) {
            val parts = srcset.split(',').map { it.trim() }.mapNotNull { p ->
                val sp = p.split(Regex("\\s+"))
                val w = sp.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: return@mapNotNull null
                sp[0] to w
            }
            parts.minByOrNull { Math.abs(it.second - 720) }?.let { return it.first }
        }
        return fallback
    }

    // ------------------------------------------------------------------ aniagotuje.pl

    fun aniaUrls(sitemap: String): List<String> =
        Regex("<loc>(https://aniagotuje\\.pl/przepis/[^<]+)</loc>").findAll(sitemap).map { it.groupValues[1].trim() }.distinct().toList()

    fun ania(html: String, url: String): Parsed? {
        val title = Regex("<h1[^>]*itemprop=\"name\"[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { text(it) }
            ?: return null
        if (!html.contains("itemprop=\"recipeIngredient\"")) return null
        // Ingrédients, par groupes (« Ciasto », « Farsz »…), dans l'ordre de la page.
        val firstIng = html.indexOf("itemprop=\"recipeIngredient\"")
        val start = html.lastIndexOf("class=\"post-ingredients\"", firstIng).takeIf { it >= 0 } ?: html.lastIndexOf("post-ingredients-group\"", firstIng).coerceAtLeast(0)
        val zone = html.substring(start, minOf(html.length, firstIng + 60_000))
        val groups = ArrayList<IngredientGroup>()
        var current = ""
        var items = ArrayList<Ingredient>()
        val tok = Regex("post-ingredients-group-header[^>]*>(.*?)</|<span class=\"ingredient ingredient-name\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        for (m in tok.findAll(zone)) {
            if (m.groupValues[1].isNotEmpty()) {
                if (items.isNotEmpty()) groups += IngredientGroup(current, items)
                items = ArrayList()
                current = text(m.groupValues[1])
            } else {
                val t = text(m.groupValues[2])
                if (t.isNotEmpty()) items += Ingredient(name = t)
            }
        }
        if (items.isNotEmpty()) groups += IngredientGroup(current, items)
        if (groups.isEmpty()) return null

        // Étapes : la partie « przepis » de l'article, du premier intertitre après les ingrédients
        // jusqu'à la publicité de fin de recette. Photos des étapes gardées comme adresses.
        val endIng = html.indexOf("</ul>", html.lastIndexOf("ingredient-name").coerceAtLeast(0)).coerceAtLeast(0)
        var body = html.substring(endIng)
        val stop = listOf("recipe_end_ad", "post-footer", "post-categories", "comments-root").map { body.indexOf(it) }.filter { it > 0 }.minOrNull()
        // On coupe avant la balise qui contient le marqueur de fin (sinon un bout de balise devient une étape).
        if (stop != null) body = body.substring(0, body.lastIndexOf('<', stop).takeIf { it >= 0 } ?: stop)
        val h3 = body.indexOf("<h3")
        if (h3 >= 0) body = body.substring(h3)
        body = body.replace(Regex("<ins[^>]*>.*?</ins>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<img[^>]*?srcset=\"([^\"]+)\"[^>]*>")) { "\n[[IMG:" + (mid(it.groupValues[1], null) ?: "") + "]]\n" }
            .replace(Regex("<(br|p|/p|h2|h3|/h2|/h3|li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        val steps = ArrayList<Step>()
        var pendingImg = ""
        for (raw in body.split('\n')) {
            val img = Regex("\\[\\[IMG:([^\\]]*)]]").find(raw)?.groupValues?.get(1)
            if (img != null) {
                // La photo illustre l'étape qui précède ; à défaut, la suivante.
                if (steps.isNotEmpty() && steps.last().image.isEmpty()) steps[steps.size - 1] = steps.last().copy(image = img)
                else if (pendingImg.isEmpty()) pendingImg = img
                continue
            }
            val t = text(raw).replace(" :", ":")
            if (t.startsWith("Porada") && steps.isNotEmpty()) {
                steps[steps.size - 1] = steps.last().copy(text = steps.last().text + "\n" + t)
                continue
            }
            if (t.length < 25) continue
            steps += Step(t, image = pendingImg)
            pendingImg = ""
        }
        if (steps.isEmpty()) return null
        val intro = Regex("class=\"article-intro\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { text(it) }.orEmpty()
        val yieldText = meta(html, "recipeYield")
        val image = Regex("article-main-img.*?<img[^>]*src=\"([^\"]+)\"[^>]*srcset=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.let { mid(it.groupValues[2], it.groupValues[1]) } ?: meta(html, "image")
        val id = Site.ANIA.prefix + slug(url)
        val r = Recipe(
            id = id,
            title = title,
            category = meta(html, "recipeCategory")?.replaceFirstChar { it.uppercase() }.orEmpty(),
            tags = meta(html, "keywords")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != title }.orEmpty(),
            servings = servings(yieldText),
            prepMinutes = minutes(meta(html, "prepTime")),
            totalMinutes = minutes(meta(html, "totalTime")).takeIf { it > 0 } ?: minutes(meta(html, "prepTime")),
            ingredients = groups,
            steps = steps,
            image = if (image != null) "$id.jpg" else "",
            notes = listOfNotNull(intro.takeIf { it.isNotBlank() }, yieldText?.let { "Porcje: $it" }).joinToString("\n\n"),
            author = Site.ANIA.label,
            rating = meta(html, "ratingValue")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0,
            ratingCount = meta(html, "ratingCount")?.toIntOrNull() ?: 0,
        )
        return Parsed(r, image)
    }

    // ------------------------------------------------------------------ kwestiasmaku.com

    fun kwestiaUrls(listing: String): List<String> =
        Regex("href=\"(?:https://www\\.kwestiasmaku\\.com)?(/przepis/[^\"#?]+)\"").findAll(listing)
            .map { Site.KWESTIA.base + it.groupValues[1] }.distinct().toList()

    fun kwestiaHasNext(listing: String, page: Int): Boolean = listing.contains("page=${page + 1}\"") || listing.contains("page=${page + 1}&")

    private fun field(h: String, name: String): String? = between(h, "field-name-$name", "</div>")?.substringAfter('>')

    private fun lis(block: String?): List<String> =
        if (block == null) emptyList() else Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(block).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()

    fun kwestia(html: String, url: String): Parsed? {
        val title = Regex("<h1[^>]*class=\"page-header\"[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { text(it) }
            ?: return null
        // Ingrédients : listes, éventuellement précédées d'un sous-titre (« Ciasto: »).
        val ingBlock = between(html, "field-name-field-skladniki", "</div>")?.substringAfter('>') ?: return null
        val groups = ArrayList<IngredientGroup>()
        var current = ""
        val parts = Regex("<(p|ul)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(ingBlock)
        for (m in parts) {
            if (m.groupValues[1] == "p") current = text(m.groupValues[2]).trimEnd(':')
            else {
                val items = lis(m.groupValues[2]).map { Ingredient(name = it) }
                if (items.isNotEmpty()) groups += IngredientGroup(current, items)
                current = ""
            }
        }
        if (groups.isEmpty()) return null
        val steps = lis(between(html, "field-name-field-przygotowanie", "</div>")).map { Step(it) }
        if (steps.isEmpty()) return null
        val tips = lis(between(html, "field-name-field-wskazowki", "</div>"))
        val intro = field(html, "field-uwagi-wstepne")?.let { text(it) }.orEmpty()
        val serving = lis(between(html, "field-name-field-propozycja-podania", "</div>"))
        val porcje = field(html, "field-ilosc-porcji")?.let { text(it) }
        val cats = between(html, "field-name-field-przepisy", "</ul>")?.let { b ->
            Regex("<a[^>]*>(.*?)</a>").findAll(b).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
        }.orEmpty()
        val image = Regex("field-name-schema-recipe.*?<img[^>]*itemprop=\"image\"[^>]*src=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        val id = Site.KWESTIA.prefix + slug(url)
        val notes = buildList {
            if (intro.isNotBlank()) add(intro)
            if (tips.isNotEmpty()) add("Wskazówki:\n" + tips.joinToString("\n") { "• $it" })
            if (serving.isNotEmpty()) add("Propozycja podania:\n" + serving.joinToString("\n") { "• $it" })
        }.joinToString("\n\n")
        val r = Recipe(
            id = id,
            title = title,
            category = cats.firstOrNull().orEmpty(),
            tags = cats.drop(1),
            servings = servings(porcje),
            ingredients = groups,
            steps = steps,
            image = if (image != null) "$id.jpg" else "",
            notes = notes,
            author = Site.KWESTIA.label,
            rating = Regex("itemprop=\"ratingValue\"[^>]*>([0-9.,]+)<").find(html)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0,
            ratingCount = Regex("itemprop=\"(?:reviewCount|ratingCount)\"[^>]*>(\\d+)<").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
        return Parsed(r, image)
    }
}
