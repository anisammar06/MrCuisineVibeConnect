package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.kitchen.Shopping
import app.toque.recipes.Reviews
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Fiche recette : ingrédients à gauche, étapes à droite, « Cuisiner » pour le pas-à-pas. */
class RecipeScreen(a: MainActivity, val recipe: Recipe, private val repo: app.toque.recipes.RecipeRepo = a.app.recipes) : Screen(a) {
    override val title = recipe.title
    private lateinit var favIcon: Icon
    private lateinit var favLabel: TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- colonne gauche
        val left = Ui.vertical(c)
        val header = FrameLayout(c)
        header.background = Ui.gradient(Ui.placeholderColors(recipe.title), 24f)
        header.addView(Ui.text(c, Ui.initials(recipe.title), 70f, 0x44FFFFFF, Ui.THIN), Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        header.addView(img, Ui.flp())
        header.clipToOutline = true
        header.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, Ui.uf(24f))
        }
        ImageLoader.load(img, repo.imageFile(recipe.image), Ui.u(420))
        left.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(170)))

        val meta = Ui.horizontal(c)
        if (recipe.totalMinutes > 0) meta.addView(Ui.chip(c, "${recipe.totalMinutes} min", Icon.Kind.CLOCK), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        if (recipe.servings > 0) meta.addView(Ui.chip(c, tr("%d pers.", recipe.servings), Icon.Kind.POT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        meta.addView(Ui.chip(c, when (recipe.difficulty) { 1 -> tr("Facile"); 2 -> tr("Moyen"); else -> tr("Difficile") }, Icon.Kind.STAR))
        left.addView(meta, Ui.lp(t = 12))

        // Auteur et note du site.
        val byline = ArrayList<String>()
        if (recipe.author.isNotBlank()) byline += tr("Par %s", authorName(recipe.author))
        if (recipe.ratingCount > 0) byline += "★ " + tr("%s/5 · %d notes", rating(recipe.rating), recipe.ratingCount)
        if (byline.isNotEmpty()) {
            left.addView(Ui.ellipsize(Ui.text(c, byline.joinToString("   "), 16f, Ui.TEXT2)), Ui.lp(t = 10))
        }

        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Cuisiner"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66, textSize = 22f) {
            a.push(CookScreen(a, recipe, 0))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        val fav = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.STAR, height = 66) { toggleFavorite() }
        favIcon = fav.getChildAt(0) as Icon
        favLabel = Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM)
        fav.addView(favLabel, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(8) })
        actions.addView(fav)
        left.addView(actions, Ui.lp(t = 14))

        val ing = Ui.vertical(c)
        val ingHead = Ui.horizontal(c)
        ingHead.addView(section(tr("Ingrédients")), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (recipe.allIngredients.isNotEmpty()) {
            ingHead.addView(Ui.button(c, tr("Courses"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 44, textSize = 15f) { askShopping() },
                Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        }
        ing.addView(ingHead)
        for (g in recipe.ingredients) {
            if (g.title.isNotBlank()) ing.addView(Ui.text(c, g.title, 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 12, b = 2))
            for (i in g.items) {
                val row = Ui.horizontal(c, Gravity.TOP)
                row.addView(Ui.text(c, "•", 18f, Ui.TEXT3), Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                row.addView(Ui.text(c, i.display, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                ing.addView(row, Ui.lp(t = 8))
            }
        }
        if (recipe.ingredients.isEmpty()) ing.addView(Ui.text(c, tr("Pas de liste d'ingrédients."), 17f, Ui.TEXT3), Ui.lp(t = 8))
        left.addView(Ui.scroll(c, ing), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- colonne droite
        val steps = Ui.vertical(c)
        steps.addView(section(tr("Étapes")))
        for ((i, s) in recipe.steps.withIndex()) steps.addView(stepRow(i, s), Ui.lp(t = 12))
        McOnline.apiIdOf(recipe.id)?.let { apiId ->
            steps.addView(reviewsBox, Ui.lp(t = 22))
            reviewsBox.addView(section(if (recipe.ratingCount > 0)
                tr("Avis · %s/5 (%d notes)", rating(recipe.rating), recipe.ratingCount) else tr("Avis")))
            reviewsBox.addView(reviewsState, Ui.lp(t = 10))
            loadReviews(apiId, 1)
        }
        val stepsBox = Ui.vertical(c)
        stepsBox.background = Ui.round(Ui.SURFACE, 24f)
        stepsBox.setPadding(Ui.u(22), Ui.u(10), Ui.u(22), Ui.u(18))
        stepsBox.addView(Ui.scroll(c, steps), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(stepsBox, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        refreshFavorite()
        return root
    }

    // ------------------------------------------------------------------ avis

    private val reviewsBox by lazy { Ui.vertical(c) }
    private val reviewsState by lazy { Ui.text(c, "", 16f, Ui.TEXT3) }
    private var moreButton: View? = null
    private var loading = false

    private fun loadReviews(apiId: Long, page: Int) {
        if (loading) return
        loading = true
        reviewsState.visibility = View.VISIBLE
        reviewsState.text = tr("Chargement des avis…")
        Thread {
            val result = try {
                Reviews.load(apiId, page)
            } catch (e: Exception) {
                null
            }
            reviewsBox.post {
                loading = false
                if (result == null) {
                    reviewsState.text = tr("Avis indisponibles (pas de connexion ?)")
                    return@post
                }
                showReviews(apiId, result)
            }
        }.start()
    }

    private fun showReviews(apiId: Long, p: McOnline.ReviewPage) {
        moreButton?.let { reviewsBox.removeView(it) }
        moreButton = null
        if (p.page == 1 && p.reviews.isEmpty()) {
            reviewsState.text = tr("Pas encore de commentaire sur cette recette.")
            return
        }
        reviewsState.visibility = View.GONE
        for (r in p.reviews) reviewsBox.addView(reviewRow(r), Ui.lp(t = 14))
        if (p.page < p.totalPages) {
            val shown = reviewsBox.childCount - 2
            val more = Ui.button(c, tr("Voir plus d'avis (%d sur %d)", shown, p.total), Ui.Style.GHOST, height = 50, textSize = 16f) {
                loadReviews(apiId, p.page + 1)
            }
            moreButton = more
            reviewsBox.addView(more, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        }
    }

    private fun reviewRow(r: McOnline.Review): View {
        val box = Ui.vertical(c)
        box.background = Ui.round(Ui.SURFACE2, 16f)
        box.setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
        val head = Ui.horizontal(c)
        head.addView(Ui.text(c, "★".repeat(r.rating) + "☆".repeat(5 - r.rating), 17f, Ui.ACCENT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        val who = listOf(r.author.ifBlank { tr("Anonyme") }, r.date).filter { it.isNotBlank() }.joinToString(" · ")
        head.addView(Ui.ellipsize(Ui.text(c, who, 15f, Ui.TEXT3)), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(head)
        if (r.message.isNotBlank()) {
            box.addView(Ui.text(c, r.message, 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.12f) }, Ui.lp(t = 6))
        }
        if (r.reply.isNotBlank()) {
            box.addView(Ui.text(c, tr("Réponse de Monsieur Cuisine : %s", r.reply), 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 8))
        }
        return box
    }

    private fun rating(v: Double): String = String.format(I18n.locale, "%.1f", v)

    private fun authorName(name: String): String =
        if (name.equals("Monsieur Cuisine Official", ignoreCase = true)) tr("Monsieur Cuisine (recette officielle)") else name

    private fun section(t: String): TextView =
        Ui.text(c, t.uppercase(), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f; setPadding(0, Ui.u(10), 0, 0) }

    private fun stepRow(i: Int, s: Step): View {
        val row = Ui.horizontal(c, Gravity.TOP)
        val num = Ui.text(c, "${i + 1}", 17f, Ui.ACCENT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        num.background = Ui.round(Ui.ACCENT_DIM, 18f)
        row.addView(num, LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        if (s.text.isNotBlank()) col.addView(Ui.text(c, s.text, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) })
        val chips = Ui.horizontal(c)
        s.weigh?.let { chips.addView(Ui.chip(c, tr("Peser %d g", it.grams), Icon.Kind.SCALE, Ui.TEXT2, size = 15f), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8)) }
        s.machine?.let { m ->
            val ok = m.valid
            chips.addView(Ui.chip(c, m.summary, if (ok) Icon.Kind.POT else Icon.Kind.WARNING,
                if (ok) Ui.ACCENT else Ui.WARN, if (ok) Ui.ACCENT_DIM else 0x33FFC14D, 15f))
        }
        if (chips.childCount > 0) col.addView(chips, Ui.lp(t = 8))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    // ------------------------------------------------------------------ liste de courses

    /** Choix des portions et des ingrédients à acheter (le sel ou l'huile qu'on a déjà se décochent). */
    private fun askShopping() {
        val items = recipe.allIngredients
        val chosen = BooleanArray(items.size) { true }
        var servings = recipe.servings
        val card = Ui.card(c, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(28), Ui.u(24), Ui.u(28), Ui.u(22))
        card.addView(Ui.text(c, tr("Ajouter à la liste de courses"), 24f, Ui.TEXT, Ui.MEDIUM))
        val addBtn = Ui.button(c, tr("Ajouter"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 60) {
            val picked = items.filterIndexed { i, _ -> chosen[i] }
            val n = Shopping.edit(c) { it.addIngredients(recipe.title, picked, recipe.servings, servings) }
            a.closeOverlay()
            a.toast(tr("%d ingrédients ajoutés à la liste de courses", n))
        }
        fun refreshAdd() {
            Ui.chipText(addBtn).text = tr("Ajouter (%d)", chosen.count { it })
            Ui.setButtonEnabled(addBtn, chosen.any { it })
        }
        if (recipe.servings > 0) {
            val row = Ui.horizontal(c)
            row.addView(Ui.text(c, tr("Portions"), 19f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val value = Ui.text(c, "", 26f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
            fun show() { value.text = servings.toString() }
            row.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 54) { if (servings > 1) { servings--; show() } })
            row.addView(value, LinearLayout.LayoutParams(Ui.u(70), Ui.WRAP))
            row.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 54) { if (servings < 24) { servings++; show() } })
            show()
            card.addView(row, Ui.lp(t = 14))
        }
        val listBox = Ui.vertical(c)
        for ((i, ing) in items.withIndex()) {
            val r = Ui.horizontal(c)
            r.setPadding(Ui.u(6), Ui.u(8), Ui.u(6), Ui.u(8))
            val box = Icon(c, Icon.Kind.CHECK, Ui.OK)
            box.setPadding(Ui.u(5), Ui.u(5), Ui.u(5), Ui.u(5))
            box.background = Ui.round(0, 14f, Ui.OK, 2f)
            r.addView(box, LinearLayout.LayoutParams(Ui.u(30), Ui.u(30)).apply { rightMargin = Ui.u(12) })
            val label = Ui.text(c, ing.display, 18f, Ui.TEXT)
            r.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.isClickable = true
            r.background = Ui.pressable(Ui.round(0, 12f), 12f)
            r.setOnClickListener {
                chosen[i] = !chosen[i]
                box.alpha = if (chosen[i]) 1f else 0.15f
                label.setTextColor(if (chosen[i]) Ui.TEXT else Ui.TEXT3)
                refreshAdd()
            }
            listBox.addView(r)
        }
        card.addView(Ui.scroll(c, listBox), LinearLayout.LayoutParams(Ui.MATCH, Ui.u(250)).apply { topMargin = Ui.u(10) })
        card.addView(Ui.text(c, tr("Touchez un ingrédient que vous avez déjà pour le retirer."), 15f, Ui.TEXT3), Ui.lp(t = 8))
        val actions = Ui.horizontal(c, Gravity.END or Gravity.CENTER_VERTICAL)
        actions.addView(Ui.button(c, tr("Annuler"), Ui.Style.GHOST, height = 60) { a.closeOverlay() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        actions.addView(addBtn)
        card.addView(actions, Ui.lp(t = 14))
        refreshAdd()
        a.showOverlay(card, 700)
    }

    private fun toggleFavorite() {
        val now = !repo.isFavorite(recipe.id)
        repo.setFavorite(recipe.id, now)
        refreshFavorite()
        a.toast(if (now) tr("Ajoutée aux favoris") else tr("Retirée des favoris"))
    }

    private fun refreshFavorite() {
        val f = repo.isFavorite(recipe.id)
        favIcon.set(if (f) Icon.Kind.STAR_FILLED else Icon.Kind.STAR)
        favIcon.color = if (f) Ui.ACCENT else Ui.TEXT
        favLabel.text = tr("Favori")
    }

    override fun onKnobPress(): Boolean {
        a.push(CookScreen(a, recipe, 0))
        return true
    }
}
