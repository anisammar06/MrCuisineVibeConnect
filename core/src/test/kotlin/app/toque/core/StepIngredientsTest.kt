package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.StepIngredients
import org.junit.Assert.assertEquals
import org.junit.Test

class StepIngredientsTest {
    private val pl = listOf(Ingredient("25", "g", "cukru"), Ingredient("500", "g", "mleka"), Ingredient("60", "g", "kaszy manny"))
    private val fr = listOf(Ingredient("200", "g", "farine"), Ingredient("2", "", "œufs"), Ingredient("1", "l", "lait"), Ingredient("1", "", "oignon"))

    @Test
    fun polishDeclension() {
        val m = StepIngredients.match("Do naczynia wsypać cukier, zmielić 10 s", pl)
        assertEquals(listOf("cukru"), m.map { it.name })
        assertEquals(listOf("mleka"), StepIngredients.match("Wlać mleko i podgrzać", pl).map { it.name })
    }

    @Test
    fun french() {
        assertEquals(listOf("farine", "œufs"), StepIngredients.match("Ajouter la farine et les oeufs", fr).map { it.name })
        assertEquals(listOf("oignon"), StepIngredients.match("Mettre les oignons coupés en deux", fr).map { it.name })
        assertEquals(emptyList<String>(), StepIngredients.match("Mixer 10 s vitesse 5", fr).map { it.name })
        assertEquals(emptyList<String>(), StepIngredients.match("Laisser reposer", fr).map { it.name })
    }

    @Test
    fun grams() {
        assertEquals(25, StepIngredients.grams(pl[0]))
        assertEquals(1000, StepIngredients.grams(fr[2]))
        assertEquals(null, StepIngredients.grams(fr[1]))
    }
}
