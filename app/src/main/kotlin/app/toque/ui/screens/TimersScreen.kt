package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.kitchen.KitchenTimer
import app.toque.core.kitchen.TimerBoard
import app.toque.kitchen.Timers
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.ui.ValueCard

/** Plusieurs minuteurs nommés, indépendants de la cuisson du robot. */
class TimersScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Minuteurs")
    private lateinit var display: TextView
    private lateinit var names: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private var seconds = 5 * 60
    private var name = ""
    private var unsub: (() -> Unit)? = null
    private val rows = HashMap<String, Row>()

    private class Row(val view: View, val time: TextView, val state: TextView, val progress: ProgressLine,
                      val pause: LinearLayout, val stop: LinearLayout)

    private val presetNames get() = listOf(tr("Four"), tr("Pâtes"), tr("Riz"), tr("Œufs"), tr("Légumes"), tr("Repos"))

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- nouveau minuteur
        val left = Ui.card(c, 24f)
        left.addView(Ui.text(c, tr("NOUVEAU MINUTEUR"), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f })
        names = Ui.horizontal(c)
        val namesScroll = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(names) }
        left.addView(namesScroll, Ui.lp(t = 12))
        buildNames()

        val timeRow = Ui.horizontal(c, Gravity.CENTER)
        val minus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 72) { step(-60) }
        ValueCard.repeatOnHold(minus) { step(-60) }
        timeRow.addView(minus, Ui.lp(Ui.u(84), Ui.WRAP))
        display = Ui.text(c, "", 76f, Ui.TEXT, Ui.THIN).apply { gravity = Gravity.CENTER }
        timeRow.addView(display, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val plus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 72) { step(60) }
        ValueCard.repeatOnHold(plus) { step(60) }
        timeRow.addView(plus, Ui.lp(Ui.u(84), Ui.WRAP))
        left.addView(timeRow, Ui.lp(t = 10))

        val fine = Ui.horizontal(c, Gravity.CENTER)
        fine.addView(Ui.button(c, tr("−10 s"), Ui.Style.GHOST, height = 48, textSize = 16f) { step(-10) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        fine.addView(Ui.button(c, tr("+10 s"), Ui.Style.GHOST, height = 48, textSize = 16f) { step(10) })
        left.addView(fine, Ui.lp(t = 4))

        val presets = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
        val grid = Ui.vertical(c)
        for (line in presets.chunked(5)) {
            val r = Ui.horizontal(c)
            for (m in line) r.addView(Ui.toggleChip(c, tr("%d min", m), false) { seconds = m * 60; refreshDisplay() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            grid.addView(r, Ui.lp(t = 8))
        }
        left.addView(grid, Ui.lp(t = 8))
        left.addView(Ui.spacer(c), LinearLayout.LayoutParams(1, 0, 1f))
        left.addView(Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66, textSize = 22f) { startTimer() }, Ui.lp(t = 12))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.48f).apply { rightMargin = Ui.u(20) })

        // ---- minuteurs en cours
        val right = Ui.vertical(c)
        right.addView(Ui.text(c, tr("EN COURS"), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f; setPadding(0, Ui.u(10), 0, 0) })
        empty = Ui.text(c, tr("Aucun minuteur. Ils restent visibles en haut de tous les écrans et sonnent même pendant une vidéo."), 17f, Ui.TEXT3)
            .apply { setLineSpacing(0f, 1.15f) }
        right.addView(empty, Ui.lp(t = 12))
        list = Ui.vertical(c)
        right.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6) })
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.52f))
        refreshDisplay()
        return root
    }

    private fun buildNames() {
        names.removeAllViews()
        for (n in presetNames) {
            names.addView(Ui.toggleChip(c, n, n == name) { name = if (name == n) "" else n; buildNames() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
    }

    private fun step(d: Int) {
        seconds = (seconds + d).coerceIn(10, 6 * 3600)
        refreshDisplay()
    }

    private fun refreshDisplay() {
        display.text = TimerBoard.format(seconds * 1000L)
    }

    private fun startTimer() {
        val label = name.ifEmpty { tr("Minuteur %d", Timers.get(c).timers.size + 1) }
        Timers.edit(c) { b, now -> b.start(label, seconds * 1000L, now) }
        a.toast(tr("« %s » : %s", label, TimerBoard.format(seconds * 1000L)))
        name = ""
        buildNames()
    }

    // ------------------------------------------------------------------ liste

    private fun render() {
        val now = System.currentTimeMillis()
        val timers = Timers.get(c).timers
        empty.visibility = if (timers.isEmpty()) View.VISIBLE else View.GONE
        val ids = timers.map { it.id }.toSet()
        if (rows.keys != ids) {
            list.removeAllViews()
            rows.clear()
            for (t in timers) {
                val r = makeRow(t)
                rows[t.id] = r
                list.addView(r.view, Ui.lp(t = 12))
            }
        }
        for (t in timers) update(rows[t.id] ?: continue, t, now)
    }

    private fun makeRow(t: KitchenTimer): Row {
        val box = Ui.card(c, 20f)
        box.setPadding(Ui.u(18), Ui.u(14), Ui.u(14), Ui.u(14))
        val head = Ui.horizontal(c)
        val col = Ui.vertical(c)
        col.addView(Ui.ellipsize(Ui.text(c, t.name, 20f, Ui.TEXT, Ui.MEDIUM)))
        val state = Ui.text(c, "", 15f, Ui.TEXT2)
        col.addView(state)
        head.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val time = Ui.text(c, "", 40f, Ui.TEXT, Ui.LIGHT)
        head.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        box.addView(head)
        val progress = ProgressLine(c)
        box.addView(progress, Ui.lp(Ui.MATCH, Ui.u(6), t = 8))
        val actions = Ui.horizontal(c)
        val pause = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 52) {
            Timers.edit(c) { b, now -> val x = b.get(t.id); if (x?.paused == true) b.resume(t.id, now) else b.pause(t.id, now) }
        }
        actions.addView(pause, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        actions.addView(Ui.button(c, tr("+1 min"), Ui.Style.SECONDARY, height = 52, textSize = 17f) {
            Timers.edit(c) { b, now -> b.add(t.id, 60_000, now) }
        }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        val stop = Ui.button(c, tr("Arrêter"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, textSize = 17f) {
            Timers.edit(c) { b, _ -> b.dismiss(t.id) }
        }
        actions.addView(stop, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        actions.addView(Ui.spacer(c), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(Ui.button(c, "", Ui.Style.GHOST, Icon.Kind.CLOSE, height = 52) {
            Timers.edit(c) { b, _ -> b.remove(t.id) }
        })
        box.addView(actions, Ui.lp(t = 10))
        return Row(box, time, state, progress, pause, stop)
    }

    private fun update(r: Row, t: KitchenTimer, now: Long) {
        val left = t.leftMs(now)
        val done = t.finished(now)
        r.time.text = if (done) "0:00" else TimerBoard.format(left)
        r.time.setTextColor(when {
            t.ringing(now) -> Ui.DANGER
            t.paused -> Ui.WARN
            else -> Ui.TEXT
        })
        r.state.text = when {
            t.ringing(now) -> tr("Sonne !")
            done -> tr("Terminé")
            t.paused -> tr("En pause")
            else -> tr("Sur %s", TimerBoard.format(t.durationMs))
        }
        r.progress.progress = if (t.durationMs > 0) 1f - left.toFloat() / t.durationMs else 1f
        r.pause.visibility = if (done) View.GONE else View.VISIBLE
        (r.pause.getChildAt(0) as Icon).set(if (t.paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
        r.stop.visibility = if (t.ringing(now)) View.VISIBLE else View.GONE
    }

    override fun onShow() {
        render()
        unsub = Timers.onChange { render() }
    }

    override fun onHide() {
        unsub?.invoke()
        unsub = null
    }

    override fun onKnob(delta: Int): Boolean {
        step(delta * 60)
        return true
    }

    override fun onKnobPress(): Boolean {
        startTimer()
        return true
    }
}
