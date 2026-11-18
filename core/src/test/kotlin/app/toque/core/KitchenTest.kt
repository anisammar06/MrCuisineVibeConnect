package app.toque.core

import app.toque.core.kitchen.Quantity
import app.toque.core.kitchen.ShoppingList
import app.toque.core.kitchen.TimerBoard
import app.toque.core.recipes.Ingredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenTest {
    @Test
    fun quantities() {
        assertEquals(0.5, Quantity.parse("1/2")!!, 1e-9)
        assertEquals(1.5, Quantity.parse("1 1/2")!!, 1e-9)
        assertEquals(1.5, Quantity.parse("1,5")!!, 1e-9)
        assertEquals(0.5, Quantity.parse("½")!!, 1e-9)
        assertNull(Quantity.parse("une pincée"))
        assertEquals("400", Quantity.scale("200", 2.0))
        assertEquals("1 ½", Quantity.scale("3", 0.5))
        assertEquals("335", Quantity.scale("250", 4.0 / 3))
        assertEquals("4-6", Quantity.scale("2-3", 2.0))
        assertEquals("1,3", Quantity.scale("1", 4.0 / 3))
        assertEquals("quelques", Quantity.scale("quelques", 2.0))
    }

    @Test
    fun shoppingMergesSameIngredient() {
        val l = ShoppingList()
        l.addIngredients("Crêpes", listOf(Ingredient("250", "g", "Farine"), Ingredient("3", "", "Œufs")), 4, 4)
        l.addIngredients("Gâteau", listOf(Ingredient("200", "g", "farine"), Ingredient("", "", "Sel")), 4, 2)
        val items = l.items
        assertEquals(3, items.size)
        assertEquals("350 g", items.first { it.name == "Farine" }.amount)
        assertEquals(listOf("Crêpes", "Gâteau"), items.first { it.name == "Farine" }.recipes)
        val eggs = items.first { it.name == "Œufs" }
        l.toggle(eggs.id)
        assertEquals(2, l.remaining)
        assertTrue(l.items.last().done)
        val back = ShoppingList.decode(l.encode())
        assertEquals(l.items, back.items)
        l.clearDone()
        assertEquals(2, l.items.size)
    }

    @Test
    fun timers() {
        val b = TimerBoard()
        val t = b.start("Pâtes", 60_000, 1_000)
        assertEquals(30_000, b.get(t.id)!!.leftMs(31_000))
        b.pause(t.id, 31_000)
        assertEquals(30_000, b.get(t.id)!!.leftMs(99_000))
        b.resume(t.id, 100_000)
        assertEquals(130_000L, b.nextEnd(100_000))
        assertTrue(b.ringing(130_000).isNotEmpty())
        b.add(t.id, 60_000, 131_000)
        assertTrue(b.ringing(131_000).isEmpty())
        b.dismiss(t.id)
        assertEquals(1, TimerBoard.decode(b.encode()).timers.size)
        assertEquals("1:05:00", TimerBoard.format(3_900_000))
        assertEquals("4:07", TimerBoard.format(247_000))
    }
}
