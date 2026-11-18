package app.toque.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.kitchen.ShoppingItem
import app.toque.kitchen.Shopping
import app.toque.media.Received
import app.toque.media.ReceiverService
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Liste de courses : remplie depuis les recettes ou à la main ; sur le téléphone, une page web
 * (code QR) qui reste lisible au magasin, sans appli à installer.
 */
class ShoppingScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Liste de courses")
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var header: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView
    private var unsub: (() -> Unit)? = null

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- liste
        val left = Ui.vertical(c)
        val bar = Ui.horizontal(c)
        val field = Ui.horizontal(c)
        field.setPadding(Ui.u(20), 0, Ui.u(10), 0)
        field.background = Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f)
        input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(20f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Ajouter un article (ex. : lait, 6 œufs)")
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, _, _ -> addTyped(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(64), 1f))
        bar.addView(Ui.button(c, tr("Ajouter"), Ui.Style.PRIMARY, Icon.Kind.PLUS, height = 64) { addTyped() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        left.addView(bar)
        header = Ui.text(c, "", 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f }
        left.addView(header, Ui.lp(t = 16))
        list = Ui.vertical(c)
        left.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6) })
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.62f).apply { rightMargin = Ui.u(20) })

        // ---- téléphone et nettoyage
        val right = Ui.card(c, 24f)
        right.addView(Ui.text(c, tr("SUR LE TÉLÉPHONE"), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f })
        qr = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        right.addView(qr, Ui.lp(Ui.MATCH, Ui.u(190), t = 10))
        right.addView(Ui.text(c, tr("Scannez avec l'appareil photo du téléphone (même Wi-Fi). La page reste affichée au magasin."), 15f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.12f); gravity = Gravity.CENTER }, Ui.lp(t = 8))
        url = Ui.text(c, "", 14f, Ui.TEXT3).apply { gravity = Gravity.CENTER }
        right.addView(url, Ui.lp(t = 4))
        right.addView(Ui.spacer(c), LinearLayout.LayoutParams(1, 0, 1f))
        right.addView(Ui.button(c, tr("Retirer les articles cochés"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 56, textSize = 17f) {
            Shopping.edit(c) { it.clearDone() }
        }, Ui.lp(t = 10))
        right.addView(Ui.button(c, tr("Tout effacer"), Ui.Style.GHOST, height = 52, textSize = 16f) {
            a.confirm(tr("Tout effacer ?"), tr("La liste de courses sera vidée."), tr("Tout effacer"), danger = true) {
                Shopping.edit(c) { it.clear() }
            }
        }, Ui.lp(t = 8))
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.38f))
        return root
    }

    private fun addTyped() {
        val t = input.text.toString()
        if (t.isBlank()) return
        Shopping.edit(c) { it.addText(t) }
        input.setText("")
    }

    private fun render() {
        val l = Shopping.get(c)
        val items = l.items
        header.text = if (items.isEmpty()) tr("LISTE VIDE") else tr("À ACHETER : %d", l.remaining)
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(Ui.text(c, tr("Ajoutez des articles ici, ou ouvrez une recette et touchez « Courses »."), 17f, Ui.TEXT3)
                .apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 8))
        }
        for (i in items) list.addView(row(i), Ui.lp(t = 8))
    }

    private fun row(i: ShoppingItem): View {
        val r = Ui.horizontal(c)
        r.setPadding(Ui.u(16), Ui.u(10), Ui.u(8), Ui.u(10))
        r.background = Ui.pressable(Ui.round(if (i.done) Ui.BG else Ui.SURFACE, 18f), 18f)
        val box = Icon(c, if (i.done) Icon.Kind.CHECK else Icon.Kind.PLUS, if (i.done) Ui.OK else Ui.TEXT3)
        box.background = Ui.round(0, 16f, if (i.done) Ui.OK else Ui.STROKE, 2f)
        box.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(6))
        if (!i.done) box.alpha = 0f
        val boxWrap = android.widget.FrameLayout(c)
        boxWrap.background = Ui.round(0, 16f, if (i.done) Ui.OK else Ui.STROKE, 2f)
        boxWrap.addView(box, Ui.flp())
        r.addView(boxWrap, LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        val name = Ui.text(c, i.display, 19f, if (i.done) Ui.TEXT3 else Ui.TEXT)
        if (i.done) name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(name)
        if (i.recipes.isNotEmpty()) col.addView(Ui.ellipsize(Ui.text(c, i.recipes.joinToString(" · "), 14f, Ui.TEXT3)))
        r.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val del = Icon(c, Icon.Kind.CLOSE, Ui.TEXT3)
        del.setPadding(Ui.u(12), Ui.u(12), Ui.u(12), Ui.u(12))
        del.setOnClickListener { Shopping.edit(c) { it.remove(i.id) } }
        r.addView(del, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
        r.isClickable = true
        r.setOnClickListener { Shopping.edit(c) { it.toggle(i.id) } }
        return r
    }

    private fun renderQr() {
        val ip = Received.localIp()
        if (ip.isEmpty() || !a.prefs.receiverEnabled) {
            qr.setImageDrawable(null)
            url.text = if (ip.isEmpty()) tr("Robot sans Wi-Fi") else tr("Activez « Réception depuis le téléphone » dans les réglages.")
            return
        }
        val link = "http://$ip:${ReceiverService.PORT}/courses?k=${a.prefs.receiverCode}"
        url.text = "http://$ip:${ReceiverService.PORT}/courses"
        qr.setImageBitmap(qrBitmap(link, Ui.u(190)))
    }

    override fun onShow() {
        render()
        renderQr()
        unsub = Shopping.onChange { render() }
    }

    override fun onHide() {
        unsub?.invoke()
        unsub = null
    }

    companion object {
        fun qrBitmap(text: String, size: Int): Bitmap? = try {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }
}
