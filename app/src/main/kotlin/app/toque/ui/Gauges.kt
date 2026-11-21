package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
    )
}

private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

/**
 * Jauge de pesée : anneau en dégradé qui se remplit en douceur, halo lumineux, reflet en relief,
 * poids au centre. Bleu → vert à l'objectif, orange au-delà ; pulsation quand c'est bon.
 */
class WeighGauge(c: Context) : View(c) {
    /** Poids mesuré (g). */
    var grams = 0
        set(v) { field = v; invalidate() }
    /** Objectif (g) ; 0 : balance libre. */
    var target = 0
        set(v) { field = v; invalidate() }
    var connected = true
        set(v) { field = v; invalidate() }

    private var shown = 0f
    private var pulse = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.TEXT; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.TEXT2; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    private val m = Matrix()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val stroke = r * 0.13f
        val ringR = r - stroke * 0.9f
        // Valeur affichée qui rattrape la mesure en douceur.
        val goal = grams.toFloat()
        shown += (goal - shown) * 0.18f
        if (abs(goal - shown) < 0.5f) shown = goal
        val frac = if (target > 0) shown / target else (shown / 1000f).coerceIn(0f, 1f)
        val reached = target > 0 && grams >= target * 0.97f && grams <= target * 1.05f
        val over = target > 0 && grams > target * 1.05f
        val accent = when {
            over -> 0xFFFF7A3D.toInt()
            reached -> 0xFF3DDC84.toInt()
            else -> Ui.COOL
        }
        pulse = if (reached) (pulse + 0.06f) % 6.2832f else 0f
        val p = if (reached) 0.5f + 0.5f * sin(pulse) else 0f

        // Halo derrière l'anneau.
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(accent, (50 + 60 * p).toInt()), alpha(accent, 18), Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.82f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, glow)
        // Disque central en relief (lumière venant du haut).
        disc.shader = LinearGradient(cx, cy - ringR, cx, cy + ringR, 0xFF2A2F37.toInt(), 0xFF111317.toInt(), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, ringR - stroke * 0.7f, disc)
        disc.shader = RadialGradient(cx, cy - ringR * 0.55f, ringR * 0.9f, intArrayOf(0x22FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, ringR - stroke * 0.7f, disc)
        // Graduations.
        tick.strokeWidth = stroke * 0.12f
        for (i in 0 until 40) {
            val a = Math.toRadians((135 + i * 270.0 / 39)).toFloat()
            val lit = i / 39f <= frac.coerceIn(0f, 1f)
            tick.color = if (lit) alpha(accent, 150) else 0x22FFFFFF
            val r1 = ringR - stroke * 1.05f
            val r2 = r1 - stroke * (if (i % 13 == 0) 0.55f else 0.3f)
            cv.drawLine(cx + r1 * cos(a), cy + r1 * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), tick)
        }
        // Piste puis arc en dégradé.
        box.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        track.strokeWidth = stroke
        track.color = 0xFF22262D.toInt()
        cv.drawArc(box, 135f, 270f, false, track)
        val sweep = 270f * frac.coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            val g = SweepGradient(cx, cy, intArrayOf(mix(accent, 0xFF6A5CFF.toInt(), 0.55f), accent, mix(accent, Color.WHITE, 0.35f)),
                floatArrayOf(0f, 0.6f, 1f))
            m.setRotate(135f, cx, cy)
            g.setLocalMatrix(m)
            ring.shader = g
            ring.strokeWidth = stroke * (1f + 0.12f * p)
            cv.drawArc(box, 135f, sweep, false, ring)
            // Point lumineux en tête d'arc.
            val a = Math.toRadians((135 + sweep).toDouble()).toFloat()
            glow.shader = RadialGradient(cx + ringR * cos(a), cy + ringR * sin(a), stroke * 1.4f,
                intArrayOf(0xCCFFFFFF.toInt(), alpha(accent, 90), Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(cx + ringR * cos(a), cy + ringR * sin(a), stroke * 1.4f, glow)
        }
        // Textes.
        big.textSize = r * 0.42f
        small.textSize = r * 0.14f
        val txt = if (connected) "${shown.toInt()}" else "—"
        cv.drawText(txt, cx, cy + big.textSize * 0.28f, big)
        small.color = Ui.TEXT2
        cv.drawText("g", cx, cy + big.textSize * 0.28f + small.textSize * 1.3f, small)
        if (target > 0 && connected) {
            val left = target - grams
            small.color = if (over) 0xFFFF7A3D.toInt() else if (reached) 0xFF3DDC84.toInt() else Ui.TEXT2
            val sub = when {
                reached -> "✓ $target g"
                over -> "+${-left} g"
                else -> "− $left g"
            }
            cv.drawText(sub, cx, cy - big.textSize * 0.62f, small)
        }
        if (abs(goal - shown) > 0.5f || reached) postInvalidateOnAnimation()
    }
}

/**
 * Animation du moteur : lames qui tournent à la vitesse demandée (sens inverse compris), halo coloré
 * par la température, anneau qui « respire » pendant la chauffe.
 */
class MotorView(c: Context) : View(c) {
    var speed = 0
    var turbo = false
    var reverse = false
    var tempC = 0
    var heating = false
    var running = false
        set(v) {
            val was = field
            field = v
            if (v && !was) postInvalidateOnAnimation() else invalidate()
        }

    private var angle = 0f
    private var phase = 0f
    private var last = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun tempColor(t: Int): Int = when {
        t >= 100 -> mix(0xFFFF8A3D.toInt(), 0xFFFF3D3D.toInt(), (t - 100) / 30f)
        t >= 50 -> mix(0xFFFFC14D.toInt(), 0xFFFF8A3D.toInt(), (t - 50) / 50f)
        t >= 30 -> mix(Ui.COOL, 0xFFFFC14D.toInt(), (t - 30) / 20f)
        else -> Ui.COOL
    }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime() / 1_000_000L
        val dt = if (last == 0L) 0f else ((now - last).coerceAtMost(100)) / 1000f
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val col = tempColor(tempC)
        // Tours par seconde affichés (stylisés, pas la vraie vitesse).
        val rps = if (!running) 0f else if (turbo) 6f else 0.15f + speed * 0.45f
        angle = (angle + (if (reverse) -1 else 1) * rps * 360f * dt) % 360f
        phase = (phase + dt * 2.2f) % 6.2832f
        val breathe = if (heating && running) 0.5f + 0.5f * sin(phase) else 0f

        p.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(col, if (running) (70 + 70 * breathe).toInt() else 25), alpha(col, 15), Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p)
        // Cuve vue de dessus, en relief.
        p.shader = LinearGradient(cx, cy - r * 0.8f, cx, cy + r * 0.8f, 0xFF2C3139.toInt(), 0xFF0F1114.toInt(), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.8f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.05f
        p.color = alpha(col, if (running) 200 else 70)
        cv.drawCircle(cx, cy, r * 0.8f, p)
        p.style = Paint.Style.FILL
        // Lames (4), avec traînée quand ça tourne vite.
        val trails = if (running && rps > 1.5f) 3 else 1
        for (tIdx in trails - 1 downTo 0) {
            val a0 = angle - (if (reverse) -1 else 1) * tIdx * 9f
            val al = if (tIdx == 0) 255 else 60 / tIdx
            for (k in 0 until 4) {
                val a = Math.toRadians((a0 + k * 90).toDouble()).toFloat()
                val len = r * (if (k % 2 == 0) 0.66f else 0.52f)
                val wdt = r * 0.1f
                path.reset()
                path.moveTo(cx + wdt * cos(a + 1.57f), cy + wdt * sin(a + 1.57f))
                path.quadTo(cx + len * 0.6f * cos(a + 0.35f), cy + len * 0.6f * sin(a + 0.35f), cx + len * cos(a), cy + len * sin(a))
                path.quadTo(cx + len * 0.5f * cos(a - 0.2f), cy + len * 0.5f * sin(a - 0.2f), cx + wdt * cos(a - 1.57f), cy + wdt * sin(a - 1.57f))
                path.close()
                blade.shader = LinearGradient(cx, cy, cx + len * cos(a), cy + len * sin(a), alpha(0xFFE8ECF2.toInt(), al), alpha(0xFF8A93A3.toInt(), al), Shader.TileMode.CLAMP)
                cv.drawPath(path, blade)
            }
        }
        // Moyeu brillant.
        p.shader = RadialGradient(cx - r * 0.04f, cy - r * 0.05f, r * 0.16f, intArrayOf(Color.WHITE, 0xFF8A93A3.toInt(), 0xFF3A404A.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.14f, p)
        p.shader = null
        if (running) postInvalidateOnAnimation() else last = 0L
    }
}
