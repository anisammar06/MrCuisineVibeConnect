package app.toque.send

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Réglage unique : trouver le robot sur le Wi-Fi et saisir le code affiché dans ses réglages. */
class SetupActivity : Activity() {
    private lateinit var robot: Robot
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        robot = Robot(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4) }

        col.addView(label("Envoyer vers Gotuj z Kasią", 22f))
        col.addView(label("1. Le téléphone et le robot doivent être sur le même Wi-Fi.\n" +
            "2. Sur le robot : Réglages › Réception depuis le téléphone affiche le code et l'adresse.\n" +
            "3. Ensuite, dans n'importe quelle appli : Partager › Gotuj z Kasią."))

        col.addView(label("Adresse du robot"))
        host = EditText(this).apply { setText(robot.host); hint = "ex. 192.168.0.168"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        col.addView(host)
        col.addView(Button(this).apply { text = "Rechercher le robot sur le Wi-Fi"; setOnClickListener { discover() } })

        col.addView(label("Code d'appairage (6 chiffres)"))
        code = EditText(this).apply { setText(robot.code); hint = "000000"; inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(code)

        col.addView(Button(this).apply { text = "Enregistrer et tester"; setOnClickListener { saveAndTest() } })
        status = label("").apply { gravity = Gravity.START }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        if (robot.host.isEmpty()) discover()
    }

    private fun discover() {
        status.text = "Recherche du robot…"
        robot.discover { h, p ->
            if (h == null) status.text = "Robot non trouvé automatiquement : saisissez son adresse (affichée dans ses réglages)."
            else {
                host.setText(h)
                robot.port = p
                status.text = "Robot trouvé : $h"
            }
        }
    }

    private fun saveAndTest() {
        robot.host = host.text.toString()
        robot.code = code.text.toString()
        if (!robot.configured) {
            status.text = "Adresse et code à 6 chiffres obligatoires."
            return
        }
        status.text = "Test…"
        Thread {
            val r = robot.check()
            runOnUiThread { status.text = if (r.ok) "C'est prêt : partagez depuis n'importe quelle appli." else r.message }
        }.start()
    }
}
