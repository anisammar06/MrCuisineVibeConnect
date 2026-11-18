package app.toque.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.toque.Prefs
import app.toque.ui.ReturnBubble

/** Ouverture d'un lien reçu ou choisi : YouTube dans NewPipe, média dans le lecteur, sinon le navigateur. */
object Links {
    const val NEWPIPE = "org.schabi.newpipe"
    private val URL = Regex("""https?://[^\s<>"]+""")

    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')

    fun isYoutube(url: String): Boolean {
        val host = try {
            Uri.parse(url).host.orEmpty().lowercase()
        } catch (_: Exception) {
            ""
        }
        return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
    }

    fun hasNewPipe(c: Context): Boolean = c.packageManager.getLaunchIntentForPackage(NEWPIPE) != null

    /**
     * Prépare le bouton « Retour à Toque » avant d'ouvrir une autre appli (droit d'affichage
     * par-dessus accordé par root). À appeler hors du fil principal.
     */
    fun prepare(c: Context) {
        ReturnBubble.ensurePermission(c)
    }

    /** Sur le fil principal, après [prepare]. */
    fun open(c: Context, url: String) {
        val kind = Received.kindOf(url)
        if (kind != Received.Kind.OTHER) {
            MiniPlayer.play(c, url, kind, Uri.parse(url).lastPathSegment ?: url)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isYoutube(url) && hasNewPipe(c)) intent.setPackage(NEWPIPE)
        external(c, intent)
    }

    /** Ouvre NewPipe sur son écran d'accueil (abonnements). */
    fun openNewPipe(c: Context): Boolean {
        val launch = c.packageManager.getLaunchIntentForPackage(NEWPIPE) ?: return false
        external(c, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun external(c: Context, intent: Intent) {
        val prefs = Prefs(c)
        if (ReturnBubble.canShow(c)) {
            prefs.returnBubble = true
            ReturnBubble.start(c)
        }
        try {
            c.startActivity(intent)
        } catch (_: Exception) {
            prefs.returnBubble = false
            ReturnBubble.stop(c)
        }
    }
}
