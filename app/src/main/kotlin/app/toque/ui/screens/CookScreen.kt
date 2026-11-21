package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.StepIngredients
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.StepProgram
import app.toque.kitchen.Cooked
import app.toque.ui.HoldButton
import app.toque.ui.ImageLoader
import app.toque.ui.MotorView
import app.toque.ui.WeighGauge
import app.toque.assistant.Remarks
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Cuisine pas à pas. Chaque étape « machine » se lance d'un appui ; à la fin du programme,
 * l'étape suivante s'affiche toute seule. Les réglages qui se suivent sans geste à faire entre
 * eux partent ensemble, en un seul programme : la machine passe de l'un à l'autre sans arrêter
 * la lame (sauf changement de sens). La progression est mémorisée (reprise après coupure).
 */
class CookScreen(a: MainActivity, val recipe: Recipe, startStep: Int) : Screen(a) {
    override val title = recipe.title
    /** Étape affichée (pour l'assistant). */
    val stepIndex: Int get() = index
    private var index = startStep.coerceIn(0, maxOf(0, recipe.steps.size - 1))
    private val reviewed = HashSet<Int>()
    private val done = HashSet<Int>()
    private var launchedStep = -1
    /** Étapes couvertes par le programme lancé (plusieurs quand elles sont enchaînées). */
    private var launchedCount = 1
    /** Dernière phase de la série vue à l'écran (on ne suit que les changements de phase). */
    private var seenPhase = -1
    /** « Mixer quand même » à chaud déjà accepté pendant cette recette : pas de nouvelle question. */
    private var hotBlendOk = false
    /** Message fixe de l'étape (vérification, refus), réaffiché hors cuisson. */
    private var panelInfo = ""

    private lateinit var segments: LinearLayout
    private lateinit var counter: TextView
    private lateinit var text: TextView
    private lateinit var weighBox: LinearLayout
    private lateinit var weighLabel: TextView
    private lateinit var weighValue: TextView
    private lateinit var weighBar: ProgressLine
    private lateinit var gauge: WeighGauge
    private var motor: MotorView? = null
    private lateinit var ingRow: LinearLayout
    private lateinit var stepPic: android.widget.ImageView
    /** Ce qu'on pèse en ce moment (cible de l'étape, ou ingrédient choisi) ; null : balance seule. */
    private var weighTarget: Weigh? = null
    private var scaleOpen = false
    private var reachedFor: Weigh? = null
    private lateinit var panel: LinearLayout
    private lateinit var prevBtn: LinearLayout
    private lateinit var nextBtn: LinearLayout
    private lateinit var nextLabel: TextView

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(18))
        segments = Ui.horizontal(c)
        root.addView(segments, Ui.lp(Ui.MATCH, Ui.u(6), b = 14))

        val body = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        counter = Ui.text(c, "", 17f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.06f }
        left.addView(counter)
        text = Ui.text(c, "", 31f, Ui.TEXT, Ui.LIGHT).apply { setLineSpacing(0f, 1.18f) }
        // Texte de l'étape, et sa photo quand la recette en a (recettes polonaises du web).
        val textCol = Ui.vertical(c)
        textCol.addView(text)
        stepPic = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        stepPic.background = Ui.round(Ui.SURFACE2, 16f)
        stepPic.clipToOutline = true
        stepPic.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.uf(16f))
        }
        textCol.addView(stepPic, LinearLayout.LayoutParams(Ui.u(360), Ui.u(240)).apply { topMargin = Ui.u(12) })
        left.addView(Ui.scroll(c, textCol), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(12) })
        // Ingrédients cités dans l'étape, et la balance à portée de main.
        ingRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        left.addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(ingRow)
        }, Ui.lp(t = 10))

        // Balance : jauge circulaire animée + objectif + tare.
        weighBox = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        weighBox.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF1B2230.toInt(), 0xFF15171B.toInt())).apply { cornerRadius = Ui.uf(22f); setStroke(Ui.u(1), 0x3349A6FF) }
        weighBox.setPadding(Ui.u(12), Ui.u(8), Ui.u(16), Ui.u(8))
        gauge = WeighGauge(c)
        weighBox.addView(gauge, LinearLayout.LayoutParams(Ui.u(178), Ui.u(178)).apply { rightMargin = Ui.u(14) })
        val wCol = Ui.vertical(c)
        weighLabel = Ui.text(c, "", 21f, Ui.TEXT, Ui.MEDIUM).apply { maxLines = 2 }
        wCol.addView(weighLabel)
        weighValue = Ui.text(c, "", 16f, Ui.TEXT2)
        wCol.addView(weighValue, Ui.lp(t = 4))
        val wBtns = Ui.horizontal(c)
        wBtns.addView(Ui.button(c, tr("Tare"), Ui.Style.SECONDARY, Icon.Kind.SCALE, height = 52) { a.machine.tare() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        wBtns.addView(Ui.button(c, "", Ui.Style.GHOST, Icon.Kind.CLOSE, height = 52) {
            weighTarget = null
            scaleOpen = false
            renderWeigh()
        })
        wCol.addView(wBtns, Ui.lp(t = 12))
        weighBox.addView(wCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        weighBar = ProgressLine(c)
        left.addView(weighBox, Ui.lp(t = 12))
        body.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(22) })

        panel = Ui.vertical(c)
        panel.background = Ui.round(Ui.SURFACE, 26f)
        panel.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
        body.addView(panel, LinearLayout.LayoutParams(Ui.u(340), Ui.MATCH))
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val nav = Ui.horizontal(c)
        prevBtn = Ui.button(c, tr("Précédent"), Ui.Style.GHOST, Icon.Kind.BACK, height = 64) { go(index - 1) }
        nav.addView(prevBtn)
        nav.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        nextBtn = Ui.button(c, tr("Suivant"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 64) { next() }
        nextLabel = nextBtn.getChildAt(1) as TextView
        nav.addView(nextBtn)
        root.addView(nav, Ui.lp(t = 14))
        render()
        return root
    }

    /** Commande vocale « étape suivante » / « précédente ». */
    fun voiceNext() = next()
    fun voicePrev() = go(index - 1)

    private fun next() {
        if (index >= recipe.steps.size - 1) {
            a.prefs.guidedRecipe = ""
            Cooked.add(a, recipe.id, recipe.title)
            a.chime(1)
            a.toast(tr("Bon appétit !"), Ui.OK)
            a.back()
        } else go(index + 1)
    }

    private fun go(i: Int) {
        if (i < 0 || i >= recipe.steps.size) return
        index = i
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = index
        render()
    }

    private fun render() {
        val n = recipe.steps.size
        segments.removeAllViews()
        for (i in 0 until n) {
            val v = View(c)
            v.background = Ui.round(if (i < index) Ui.ACCENT else if (i == index) Ui.TEXT else Ui.SURFACE3, 4f)
            segments.addView(v, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < n - 1) rightMargin = Ui.u(6) })
        }
        if (n == 0) {
            counter.text = ""
            text.text = tr("Cette recette ne contient pas d'étapes.")
            weighBox.visibility = View.GONE
            panel.removeAllViews()
            return
        }
        val step = recipe.steps[index]
        counter.text = tr("ÉTAPE %d SUR %d", index + 1, n)
        text.text = step.text.ifBlank { step.machine?.summary ?: "" }
        // La photo va dans le panneau de droite quand l'étape n'a pas de réglage machine ; sinon sous le texte.
        val picLeft = step.image.isNotBlank() && step.machine != null
        stepPic.visibility = if (picLeft) View.VISIBLE else View.GONE
        if (picLeft) ImageLoader.loadUrl(stepPic, step.image, Ui.u(360))
        // Longues étapes (recettes du web) : un peu plus petit pour tout voir sans défiler.
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(if (text.text.length > 420) 22f else if (text.text.length > 220) 26f else 31f))
        val found = StepIngredients.match(step.text, recipe.allIngredients)
        val firstGrams = found.firstOrNull { StepIngredients.grams(it) != null }
        weighTarget = step.weigh ?: firstGrams?.let { Weigh(it.name, StepIngredients.grams(it)!!) }
        scaleOpen = weighTarget != null
        ingRow.removeAllViews()
        for (ing in found.take(8)) {
            val g = StepIngredients.grams(ing)
            ingRow.addView(Ui.toggleChip(c, ing.display, false) {
                if (g != null) {
                    weighTarget = Weigh(ing.name, g)
                    scaleOpen = true
                    renderWeigh()
                }
            }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        ingRow.addView(Ui.button(c, tr("Balance"), Ui.Style.GHOST, Icon.Kind.SCALE, height = 50, textSize = 16f) {
            scaleOpen = true
            weighTarget = null
            renderWeigh()
        }, Ui.lp(Ui.WRAP, Ui.WRAP))
        renderWeigh()
        Ui.setButtonEnabled(prevBtn, index > 0)
        nextLabel.text = if (index == n - 1) tr("Terminer") else tr("Suivant")
        buildPanel()
        onSnapshot(a.snapshot)
    }

    private lateinit var startBtn: View
    private var turboBtn: HoldButton? = null
    private var reviewBtn: View? = null
    private var panelState: TextView? = null

    private fun buildPanel() {
        panel.removeAllViews()
        motor = null
        turboBtn = null
        reviewBtn = null
        val m = recipe.steps[index].machine
        val photo = recipe.steps[index].image
        if (m == null && photo.isNotBlank()) {
            // Étape sans réglage machine (recettes polonaises) : sa photo occupe le panneau de droite.
            val pic = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
            pic.background = Ui.round(Ui.SURFACE2, 18f)
            pic.clipToOutline = true
            pic.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, Ui.uf(18f))
            }
            panel.addView(pic, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(12) })
            ImageLoader.loadUrl(pic, photo, Ui.u(420))
            startBtn = Ui.button(c, tr("Étape suivante"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 70) { next() }
            panel.addView(startBtn)
            return
        }
        if (m == null) {
            val box = Ui.vertical(c, Gravity.CENTER)
            box.addView(Icon(c, Icon.Kind.CHECK, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
            box.addView(Ui.text(c, tr("Pas de réglage machine pour cette étape"), 18f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 12))
            panel.addView(box, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            startBtn = Ui.button(c, tr("Étape suivante"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 70) { next() }
            panel.addView(startBtn)
            return
        }
        val head = Ui.horizontal(c)
        val badge = FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 24f)
        val kindIcon = when (m.program) {
            StepProgram.KNEAD -> Icon.Kind.KNEAD
            StepProgram.STEAM -> Icon.Kind.STEAM
            StepProgram.TOAST -> Icon.Kind.FLAME
            StepProgram.TURBO -> Icon.Kind.BOLT
            StepProgram.MANUAL -> Icon.Kind.POT
        }
        badge.addView(Icon(c, kindIcon, Ui.ACCENT), Ui.flp(Ui.u(28), Ui.u(28), Gravity.CENTER))
        head.addView(badge, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(12) })
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(c, when (m.program) {
            StepProgram.MANUAL -> tr("Réglage machine")
            StepProgram.KNEAD -> JobKind.KNEAD.label
            StepProgram.STEAM -> JobKind.STEAM.label
            StepProgram.TOAST -> JobKind.TOAST.label
            StepProgram.TURBO -> JobKind.TURBO.label
        }, 22f, Ui.TEXT, Ui.MEDIUM))
        panel.addView(head)
        // Animation du moteur (tourne pendant le programme, halo selon la température).
        val mv = MotorView(c)
        motor = mv
        panel.addView(mv, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(96)).apply { topMargin = Ui.u(6) })

        val grid = Ui.vertical(c)
        fun line(icon: Icon.Kind, label: String, value: String, color: Int = Ui.TEXT) {
            val r = Ui.horizontal(c)
            r.addView(Icon(c, icon, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
            r.addView(Ui.text(c, label, 17f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.text(c, value, 24f, color, Ui.LIGHT))
            grid.addView(r, Ui.lp(t = 10))
        }
        if (m.program == StepProgram.TURBO) {
            line(Icon.Kind.BOLT, tr("Impulsions"), if (m.pulses > 0) "× ${m.pulses}" else tr("au besoin"))
        } else {
            line(Icon.Kind.CLOCK, tr("Durée"), Ui.duration(m.seconds))
            if (m.program == StepProgram.MANUAL) {
                line(Icon.Kind.THERMO, tr("Température"), if (m.tempC > 0) "${Mcc.celsiusForLevel(m.temperatureLevel)} °C" else "—",
                    if (m.tempC > 0) Ui.tempColor(m.tempC) else Ui.TEXT2)
                line(Icon.Kind.SPEED, tr("Vitesse"), "${m.speed}")
                if (m.reverse) line(Icon.Kind.REVERSE, tr("Sens"), tr("Inverse"), Ui.COOL)
                if (m.accessory == Accessory.WHISK) line(Icon.Kind.WHISK, tr("Accessoire"), Accessory.WHISK.label, Ui.WARN)
            }
        }
        panel.addView(grid, Ui.lp(t = 6))
        val chained = if (a.prefs.chainSteps) chainLength(index) else 0
        if (chained > 0) {
            val next = (index + 1..index + chained).map { recipe.steps[it].machine!!.summary }
            val list = next.take(4).joinToString("\n") { "• $it" } +
                if (next.size > 4) "\n" + tr("… et %d autres", next.size - 4) else ""
            panel.addView(Ui.text(c, tr("Puis, enchaînés automatiquement :") + "\n" + list, 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
        }
        panelState = Ui.text(c, "", 16f, Ui.WARN).apply { setLineSpacing(0f, 1.1f) }
        panel.addView(panelState, Ui.lp(t = 12))
        panel.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))

        val violations = m.violations()
        val step = recipe.steps[index]
        panelInfo = ""
        if (violations.isNotEmpty()) {
            panelInfo = tr("Réglage refusé par la sécurité : %s. Utilisez la cuisson manuelle.", violations.first().message)
        }
        if (step.needsReview && index !in reviewed && violations.isEmpty()) {
            val rb = Ui.button(c, tr("J'ai vérifié ce réglage"), Ui.Style.GHOST, Icon.Kind.CHECK, height = 56, textSize = 17f) {
                reviewed += index
                buildPanel()
                onSnapshot(a.snapshot)
            }
            reviewBtn = rb
            panel.addView(rb, Ui.lp(b = 10))
            panelInfo = tr("Réglage importé : vérifiez-le avant de lancer.")
        }
        panelState?.text = panelInfo
        if (m.program == StepProgram.TURBO) {
            val t = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
                a.machine.turboDown(m.accessory) { v -> if (!v.ok) a.refusal(v) }
            }, onUp = { a.machine.turboUp() })
            turboBtn = t
            startBtn = t
            panel.addView(t, Ui.lp(Ui.MATCH, Ui.u(70)))
        } else {
            startBtn = Ui.button(c, tr("Lancer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 74, textSize = 23f) { launch() }
            panel.addView(startBtn)
        }
        if (m.program == StepProgram.MANUAL) {
            panel.addView(Ui.button(c, tr("Ajuster à la main"), Ui.Style.GHOST, height = 50, textSize = 16f) {
                a.push(ManualScreen(a, m))
            }, Ui.lp(t = 8))
        }
        val blocked = violations.isNotEmpty() || (step.needsReview && index !in reviewed)
        Ui.setButtonEnabled(startBtn, !blocked)
    }

    private fun launch() {
        val first = index
        val m = recipe.steps[first].machine ?: return
        val series = series(first)
        val job = series ?: m.toJob(tr("Étape %d · %s", first + 1, recipe.title), hotBlendConfirmed = hotBlendOk) ?: return
        val count = series?.phases?.size ?: 1
        Cooked.add(a, recipe.id, recipe.title)
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = first
        a.startJob(job, onHotConfirmed = { hotBlendOk = true }) { v ->
            if (v.ok) {
                launchedStep = first
                launchedCount = count
                seenPhase = 0
            }
        }
    }

    /**
     * Réglages enchaînés à partir de [first], réunis en un seul programme (une phase par étape) :
     * la machine passe directement au réglage suivant, sans arrêt ni nouvel appui. Uniquement
     * des réglages manuels avec le même accessoire ; sinon les étapes s'enchaînent une à une.
     */
    private fun series(first: Int): JobSpec? {
        if (!a.prefs.chainSteps) return null
        val n = chainLength(first)
        if (n == 0) return null
        val machines = (first..first + n).map { recipe.steps[it].machine ?: return null }
        if (machines.any { it.program != StepProgram.MANUAL || it.accessory != machines[0].accessory }) return null
        val phases = machines.map { it.toJob("", hotBlendOk)?.phases?.singleOrNull() ?: return null }
        val spec = JobSpec(
            kind = JobKind.MANUAL,
            label = tr("Étapes %d à %d · %s", first + 1, first + n + 1, recipe.title),
            phases = phases,
            totalMs = phases.sumOf { it.durationMs },
            cyclic = false,
            accessory = machines[0].accessory,
            hotBlendConfirmed = hotBlendOk,
        )
        return spec.takeIf { SafetyPolicy.checkJob(it, null).isEmpty() }
    }

    /**
     * L'étape [i] continue directement la précédente : un réglage machine seul (pas de texte
     * à suivre, pas de pesée), après une autre étape machine. Le Turbo reste toujours à l'appui.
     */
    private fun continues(i: Int): Boolean {
        val s = recipe.steps.getOrNull(i) ?: return false
        val m = s.machine ?: return false
        val prev = recipe.steps.getOrNull(i - 1)?.machine ?: return false
        return m.program != StepProgram.TURBO && prev.program != StepProgram.TURBO && s.weigh == null &&
            (s.text.isBlank() || s.text == m.summary) && m.violations().isEmpty()
    }

    /** Nombre d'étapes qui s'enchaîneront après l'étape [i]. */
    private fun chainLength(i: Int): Int {
        var n = 0
        while (continues(i + n + 1)) n++
        return n
    }

    private fun renderWeigh() {
        val w = weighTarget
        weighBox.visibility = if (scaleOpen) View.VISIBLE else View.GONE
        weighLabel.text = if (w != null) tr("Pesez %d g", w.grams) + if (w.label.isNotBlank()) " · ${w.label}" else "" else tr("Balance")
        weighValue.text = if (w != null) tr("Posez le bol sur la base, faites la tare, puis versez.") else tr("Pesez librement ; la tare remet à zéro.")
        gauge.target = w?.grams ?: 0
        updateWeigh(a.snapshot)
    }

    private fun updateWeigh(s: Snapshot) {
        val w = weighTarget
        if (scaleOpen) {
            gauge.connected = s.hasSample
            gauge.grams = s.weightG
            if (w != null && s.hasSample && s.scaleStable && s.weightG >= w.grams * 0.97f && s.weightG <= w.grams * 1.05f && reachedFor != w) {
                reachedFor = w
                Remarks.weighed(a, w.label, s.weightG)
            }
        }
        motor?.let { m ->
            m.running = s.jobActive && s.motorOn
            m.speed = s.speedLevel
            m.turbo = s.turboActive
            m.reverse = s.jobReverse
            m.tempC = s.actualTempC
            m.heating = s.heaterOn
        }
    }

    override fun onSnapshot(s: Snapshot) {
        updateWeigh(s)
        val running = launchedStep >= 0 && s.jobActive
        if (running && launchedCount > 1 && s.phaseIndex != seenPhase &&
            s.state != JobState.FINISHED && s.state != JobState.ABORTED) {
            // Série en cours : à chaque nouveau réglage, l'écran passe à l'étape correspondante.
            seenPhase = s.phaseIndex
            val current = launchedStep + s.phaseIndex.coerceIn(0, launchedCount - 1)
            for (k in launchedStep until current) done += k
            reviewed += current
            if (current != index) {
                go(current)
                return
            }
        }
        val m = recipe.steps.getOrNull(index)?.machine ?: return
        val mine = running && index >= launchedStep && index < launchedStep + launchedCount
        val ps = panelState
        if (m.program != StepProgram.TURBO && ::startBtn.isInitialized) {
            startBtn.visibility = if (mine && s.state != JobState.FINISHED && s.state != JobState.ABORTED) View.GONE else View.VISIBLE
            if (s.busy && !mine) Ui.setButtonEnabled(startBtn, false)
            else if (reviewBtn == null && m.violations().isEmpty()) Ui.setButtonEnabled(startBtn, !s.busy)
        }
        if (!mine && ps != null) {
            if (index in done) {
                ps.setTextColor(Ui.OK)
                ps.text = tr("Étape terminée")
            } else {
                ps.setTextColor(Ui.WARN)
                ps.text = panelInfo
            }
        }
        if (mine && ps != null) {
            ps.setTextColor(Ui.TEXT2)
            ps.text = when (s.state) {
                JobState.RUNNING, JobState.STARTING -> tr("En cours… reste %s", Ui.clock(s.remainingMs))
                JobState.STOPPING -> tr("Arrêt du moteur…")
                JobState.PAUSED -> tr("En pause")
                JobState.FINISHED -> tr("Terminé")
                JobState.ABORTED -> s.endReason
                null -> ""
            }
        }
        turboBtn?.let { t ->
            t.isEnabled = !s.busy || s.turboActive
            t.alpha = if (t.isEnabled) 1f else 0.35f
        }
    }

    private var welcomed = false

    override fun onShow() {
        if (!welcomed) {
            welcomed = true
            view.postDelayed({ Remarks.cookStart(a, recipe) }, 1500)
        }
    }

    override fun onEvent(e: MachineEvent) {
        if (e.type == EventType.JOB_FINISHED && launchedStep >= 0 && index >= launchedStep && index < launchedStep + launchedCount) {
            val finished = launchedStep + launchedCount - 1
            if (!(a.prefs.chainSteps && continues(finished + 1))) Remarks.stepDone(a, recipe, finished)
            for (k in launchedStep..finished) done += k
            launchedStep = -1
            launchedCount = 1
            if (index != finished) go(finished)
            // Étapes non réunissables en un programme (vapeur puis réglage…) : la suivante part seule.
            val chain = a.prefs.chainSteps && continues(finished + 1)
            view.postDelayed({
                if (index == finished) {
                    a.machine.clearFinished()
                    if (index < recipe.steps.size - 1) {
                        // Étape enchaînée : le réglage a été vu avec la première étape de la série.
                        if (chain) reviewed += finished + 1
                        go(finished + 1)
                        if (chain) launch()
                    }
                }
            }, if (chain) 400L else 1_500L)
        }
    }

    override fun onKnob(delta: Int): Boolean {
        go(index + delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        val m = recipe.steps.getOrNull(index)?.machine
        if (m == null) next() else if (m.program != StepProgram.TURBO && startBtn.isEnabled && startBtn.visibility == View.VISIBLE) launch()
        return true
    }
}
