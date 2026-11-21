package app.toque.core.recipes

import java.text.Normalizer

/** Ingrédients cités dans le texte d'une étape (pour les afficher et les peser sur place). */
object StepIngredients {
    private val STOP = setOf(
        "de", "du", "des", "la", "le", "les", "et", "ou", "en", "au", "aux", "un", "une", "avec", "pour", "sans", "frais", "fraiche",
        "i", "w", "z", "na", "do", "lub", "oraz", "ze", "od", "po", "swiezy", "swieze",
    )

    fun norm(s: String): String = Normalizer.normalize(s.lowercase().replace("œ", "oe").replace("æ", "ae").replace("ł", "l"), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    private fun words(s: String) = norm(s).split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in STOP }

    /** Même mot à la déclinaison près (cukier / cukru, mleko / mleka, oignon / oignons). */
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        var k = 0
        while (k < a.length && k < b.length && a[k] == b[k]) k++
        val min = minOf(a.length, b.length)
        // Mots courts (sel, lait, riz) : identiques ou au pluriel seulement, pour ne pas confondre « lait » et « laisser ».
        if (min <= 4) return k >= min && Math.abs(a.length - b.length) <= 1
        return k >= maxOf(3, min - 3)
    }

    fun match(text: String, ingredients: List<Ingredient>): List<Ingredient> {
        val t = words(text)
        if (t.isEmpty()) return emptyList()
        return ingredients.filter { ing ->
            val w = words(ing.name)
            w.isNotEmpty() && w.any { iw -> t.any { same(it, iw) } }
        }.distinctBy { it.display }
    }

    /** Poids en grammes à peser (g, kg ; ml et l comptés comme de l'eau), ou null. */
    fun grams(i: Ingredient): Int? {
        val n = Regex("\\d+(?:[.,]\\d+)?").find(i.qty)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val f = when (norm(i.unit).trim().trimEnd('.')) {
            "g", "gr", "gram", "gramy", "gramow", "grammes", "gramme" -> 1.0
            "kg" -> 1000.0
            "ml" -> 1.0
            "cl" -> 10.0
            "dl" -> 100.0
            "l" -> 1000.0
            else -> return null
        }
        return (n * f).toInt().takeIf { it in 1..5000 }
    }
}
