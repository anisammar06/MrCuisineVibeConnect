package app.toque.core

import app.toque.core.recipes.WebRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRecipesTest {
    private val ania = """
<style>.post-ingredients{margin:0}</style>
<meta itemprop="prepTime" content="PT1H30M"><meta itemprop="totalTime" content="PT2H0M">
<meta itemprop="recipeYield" content="4 porcje"><meta itemprop="recipeCategory" content="dania mięsne">
<meta itemprop="keywords" content="Pierogi, obiad">
<div class="col-12 article-main-img"><img src="https://cdn/x-1500.jpg" srcset="https://cdn/a-360.jpg 360w, https://cdn/a-720.jpg 720w, https://cdn/a-1080.jpg 1080w"></div>
<h1 itemprop="name">Pierogi &amp; co</h1>
<div itemprop="recipeInstructions"><div class="article-intro"><p>Pyszne pierogi.</p></div>
<div class="post-ingredients"><div class="post-ingredients-group"><button class="ing-header post-ingredients-group-header">Ciasto</button>
<ul><li><span itemprop="recipeIngredient" class="ingredient-text"><span class="ingredient ingredient-name">2 szklanki mąki</span></span></li></ul></div>
<div class="post-ingredients-group"><button class="ing-header post-ingredients-group-header">Farsz</button>
<ul><li><span itemprop="recipeIngredient" class="ingredient-text"><span class="ingredient ingredient-name">300 g mięsa</span></span></li></ul></div></div>
<p>Kalorie policzone zostały na podstawie użytych przeze mnie składników.</p>
<h3>Pierogi przepis</h3><p>Przygotuj ciasto: wymieszaj mąkę z wodą i zagnieć gładkie ciasto.</p>
<div class="img-placeholder"><img src="x" srcset="https://cdn/s1-360.jpg 360w, https://cdn/s1-720.jpg 720w"></div><br>
Rozwałkuj ciasto cienko i wykrawaj krążki szklanką.<br><em>Porada</em>: można zamrozić.
<div data-read-milestone="recipe_end_ad"></div>
"""

    @Test
    fun parsesAnia() {
        val p = WebRecipes.ania(ania, "https://aniagotuje.pl/przepis/pierogi")
        assertNotNull(p)
        val r = p!!.recipe
        assertEquals("ania-pierogi", r.id)
        assertEquals("Pierogi & co", r.title)
        assertEquals(listOf("Ciasto", "Farsz"), r.ingredients.map { it.title })
        assertEquals("300 g mięsa", r.ingredients[1].items[0].name)
        assertEquals(2, r.steps.size)
        assertEquals("https://cdn/s1-720.jpg", r.steps[0].image)
        assertTrue(r.steps[1].text.contains("Porada"))
        assertEquals(4, r.servings)
        assertEquals(120, r.totalMinutes)
        assertEquals("https://cdn/a-720.jpg", p.imageUrl)
    }

    private val ania2 = """
<h1 itemprop="name">3 bit</h1>
<div class="post-ingredients"><section class="post-ingredients-group"><button type="button" class="ing-header post-ingredients-group-header post-ingredients-group-toggle"><span aria-hidden="true" class="post-ingredients-group-arrow"></span> <strong>Składniki na krem</strong> <span class="ing-remarks"><svg></svg></span></button>
<ul><li><span itemprop="recipeIngredient" class="ingredient-text"><span class="ingredient ingredient-name">200 g masła</span></span></li></ul></section></div>
<div data-read-milestone="after_ingredients"><div class="share-head">Składniki:</div><span class="btn-label">Kopiuj</span> Ukryj zdjęcia</div>
<div data-read-milestone="body_after_recipe_controls"><h2>3 bit</h2>
<div class="steps">
<div id="step1" class="step" itemprop="recipeInstructions" itemscope>
 <div class="step-name">Krok 1: <span itemprop="name">Ugotuj budyń</span></div>
 <div class="step-text" itemprop="text"><p>Do garnka wlej mleko.<br><br>Mieszaj do wrzenia.</p>
  <div class="img-placeholder"><img src="x" srcset="https://cdn/b-360.jpg 360w, https://cdn/b-720.jpg 720w"></div></div>
 <hr>
</div>
<div id="step2" class="step" itemprop="recipeInstructions" itemscope>
 <div class="step-name">Krok 2: <span itemprop="name">Podawaj</span></div>
 <div class="step-text" itemprop="text"><p>Udekoruj truskawkami.</p></div>
 <hr>
</div></div>
"""

    @Test
    fun parsesAniaSteps() {
        val r = WebRecipes.ania(ania2, "https://aniagotuje.pl/przepis/3-bit")!!.recipe
        assertEquals(listOf("Składniki na krem"), r.ingredients.map { it.title })
        assertEquals(2, r.steps.size)
        assertEquals("Ugotuj budyń\nDo garnka wlej mleko.\nMieszaj do wrzenia.", r.steps[0].text)
        assertEquals("https://cdn/b-720.jpg", r.steps[0].image)
        assertEquals("", r.steps[1].image)
    }

    private val kw = """
<h1 class="page-header">Kotlety schabowe</h1>
<ul class="field field-name-field-przepisy"><li><a href="/przepisy/schab">Schab</a></li><li><a href="/x">Kotlety</a></li></ul>
<div class="field field-name-field-ilosc-porcji field-type-text"> 4 porcje </div>
<div class="field field-name-field-skladniki"> <ul><li> 600 g schabu</li><li> <strong>do smażenia:</strong> smalec</li></ul> </div>
<div class="field field-name-field-przygotowanie"><ul><li> Rozbić mięso.</li><li> Usmażyć.</li></ul></div>
<div class="field field-name-field-wskazowki"><ul><li>Smacznego</li></ul></div>
<div class="field field-name-schema-recipe"><img itemprop="image" src="https://www.kwestiasmaku.com/files/schabowe.jpg"></div>
"""

    @Test
    fun parsesKwestia() {
        val p = WebRecipes.kwestia(kw, "https://www.kwestiasmaku.com/przepis/kotlety-schabowe")!!
        assertEquals("kw-kotlety-schabowe", p.recipe.id)
        assertEquals(listOf("600 g schabu", "do smażenia: smalec"), p.recipe.allIngredients.map { it.name })
        assertEquals(2, p.recipe.steps.size)
        assertEquals("Schab", p.recipe.category)
        assertEquals(4, p.recipe.servings)
        assertTrue(p.recipe.notes.contains("Smacznego"))
        assertEquals("https://www.kwestiasmaku.com/files/schabowe.jpg", p.imageUrl)
    }

    @Test
    fun listings() {
        assertEquals(listOf("https://aniagotuje.pl/przepis/a"), WebRecipes.aniaUrls("<loc>https://aniagotuje.pl/przepis/a</loc><loc>https://aniagotuje.pl/kategoria/b</loc>"))
        assertEquals(listOf("https://www.kwestiasmaku.com/przepis/x"), WebRecipes.kwestiaUrls("<a href=\"/przepis/x\">x</a><a href=\"/przepis/x#c\">"))
        assertTrue(WebRecipes.kwestiaHasNext("<a href=\"/przepisy?page=2\">", 1))
        assertEquals(90, WebRecipes.minutes("PT1H30M"))
    }
}
