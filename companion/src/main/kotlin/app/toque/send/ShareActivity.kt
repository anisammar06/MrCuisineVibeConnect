package app.toque.send

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Cible du menu Partager : envoie le lien ou les fichiers au robot, avec la progression. */
class ShareActivity : Activity() {
    private lateinit var robot: Robot
    private lateinit var text: TextView
    private lateinit var bar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        robot = Robot(this)
        if (!robot.configured) {
            Toast.makeText(this, "Réglez d'abord le robot (adresse et code)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            minimumWidth = (300 * resources.displayMetrics.density).toInt()
        }
        text = TextView(this).apply { textSize = 17f; this.text = "Envoi vers Gotuj z Kasią…"; gravity = Gravity.START }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(text)
        col.addView(bar)
        setContentView(col)
        setFinishOnTouchOutside(false)

        val i = intent
        val uris = streams(i)
        val shared = i.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uris.isNotEmpty() -> warnThenSend(uris)
            !shared.isNullOrBlank() -> sendLink(shared)
            else -> done(Robot.Result(false, "Rien à envoyer"))
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    private fun sendLink(t: String) {
        bar.isIndeterminate = true
        Thread {
            val r = robot.sendLink(t)
            runOnUiThread { done(r) }
        }.start()
    }

    /** Les vidéos 4K ou HEVC (format courant des téléphones récents) ne passent pas sur la puce du robot. */
    private fun heavyVideo(uri: Uri): Boolean {
        if (contentResolver.getType(uri)?.startsWith("video/") != true) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(this, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.any { f ->
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("video/") && (mime.contains("hevc") || mime.contains("av01") || mime.contains("x-vnd.on2.vp9") ||
                    (f.containsKey(MediaFormat.KEY_HEIGHT) && minOf(f.getInteger(MediaFormat.KEY_HEIGHT), f.getInteger(MediaFormat.KEY_WIDTH)) > 1080))
            }
        } catch (_: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    private fun warnThenSend(uris: List<Uri>) {
        Thread {
            val heavy = uris.any { heavyVideo(it) }
            runOnUiThread {
                if (!heavy) sendFiles(uris)
                else AlertDialog.Builder(this)
                    .setTitle("Vidéo trop lourde pour le robot ?")
                    .setMessage("Cette vidéo est en 4K ou en HEVC : le robot risque de ne pas pouvoir la lire. " +
                        "Astuce : filmez en 1080p (Appareil photo › Réglages vidéo) pour les vidéos à regarder sur le robot.")
                    .setPositiveButton("Envoyer quand même") { _, _ -> sendFiles(uris) }
                    .setNegativeButton("Annuler") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }.start()
    }

    private fun sendFiles(uris: List<Uri>) {
        Thread {
            var last = Robot.Result(false, "Rien à envoyer")
            for ((n, u) in uris.withIndex()) {
                runOnUiThread { text.text = if (uris.size > 1) "Envoi ${n + 1} / ${uris.size}…" else "Envoi vers Gotuj z Kasią…" }
                last = robot.sendFile(u) { pct -> runOnUiThread { bar.progress = pct } }
                if (!last.ok) break
            }
            runOnUiThread { done(last) }
        }.start()
    }

    private fun done(r: Robot.Result) {
        Toast.makeText(this, r.message, if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        finish()
    }
}
