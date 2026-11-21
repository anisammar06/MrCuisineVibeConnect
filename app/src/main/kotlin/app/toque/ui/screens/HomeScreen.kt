package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.recipes.SyncProgress
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date

class HomeScreen(a: MainActivity) : Screen(a) {
    override val title = "Gotuj z Kasią"
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var recipesSub: TextView
    private lateinit var favSub: TextView
    private lateinit var shopSub: TextView
    private lateinit var tmSub: TextView
    private var webSub: TextView? = null
    private lateinit var timerSub: TextView
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private var unsub: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            view.postDelayed(this, 10_000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(10), Ui.u(24), Ui.u(24))

        val left = Ui.vertical(c)
        greeting = Ui.text(c, "", 24f, Ui.TEXT2, Ui.LIGHT)
        left.addView(greeting, Ui.lp(t = 8))
        clock = Ui.text(c, "", 132f, Ui.TEXT, Ui.THIN)
        left.addView(clock, Ui.lp(t = 2))
        date = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
        left.addView(date, Ui.lp(t = 2))

        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(24), 0, Ui.u(24), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 36f, Ui.STROKE, 1f), 36f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(14) })
        search.addView(Ui.text(c, tr("Rechercher une recette…"), 21f, Ui.TEXT3))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(72), t = 34))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(22), Ui.u(16), Ui.u(18), Ui.u(16))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 2)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        // Abandonner la recette commencée (la pastille disparaît).
        val cancel = Icon(c, Icon.Kind.CLOSE, Ui.TEXT2)
        cancel.setPadding(Ui.u(12), Ui.u(12), Ui.u(12), Ui.u(12))
        cancel.background = Ui.pressable(Ui.round(0x22FFFFFF, 22f), 22f)
        cancel.isClickable = true
        cancel.setOnClickListener {
            a.prefs.guidedRecipe = ""
            a.prefs.guidedStep = 0
            a.toast(tr("Recette abandonnée"))
            refresh()
        }
        resumeCard.addView(cancel, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { leftMargin = Ui.u(10) })
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.repoOf(a.prefs.guidedRecipe).get(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(28) })

        // Grille de 9 tuiles (3 × 3). Réglages : engrenage de la barre du haut.
        val grid = Ui.vertical(c)
        val rows = List(3) { Ui.horizontal(c) }
        val (t1, s1) = tile(Icon.Kind.BOOK, tr("Monsieur Cuisine")) { a.push(RecipesScreen(a)) }
        recipesSub = s1
        rows[0].addView(t1, tileLp(true))
        rows[0].addView(tile(Icon.Kind.POT, tr("Cuisson manuelle"), tr("Temps, chaleur, vitesse")) { a.push(ManualScreen(a)) }.first, tileLp(true))
        rows[0].addView(tile(Icon.Kind.KNEAD, tr("Programmes"), tr("Pétrir, vapeur, rissoler")) { a.push(ProgramsScreen(a)) }.first, tileLp(false))
        // La balance reste accessible depuis la barre du haut ; à sa place, les recettes polonaises du web.
        val (t4, s4) = tile(Icon.Kind.BOOK, tr("Recettes polonaises")) { a.push(RecipesScreen(a, web = true)) }
        webSub = s4
        rows[1].addView(t4, tileLp(true))
        val (t5, s5) = tile(Icon.Kind.STAR, tr("Favoris")) { a.push(RecipesScreen(a, favorites = true)) }
        favSub = s5
        rows[1].addView(t5, tileLp(true))
        rows[1].addView(tile(Icon.Kind.PLAY, tr("Médias"), tr("Vidéos, musique, partage")) { a.push(MediaScreen(a)) }.first, tileLp(false))
        val (t7, s7) = tile(Icon.Kind.BOOK, tr("Recettes Thermomix")) { a.push(RecipesScreen(a, thermomix = true)) }
        tmSub = s7
        rows[2].addView(t7, tileLp(true))
        val (t8, s8) = tile(Icon.Kind.CHECK, tr("Liste de courses")) { a.push(ShoppingScreen(a)) }
        shopSub = s8
        rows[2].addView(t8, tileLp(true))
        val (t9, s9) = tile(Icon.Kind.TIMER, tr("Minuteurs")) { a.push(TimersScreen(a)) }
        timerSub = s9
        rows[2].addView(t9, tileLp(false))
        for ((i, r) in rows.withIndex()) grid.addView(r, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { if (i < 2) bottomMargin = Ui.u(12) })
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(12) }

    /** Tuile d'une fonction prévue dans une prochaine version. */
    private fun soon(icon: Icon.Kind, title: String, sub: String): LinearLayout {
        val (t, _) = tile(icon, title, sub) { a.toast(tr("Arrive dans une prochaine version")) }
        t.alpha = 0.55f
        return t
    }

    private fun tile(icon: Icon.Kind, title: String, sub: String = "", onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val t = Ui.vertical(c)
        t.setPadding(Ui.u(14), Ui.u(12), Ui.u(12), Ui.u(10))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f)
        val badge = android.widget.FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 20f)
        badge.addView(Icon(c, icon, Ui.ACCENT), Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
        t.addView(badge, LinearLayout.LayoutParams(Ui.u(40), Ui.u(40)))
        t.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        t.addView(Ui.ellipsize(Ui.text(c, title, 18f, Ui.TEXT, Ui.MEDIUM), 1))
        val s = Ui.ellipsize(Ui.text(c, sub, 13f, Ui.TEXT2), 1)
        t.addView(s, Ui.lp(t = 2))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t to s
    }

    private fun refreshClock() {
        clock.text = MainActivity.now()
        date.text = SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> tr("Bonjour")
            in 12..17 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }
    }

    private fun refresh() {
        val repo = a.app.recipes
        val n = repo.count
        recipesSub.text = when {
            SyncProgress.running -> SyncProgress.text
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette Lidl pour l'instant")
            a.app.recipesPl.count > 0 -> tr("Lidl · %d FR · %d PL", a.app.recipesFr.count, a.app.recipesPl.count)
            else -> tr("%d recettes Lidl", n)
        }
        val favs = repo.index.entries.count { it.favorite }
        favSub.text = if (favs == 0) tr("Aucun favori") else if (favs == 1) tr("1 favori") else tr("%d favoris", favs)
        val tmFr = a.app.tmFr.count
        val tmPl = a.app.tmPl.count
        tmSub.text = when {
            app.toque.recipes.CookidooProgress.running -> app.toque.recipes.CookidooProgress.text
            tmFr + tmPl > 0 -> tr("Cookidoo · %d FR · %d PL", tmFr, tmPl)
            app.toque.recipes.CookidooClient(c).loggedIn -> tr("Synchro à lancer")
            else -> tr("Cookidoo à connecter")
        }
        val wa = a.app.webAnia.count
        val wk = a.app.webKwestia.count
        webSub?.text = when {
            app.toque.recipes.WebProgress.running -> tr("Téléchargement… Ania %d · Kwestia %d", wa, wk)
            wa + wk > 0 -> tr("Ania %d · Kwestia %d", wa, wk)
            else -> tr("Ania Gotuje, Kwestia Smaku")
        }
        val shop = app.toque.kitchen.Shopping.get(c).remaining
        shopSub.text = if (shop == 0) tr("Depuis les recettes") else tr("%d à acheter", shop)
        val now = System.currentTimeMillis()
        val running = app.toque.kitchen.Timers.get(c).timers.count { !it.finished(now) }
        timerSub.text = if (running == 0) tr("Plusieurs à la fois") else tr("%d en cours", running)
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else a.app.repoOf(guided).index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
    }

    override fun onShow() {
        refreshClock()
        refresh()
        val u1a = a.app.recipesFr.onChange { refresh() }
        val u1b = a.app.recipesPl.onChange { refresh() }
        val u1 = { u1a(); u1b() }
        val u2 = SyncProgress.onChange { refresh() }
        val u3 = app.toque.kitchen.Shopping.onChange { refresh() }
        val u4 = app.toque.kitchen.Timers.onChange { refresh() }
        val u5 = a.app.tmFr.onChange { refresh() }
        val u6 = a.app.tmPl.onChange { refresh() }
        val u7 = app.toque.recipes.CookidooProgress.onChange { refresh() }
        val u8 = a.app.webAnia.onChange { refresh() }
        val u9 = a.app.webKwestia.onChange { refresh() }
        val u10 = app.toque.recipes.WebProgress.onChange { refresh() }
        unsub = { u1(); u2(); u3(); u4(); u5(); u6(); u7(); u8(); u9(); u10() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, 10_000)
    }

    override fun onHide() {
        unsub?.invoke()
        view.removeCallbacks(tick)
    }

}
