package app.toque.core

import app.toque.core.recipes.Cookidoo
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookidooTest {
    private fun m(token: String) = Cookidoo.settings(token)!!.first!!

    @Test
    fun frenchSettings() {
        val a = m("6 min/100°C/vitesse 2")
        assertEquals(StepProgram.MANUAL, a.program)
        assertEquals(360, a.seconds); assertEquals(100, a.tempC); assertEquals(2, a.speed); assertFalse(a.reverse)
        val b = m("15 sec/vitesse 8")
        assertEquals(15, b.seconds); assertEquals(8, b.speed); assertEquals(0, b.tempC)
        val c = m("10 min/90°C//vitesse 1")
        assertTrue(c.reverse); assertEquals(90, c.tempC)
        val d = m("20 min/Varoma//vitesse 1")
        assertEquals(StepProgram.STEAM, d.program); assertEquals(1200, d.seconds)
        val e = m("Turbo/1 sec/3 fois")
        assertEquals(StepProgram.TURBO, e.program); assertEquals(3, e.pulses)
        val f = m("2 min/")
        assertEquals(StepProgram.KNEAD, f.program); assertEquals(120, f.seconds)
        val g = m("1 min 30 sec/vitesse 3,5")
        assertEquals(90, g.seconds); assertEquals(4, g.speed)
    }

    @Test
    fun limitsAreApplied() {
        val (h, note) = Cookidoo.settings("5 min/100°C/vitesse 5")!!
        assertEquals(3, h!!.speed)
        assertTrue(note.isNotEmpty())
        val k = m("4 min/")
        assertEquals(120, k.seconds)
        val t = m("3 min/160°C")
        assertEquals(StepProgram.TOAST, t.program)
        assertEquals(95, m("5 min/98°C/vitesse 1").tempC)
        assertTrue(m("10 min/100°C/vitesse 2").valid)
    }

    @Test
    fun polishAndGermanSettings() {
        val p = m("5 min/100°C/obr. 1")
        assertEquals(300, p.seconds); assertEquals(1, p.speed)
        assertEquals(10, m("10 sek./obr. 5").seconds)
        assertEquals(1, m("15 min/98°C//obr. ").speed)
        assertEquals(360, m("6 Min./100°C/Stufe 2").seconds)
        assertNull(Cookidoo.settings("ca. Ø 1 cm"))
    }

    @Test
    fun recipeConversion() {
        val json = """{"id":"r907015","title":"Kokos Pralinen","difficulty":"easy",
          "times":[{"type":"activeTime","quantity":{"value":2700}},{"type":"totalTime","quantity":{"value":32400}}],
          "servingSize":{"quantity":{"value":50},"unitNotation":"Stück"},
          "categories":[{"title":"Desserts"}],
          "recipeIngredientGroups":[{"title":"","recipeIngredients":[
            {"quantity":{"value":260},"unitNotation":"g","ingredientNotation":"Kokosraspeln","preparation":""},
            {"quantity":{"from":20,"to":25},"unitNotation":"g","ingredientNotation":"Butter","optional":true}]}],
          "recipeStepGroups":[{"title":"","recipeSteps":[
            {"title":"1","formattedText":"<NOBR>200 g Kokosraspeln</NOBR> in den Mixtopf geben und <nobr>15 Sek./Stufe 8</nobr> zerkleinern."},
            {"title":"2","formattedText":"Kondensmilch und Butter in den Mixtopf geben und <nobr>6 Min./100°C/Stufe 2</nobr> kochen."},
            {"title":"3","formattedText":"Aus der Mischung kleine Kugeln formen <NOBR>(ca. Ø 1 cm)</NOBR>."}]}],
          "descriptiveAssets":[{"square":"https://assets.tmecosys.com/image/upload/{transformation}/img/x"}]}"""
        val p = Cookidoo.parseRecipe(json)
        assertNotNull(p)
        val r = p!!.recipe
        assertEquals("tm-r907015", r.id)
        assertEquals(540, r.totalMinutes)
        assertEquals(50, r.servings)
        assertEquals("20-25", r.allIngredients[1].qty)
        assertEquals(200, r.steps[0].weigh!!.grams)
        assertEquals(15, r.steps[1].machine!!.seconds)
        assertTrue(r.steps[1].needsReview)
        assertEquals(360, r.steps[2].machine!!.seconds)
        assertNull(r.steps[3].machine)
        assertTrue(p.imageUrl!!.contains("t_web_rdp_recipe"))
    }

    @Test
    fun collectionsAndSearch() {
        val c = Cookidoo.parseCollections("""{"managedlists":[{"id":"col1","title":"A","chapters":[{"title":"x","recipeIds":["r1","r2"]},{"recipes":[{"id":"r3"}]}]}],"page":{"page":0,"totalPages":2}}""")
        assertEquals(listOf("r1", "r2", "r3"), c.collections[0].recipeIds)
        assertEquals(2, c.totalPages)
        val s = Cookidoo.parseSearch("""{"recipes":[{"id":"r1","title":"Soupe","totalTime":2700}],"total":1}""")
        assertEquals(45, s.hits[0].totalMinutes)
    }
}
