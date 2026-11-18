package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.media.Links
import app.toque.media.MiniPlayer
import app.toque.media.ReceiverService
import app.toque.media.Received
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Médias : ce qui a été envoyé depuis le téléphone (lu dans le lecteur flottant), YouTube par
 * NewPipe, et comment appairer le téléphone.
 */
class MediaScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Médias")
    private lateinit var list: LinearLayout
    private lateinit var pairing: android.widget.TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(16))

        // Colonne gauche : envoyer depuis le téléphone, YouTube.
        val left = Ui.vertical(c)
        val send = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, Icon.Kind.LINK, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, tr("Envoyer depuis le téléphone"), 20f, Ui.TEXT, Ui.MEDIUM))
        send.addView(head, Ui.lp(b = 8))
        send.addView(Ui.text(c, tr("Dans n'importe quelle appli du téléphone : Partager › Gotuj z Kasią. Photos, vidéos, musique et liens YouTube s'affichent ici."),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        pairing = Ui.text(c, "", 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.2f) }
        send.addView(pairing, Ui.lp(t = 10))
        left.addView(send, Ui.lp(b = 14))

        val yt = Ui.card(c, 24f)
        val head2 = Ui.horizontal(c)
        head2.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head2.addView(Ui.text(c, "YouTube", 20f, Ui.TEXT, Ui.MEDIUM))
        yt.addView(head2, Ui.lp(b = 8))
        yt.addView(Ui.text(c, tr("Vidéos de cuisine et abonnements, par NewPipe."), 15f, Ui.TEXT2))
        yt.addView(Ui.button(c, tr("Ouvrir NewPipe"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 54) { openNewPipe() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        left.addView(yt, Ui.lp(b = 14))

        val sp = Ui.card(c, 24f)
        val head3 = Ui.horizontal(c)
        head3.addView(Icon(c, Icon.Kind.PLAY, 0xFF1DB954.toInt()), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head3.addView(Ui.text(c, "Spotify", 20f, Ui.TEXT, Ui.MEDIUM))
        sp.addView(head3, Ui.lp(b = 8))
        sp.addView(Ui.text(c, tr("Le robot est une enceinte Spotify : choisissez « %s » dans l'appli Spotify du téléphone.", a.prefs.spotifyName),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        sp.addView(Ui.button(c, tr("Ouvrir Spotify"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 54) { a.push(SpotifyScreen(a)) }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        left.addView(sp)
        root.addView(Ui.scroll(c, left), LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(18) })

        // Colonne droite : fichiers reçus.
        val right = Ui.vertical(c)
        val top = Ui.horizontal(c)
        top.addView(Ui.text(c, tr("Reçus récemment"), 20f, Ui.TEXT, Ui.MEDIUM), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(Ui.button(c, tr("Tout effacer"), Ui.Style.GHOST, height = 46) { clearAll() })
        right.addView(top, Ui.lp(b = 8))
        list = Ui.vertical(c)
        right.addView(Ui.scroll(c, list), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun openNewPipe() {
        Thread {
            Links.prepare(a)
            view.post { if (!Links.openNewPipe(a)) a.toast(tr("NewPipe n'est pas installé"), Ui.WARN) }
        }.start()
    }

    private fun refresh() {
        val ip = Received.localIp()
        pairing.text = if (!a.prefs.receiverEnabled) tr("Réception désactivée (Réglages › Réception).")
        else tr("Code d'appairage : %s", a.prefs.receiverCode) + "\n" +
            tr("Adresse du robot : %s", if (ip.isEmpty()) tr("pas de Wi-Fi") else "$ip:${ReceiverService.PORT}")
        list.removeAllViews()
        val files = Received.list(a)
        if (files.isEmpty()) {
            list.addView(Ui.text(c, tr("Rien pour l'instant. Partagez une photo ou une vidéo depuis le téléphone."), 16f, Ui.TEXT3), Ui.lp(t = 20))
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE)
        for (f in files.take(60)) list.addView(row(f, fmt.format(Date(f.lastModified()))), Ui.lp(b = 8))
    }

    private fun row(f: File, date: String): View {
        val kind = Received.kindOf(f.name)
        val r = Ui.horizontal(c)
        r.setPadding(Ui.u(16), Ui.u(12), Ui.u(8), Ui.u(12))
        r.background = Ui.pressable(Ui.round(Ui.SURFACE, 18f), 18f)
        val icon = when (kind) {
            Received.Kind.VIDEO, Received.Kind.AUDIO -> Icon.Kind.PLAY
            Received.Kind.IMAGE -> Icon.Kind.STAR
            Received.Kind.OTHER -> Icon.Kind.INFO
        }
        r.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(12) })
        val texts = Ui.vertical(c)
        texts.addView(Ui.ellipsize(Ui.text(c, f.name, 17f, Ui.TEXT)))
        texts.addView(Ui.text(c, "$date · ${Received.size(f.length())}", 14f, Ui.TEXT3))
        r.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val del = Icon(c, Icon.Kind.CLOSE, Ui.TEXT3)
        del.setPadding(Ui.u(12), Ui.u(12), Ui.u(12), Ui.u(12))
        del.setOnClickListener { f.delete(); refresh() }
        r.addView(del, LinearLayout.LayoutParams(Ui.u(46), Ui.u(46)))
        r.isClickable = true
        r.setOnClickListener {
            if (kind == Received.Kind.OTHER) a.toast(tr("Ce type de fichier ne s'ouvre pas sur le robot"), Ui.WARN)
            else MiniPlayer.play(a, f.absolutePath, kind, f.name)
        }
        return r
    }

    private fun clearAll() {
        a.confirm(tr("Tout effacer"), tr("Supprimer tous les fichiers reçus sur le robot ?"), tr("Effacer"), danger = true) {
            MiniPlayer.stop(a)
            Received.list(a).forEach { it.delete() }
            refresh()
        }
    }

    override fun onShow() = refresh()
}
