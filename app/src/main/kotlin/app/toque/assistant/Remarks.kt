package app.toque.assistant

import android.content.Context
import app.toque.Prefs
import app.toque.core.recipes.Recipe

/**
 * Petits commentaires spontanés de l'assistant : bonjour, mise en garde quand on ouvre le couvercle
 * sur du chaud, conseils en cuisinant, encouragement quand la pesée est juste.
 */
object Remarks {
    private var lastAt = 0L
    private var lastLidAt = 0L

    private fun allowed(c: Context, gapMs: Long = 20_000): Boolean {
        val p = Prefs(c)
        if (!p.assistantReady || !p.leaRemarks || Assistant.busy) return false
        val now = System.currentTimeMillis()
        if (now - lastAt < gapMs) return false
        lastAt = now
        return true
    }

    private fun pl(c: Context) = Prefs(c).voiceLang == "pl"

    /** Bonjour à l'ouverture de l'appli (au plus toutes les 4 heures). */
    fun greet(c: Context) {
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        if (now - p.lastGreetAt < 4 * 3600_000L || !allowed(c, 0)) return
        p.lastGreetAt = now
        Assistant.remark(c, "Salue la famille qui vient d'allumer le robot, selon le moment de la journée, et demande en une phrase " +
            "ce qu'on prépare de bon aujourd'hui, avec une idée de plat de saison (sans outil). Deux phrases au plus, ton chaleureux et naturel.")
    }

    /** Couvercle ouvert sur un contenu chaud. */
    fun lidHot(c: Context, tempC: Int) {
        val now = System.currentTimeMillis()
        val p = Prefs(c)
        if (!p.assistantReady || !p.leaRemarks || now - lastLidAt < 60_000 || Assistant.busy) return
        lastLidAt = now
        lastAt = now
        val fr = listOf("Attention à toi, c'est très chaud, $tempC degrés ! Gare à la vapeur.",
            "Doucement, ça fume : $tempC degrés. Écarte bien le visage en ouvrant.",
            "Prudence, c'est brûlant à l'intérieur, $tempC degrés.")
        val plList = listOf("Uważaj, jest bardzo gorąco, $tempC stopni! Uważaj na parę.",
            "Ostrożnie, w środku jest $tempC stopni. Nie pochylaj się nad parą.")
        Assistant.say(c, (if (pl(c)) plList else fr).random(), show = true)
    }

    /** Début de la cuisson guidée : un conseil pour réussir la recette. */
    fun cookStart(c: Context, r: Recipe) {
        if (!allowed(c)) return
        Assistant.remark(c, "On commence la recette « ${r.title} » en cuisson guidée. Encourage en une phrase et donne UN conseil " +
            "concret et utile pour la réussir (mise en place, ingrédient, geste). Deux phrases au plus, sans outil.")
    }

    /** Fin d'une étape machine : annonce la suite avec un conseil. */
    fun stepDone(c: Context, r: Recipe, finished: Int) {
        if (!allowed(c)) return
        val next = r.steps.getOrNull(finished + 1)
        val instr = if (next == null) "La dernière étape de « ${r.title} » est terminée. Félicite brièvement et donne un conseil de service ou de dégustation. Deux phrases au plus, sans outil."
        else "L'étape ${finished + 1} de « ${r.title} » vient de se terminer. Annonce en une phrase ce qu'il faut faire maintenant (étape ${finished + 2} : « ${next.text.take(300)} »), " +
            "avec un petit conseil pratique si utile. Deux phrases au plus, sans outil."
        Assistant.remark(c, instr)
    }

    /** Pesée juste. */
    fun weighed(c: Context, label: String, grams: Int) {
        if (!allowed(c, 8_000)) return
        val what = label.ifBlank { if (pl(c)) "składnika" else "" }
        val fr = listOf("Parfait, $grams grammes${if (what.isNotBlank()) " de $what" else ""}.", "C'est tout bon, $grams grammes.", "Pile ce qu'il faut !")
        val plList = listOf("Idealnie, $grams gramów.", "Świetnie, dokładnie tyle, ile trzeba.")
        Assistant.say(c, (if (pl(c)) plList else fr).random(), show = true)
    }
}
