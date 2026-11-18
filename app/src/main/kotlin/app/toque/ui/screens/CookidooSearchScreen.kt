package app.toque.ui.screens

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.recipes.Cookidoo
import app.toque.recipes.CookidooClient
import app.toque.recipes.CookidooSync
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Recherche dans tout Cookidoo ; une recette touchée est importée, convertie et ouverte. */
class CookidooSearchScreen(a: MainActivity, private val initial: String = "") : Screen(a) {
    override val title = tr("Rechercher sur Cookidoo")
    private lateinit var input: EditText
    private lateinit var langBox: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var lang = a.prefs.tmLang
    private var page = 0
    private var query = ""
    private var busy = false

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), 0)
        val bar = Ui.horizontal(c)
        val field = Ui.horizontal(c)
        field.setPadding(Ui.u(20), 0, Ui.u(10), 0)
        field.background = Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(21f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Plat, ingrédient… (ex. : risotto, pierogi)")
            background = null
            setSingleLine()
            setText(initial)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> start(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(64), 1f))
        langBox = Ui.horizontal(c)
        bar.addView(langBox, Ui.lp(Ui.WRAP, Ui.WRAP, l = 14))
        bar.addView(Ui.button(c, tr("Chercher"), Ui.Style.PRIMARY, height = 60) { start() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        root.addView(bar)
        status = Ui.text(c, "", 17f, Ui.TEXT2)
        root.addView(status, Ui.lp(t = 12))
        list = Ui.vertical(c)
        root.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(8) })
        buildLang()
        return root
    }

    private fun buildLang() {
        langBox.removeAllViews()
        for ((code, label) in listOf("fr" to "FR", "pl" to "PL")) {
            langBox.addView(Ui.toggleChip(c, label, lang == code) { lang = code; buildLang(); if (query.isNotEmpty()) start() },
                Ui.lp(Ui.WRAP, Ui.WRAP, r = 6))
        }
    }

    private fun start() {
        a.hideKeyboard()
        query = input.text.toString().trim()
        page = 0
        list.removeAllViews()
        load()
    }

    private fun load() {
        if (busy) return
        if (!CookidooClient(c).loggedIn) {
            status.text = tr("Connectez d'abord votre compte Cookidoo : Réglages › Recettes Thermomix.")
            return
        }
        busy = true
        status.text = tr("Recherche…")
        val l = lang
        val q = query
        val p = page
        Thread {
            val result = try {
                Result.success(Cookidoo.parseSearch(CookidooClient(c).search(l, q, p)))
            } catch (e: Exception) {
                Result.failure(e)
            }
            view.post {
                busy = false
                result.onFailure { status.text = tr("Cookidoo indisponible : %s", it.message ?: it.toString()) }
                result.onSuccess { r ->
                    status.text = if (r.hits.isEmpty() && p == 0) tr("Aucun résultat.") else tr("%d recettes trouvées", r.total)
                    (list.getChildAt(list.childCount - 1)?.tag as? String)?.let { if (it == "more") list.removeViewAt(list.childCount - 1) }
                    for (h in r.hits) list.addView(row(h, l), Ui.lp(t = 8))
                    if (r.hits.isNotEmpty() && (p + 1) * 20 < r.total) {
                        val more = Ui.button(c, tr("Plus de résultats"), Ui.Style.GHOST, height = 52, textSize = 16f) { page++; load() }
                        more.tag = "more"
                        list.addView(more, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12, b = 16))
                    }
                }
            }
        }.start()
    }

    private fun row(h: Cookidoo.Hit, l: String): View {
        val r = Ui.horizontal(c)
        r.setPadding(Ui.u(18), Ui.u(12), Ui.u(14), Ui.u(12))
        r.background = Ui.pressable(Ui.round(Ui.SURFACE, 18f), 18f)
        val col = Ui.vertical(c)
        col.addView(Ui.ellipsize(Ui.text(c, h.title, 19f, Ui.TEXT, Ui.MEDIUM), 2))
        if (h.totalMinutes > 0) col.addView(Ui.text(c, Ui.duration(h.totalMinutes * 60), 15f, Ui.TEXT3))
        r.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val have = a.app.tm(l).get(Cookidoo.toqueId(h.id)) != null
        r.addView(Ui.chip(c, if (have) tr("Sur le robot") else tr("Importer"), if (have) Icon.Kind.CHECK else Icon.Kind.PLUS,
            if (have) Ui.OK else Ui.ACCENT), Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        r.isClickable = true
        r.setOnClickListener { open(h, l) }
        return r
    }

    private fun open(h: Cookidoo.Hit, l: String) {
        val repo = a.app.tm(l)
        repo.get(Cookidoo.toqueId(h.id))?.let { a.push(RecipeScreen(a, it, repo)); return }
        if (busy) return
        busy = true
        a.toast(tr("Import et conversion de « %s »…", h.title))
        Thread {
            val result = try {
                Result.success(CookidooSync.importOne(c, l, h.id))
            } catch (e: Exception) {
                Result.failure(e)
            }
            view.post {
                busy = false
                result.onSuccess { (r, cat) -> a.push(RecipeScreen(a, r, a.app.tm(cat))) }
                result.onFailure { a.toast(tr("Import impossible : %s", it.message ?: it.toString()), Ui.WARN) }
            }
        }.start()
    }

    override fun onShow() {
        if (initial.isNotEmpty() && list.childCount == 0) start()
    }
}
