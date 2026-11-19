package app.toque.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.ui.ReturnBubble
import app.toque.ui.Ui

/** Carte flottante en bas de l'écran : ce qui a été compris et la réponse de l'assistant. */
internal object Bubble {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var you: TextView? = null
    private var answer: TextView? = null
    private val hide = Runnable { remove() }

    fun show(c: Context, question: String, reply: String) = main.post {
        main.removeCallbacks(hide)
        if (root == null) create(c.applicationContext)
        you?.text = if (question.isEmpty()) "" else "« $question »"
        you?.visibility = if (question.isEmpty()) View.GONE else View.VISIBLE
        answer?.text = reply
    }

    fun hideLater(ms: Long) = main.post {
        main.removeCallbacks(hide)
        main.postDelayed(hide, ms)
    }

    private fun create(c: Context) {
        if (!ReturnBubble.canShow(c)) return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = c.resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        val v = Ui.vertical(c)
        v.background = Ui.round(0xF2181410.toInt(), 22f, Ui.ACCENT, 2f)
        v.elevation = Ui.uf(12f)
        v.setPadding(Ui.u(22), Ui.u(14), Ui.u(22), Ui.u(16))
        val q = Ui.text(c, "", 16f, Ui.TEXT2)
        val r = Ui.text(c, "", 19f, Ui.TEXT).apply { setLineSpacing(0f, 1.12f); maxLines = 7 }
        v.addView(q)
        v.addView(r, Ui.lp(t = 4))
        v.setOnClickListener { remove() }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(minOf(Ui.u(760), dm.widthPixels - Ui.u(40)), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = Ui.u(24)
        try {
            wm.addView(v, lp)
            root = v
            you = q
            answer = r
        } catch (_: RuntimeException) {
        }
    }

    private fun remove() {
        val v = root ?: return
        try {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: RuntimeException) {
        }
        root = null
        you = null
        answer = null
    }
}
