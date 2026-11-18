package app.toque.core.kitchen

import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.TextNorm
import kotlin.math.abs
import kotlin.math.roundToLong

/** Quantités de recette : « 200 », « 1,5 », « 1/2 », « 1 1/2 », « 2-3 », « ½ ». */
object Quantity {
    private val unicode = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)

    /** Valeur numérique (milieu d'une fourchette exclue : null), ou null si ce n'est pas un nombre. */
    fun parse(text: String): Double? {
        var t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        var extra = 0.0
        unicode.forEach { (ch, v) -> if (t.contains(ch)) { extra += v; t = t.replace(ch.toString(), " ") } }
        t = t.trim()
        if (t.isEmpty()) return if (extra > 0) extra else null
        val parts = t.split(Regex("\\s+"))
        var sum = extra
        for (p in parts) {
            sum += if (p.contains('/')) {
                val a = p.substringBefore('/').toDoubleOrNull() ?: return null
                val b = p.substringAfter('/').toDoubleOrNull() ?: return null
                if (b == 0.0) return null
                a / b
            } else p.toDoubleOrNull() ?: return null
        }
        return sum
    }

    /** Écriture lisible : entiers sans décimale, sinon une décimale (virgule), ½ et ¼ pour les petites fractions. */
    fun format(v: Double): String {
        if (v <= 0) return "0"
        val whole = v.toLong()
        val frac = v - whole
        if (v < 10) {
            val sym = when {
                abs(frac - 0.5) < 0.04 -> "½"
                abs(frac - 0.25) < 0.04 -> "¼"
                abs(frac - 0.75) < 0.04 -> "¾"
                else -> null
            }
            if (sym != null) return if (whole == 0L) sym else "$whole $sym"
        }
        if (v >= 20) return roundNice(v).toString()
        val r = (v * 10).roundToLong() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().replace('.', ',')
    }

    /** Arrondi des grandes quantités : à 5 près au-delà de 100 (« 333 g » → « 335 g »). */
    private fun roundNice(v: Double): Long = if (v >= 100) (v / 5).roundToLong() * 5 else v.roundToLong()

    /** Ajuste une quantité au nombre de portions ; une fourchette « 2-3 » est ajustée des deux côtés. */
    fun scale(text: String, factor: Double): String {
        if (factor == 1.0 || text.isBlank()) return text.trim()
        val range = Regex("^\\s*([0-9.,/ ½¼¾⅓⅔]+?)\\s*[-–à]\\s*([0-9.,/ ½¼¾⅓⅔]+)\\s*$").find(text)
        if (range != null) {
            val a = parse(range.groupValues[1]) ?: return text.trim()
            val b = parse(range.groupValues[2]) ?: return text.trim()
            return format(a * factor) + "-" + format(b * factor)
        }
        val v = parse(text) ?: return text.trim()
        return format(v * factor)
    }
}

data class ShoppingItem(
    val id: String,
    val name: String,
    /** Quantité déjà ajustée, ex. « 300 g », vide pour un article saisi à la main. */
    val amount: String = "",
    /** Recettes d'où vient l'article (pour s'y retrouver au magasin). */
    val recipes: List<String> = emptyList(),
    val done: Boolean = false,
) {
    val display: String get() = if (amount.isBlank()) name else "$amount $name"
}

/** Liste de courses : ajout depuis une recette (quantités ajustées, doublons fusionnés), saisie libre. */
class ShoppingList(items: List<ShoppingItem> = emptyList()) {
    private val list = ArrayList(items)
    val items: List<ShoppingItem> get() = list.sortedWith(compareBy<ShoppingItem> { it.done })

    val remaining: Int get() = list.count { !it.done }

    private var seq = System.currentTimeMillis()
    private fun newId() = (++seq).toString(36)

    fun addText(text: String): ShoppingItem? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val existing = list.indexOfFirst { key(it.name) == key(t) && it.amount.isBlank() }
        if (existing >= 0) {
            list[existing] = list[existing].copy(done = false)
            return list[existing]
        }
        return ShoppingItem(newId(), t).also { list += it }
    }

    /**
     * Ajoute les ingrédients d'une recette pour [servings] personnes (recette prévue pour [base]).
     * Même ingrédient et même unité qu'un article non coché : les quantités s'additionnent.
     */
    fun addIngredients(recipe: String, ingredients: List<Ingredient>, base: Int, servings: Int): Int {
        val factor = if (base > 0 && servings > 0) servings.toDouble() / base else 1.0
        var n = 0
        for (i in ingredients) {
            val name = i.name.trim()
            if (name.isEmpty()) continue
            val qty = Quantity.scale(i.qty, factor)
            val unit = i.unit.trim()
            val amount = listOf(qty, unit).filter { it.isNotBlank() }.joinToString(" ")
            val idx = list.indexOfFirst { !it.done && key(it.name) == key(name) && unitOf(it.amount) == unit }
            if (idx >= 0) {
                val old = list[idx]
                val a = Quantity.parse(old.amount.removeSuffix(unit).trim())
                val b = Quantity.parse(qty)
                val merged = if (a != null && b != null) listOf(Quantity.format(a + b), unit).filter { it.isNotBlank() }.joinToString(" ")
                else listOf(old.amount, amount).filter { it.isNotBlank() }.joinToString(" + ")
                list[idx] = old.copy(amount = merged, recipes = (old.recipes + recipe).distinct())
            } else {
                list += ShoppingItem(newId(), name, amount, listOf(recipe))
            }
            n++
        }
        return n
    }

    private fun unitOf(amount: String): String {
        val words = amount.trim().split(' ')
        return if (words.size <= 1) (if (Quantity.parse(amount) == null) amount.trim() else "") else words.drop(1).joinToString(" ").let {
            // « 1 ½ g » : la fraction fait partie de la quantité.
            if (Quantity.parse(words[0] + " " + words[1]) != null) words.drop(2).joinToString(" ") else it
        }
    }

    fun toggle(id: String): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(done = !list[i].done)
        return true
    }

    fun setDone(id: String, done: Boolean): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(done = done)
        return true
    }

    fun remove(id: String) = list.removeAll { it.id == id }
    fun clearDone() = list.removeAll { it.done }
    fun clear() = list.clear()

    /** Texte simple, une ligne par article à acheter (pour copier dans une note). */
    fun asText(): String = list.filter { !it.done }.joinToString("\n") { "• " + it.display }

    fun encode(): String = Json.write(linkedMapOf("format" to "toque-courses-1", "items" to list.map {
        linkedMapOf("id" to it.id, "name" to it.name, "amount" to it.amount, "recipes" to it.recipes, "done" to it.done)
    }), pretty = true)

    companion object {
        fun key(name: String): String = TextNorm.normalize(name)

        fun decode(text: String): ShoppingList {
            val items = try {
                Json.parse(text).obj()["items"].list().mapNotNull { a ->
                    val m = a.obj()
                    val name = m.str("name")?.trim().orEmpty()
                    val id = m.str("id").orEmpty()
                    if (name.isEmpty() || id.isEmpty()) null
                    else ShoppingItem(id, name, m.str("amount").orEmpty(),
                        m["recipes"].list().mapNotNull { it as? String }, m.bool("done") ?: false)
                }
            } catch (_: Exception) {
                emptyList()
            }
            return ShoppingList(items)
        }
    }
}
