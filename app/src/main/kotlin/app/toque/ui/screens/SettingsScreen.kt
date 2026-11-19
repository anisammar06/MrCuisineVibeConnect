package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.recipes.OnlineSync
import app.toque.recipes.SyncProgress
import app.toque.recipes.CookidooClient
import app.toque.recipes.CookidooProgress
import app.toque.recipes.CookidooSync
import app.toque.media.Received
import app.toque.media.ReceiverService
import app.toque.media.SpotifyService
import app.toque.media.MicTest
import app.toque.voice.VoiceModels
import app.toque.voice.VoiceService
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.update.Updater

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglages")
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var tmText: TextView
    private lateinit var voiceLangBox: LinearLayout
    private lateinit var voiceText: TextView
    private lateinit var voiceBtn: LinearLayout
    private var voiceBusy = false
    private val voiceTick = object : Runnable {
        override fun run() {
            if (a.prefs.voiceEnabled && !voiceBusy) renderVoice()
            view.postDelayed(this, 3000)
        }
    }
    private lateinit var tmLogin: LinearLayout
    private lateinit var tmActions: LinearLayout
    private var unsubTm: (() -> Unit)? = null
    private lateinit var recvText: TextView
    private var unsubProgress: (() -> Unit)? = null
    private lateinit var bowlText: TextView
    private lateinit var linkText: TextView
    private lateinit var timeText: TextView
    private var settingTime = false
    private var mode = a.prefs.mode
    private var syncing = false

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updText: TextView
    private lateinit var updButtons: LinearLayout
    private var upd = Upd.IDLE
    private var release: Release? = null
    private var updMessage = ""
    private var updPercent = 0

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val columns = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        val right = Ui.vertical(c)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(columns)

        // ---- Machine
        val machine = section(left, tr("Machine"), Icon.Kind.POT)
        modeRow = Ui.horizontal(c)
        machine.addView(modeRow, Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(58), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(58)))
        machine.addView(twinBox, Ui.lp(t = 12))
        linkText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        machine.addView(linkText, Ui.lp(t = 12))
        machine.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { applyMode() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        renderModes()

        // ---- Sécurité
        val safety = section(left, tr("Sécurité"), Icon.Kind.WARNING)
        safety.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(bowlText, Ui.lp(t = 6))
        safety.addView(toggle(tr("Montée progressive de la vitesse si le contenu est chaud"), a.prefs.hotRamp) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        safety.addView(toggle(tr("Enchaîner les étapes machine des recettes"), a.prefs.chainSteps) { a.prefs.chainSteps = it }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("Les réglages qui se suivent sans geste à faire entre eux partent ensemble d'un seul appui sur Lancer : la lame passe directement à la vitesse et à la température suivantes."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        safety.addView(toggle(tr("Sons"), a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 10))

        // ---- Langue (titre bilingue : trouvable quelle que soit la langue affichée)
        val language = section(right, "Langue · Language", Icon.Kind.INFO)
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        language.addView(dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            a.applyLanguage()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))

        // ---- Date et heure
        val time = section(right, tr("Date et heure"), Icon.Kind.CLOCK)
        val zones = listOf(ZoneLookup.AUTO to tr("Automatique (d'après la connexion Internet)")) +
            (ZoneLookup.COMMON + listOf(TimeSync.current(), a.prefs.timeZone))
                .filter { it != ZoneLookup.AUTO }.distinct().map { it to it.replace('_', ' ') }
        time.addView(dropdown(zones, a.prefs.timeZone) { z ->
            a.prefs.timeZone = z
            setClock()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))
        timeText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        time.addView(timeText, Ui.lp(t = 10))
        time.addView(Ui.button(c, tr("Mettre à l'heure"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) { setClock() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        refreshTime()

        // ---- Recettes Lidl
        val lidl = section(right, tr("Recettes Lidl"), Icon.Kind.SYNC)
        lidl.addView(Ui.text(c, tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte)."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText, Ui.lp(t = 10))
        val b1 = Ui.horizontal(c)
        b1.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(online = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        lidl.addView(b1, Ui.lp(t = 12))
        lidl.addView(Ui.text(c, tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site)."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))
        val b2 = Ui.horizontal(c)
        b2.addView(Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, height = 52) { sync(online = false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        b2.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52) { a.openVendorApp() })
        lidl.addView(b2, Ui.lp(t = 6))
        lidl.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))
        lidl.addView(toggle(tr("Recettes en polonais aussi (gardées à côté du français)"), a.prefs.syncPolish) { on ->
            a.prefs.syncPolish = on
            refreshSync()
        }, Ui.lp(t = 10))
        lidl.addView(Ui.text(c, tr("Sur l'écran Recettes, le bouton FR | PL passe d'un catalogue à l'autre. La première synchro polonaise prend environ 15 minutes."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))

        // ---- Recettes Thermomix (Cookidoo)
        val tm = section(right, tr("Recettes Thermomix"), Icon.Kind.BOOK)
        tm.addView(Ui.text(c, tr("Votre compte Cookidoo (abonnement requis). Les recettes sont converties pour le robot : vitesse, température, temps, sens inverse, vapeur, pétrin et pesées."),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        tmText = Ui.text(c, "", 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        tm.addView(tmText, Ui.lp(t = 10))
        tmLogin = Ui.vertical(c)
        val email = field(a.prefs.cookidooEmail, tr("E-mail Cookidoo")).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        tmLogin.addView(email, Ui.lp(Ui.MATCH, Ui.u(56), t = 10))
        val password = field("", tr("Mot de passe (jamais enregistré)")).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tmLogin.addView(password, Ui.lp(Ui.MATCH, Ui.u(56), t = 8))
        tmLogin.addView(Ui.button(c, tr("Se connecter"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 56) {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) return@button
            a.hideKeyboard()
            tmText.text = tr("Connexion à Cookidoo…")
            Thread {
                val err = try {
                    CookidooClient(a).login(e, p)
                    null
                } catch (x: Exception) {
                    x.message ?: x.toString()
                }
                view.post {
                    password.setText("")
                    if (err != null) tmText.text = tr("Connexion impossible : %s", err) else {
                        renderCookidoo()
                        a.toast(tr("Compte Cookidoo connecté"), Ui.OK)
                    }
                }
            }.start()
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        tm.addView(tmLogin)
        tmActions = Ui.vertical(c)
        val tb = Ui.horizontal(c)
        tb.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 56) {
            Thread { CookidooSync.run(a.applicationContext) }.start()
        }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        tb.addView(Ui.button(c, tr("Se déconnecter"), Ui.Style.GHOST, height = 52) {
            CookidooClient(a).logout()
            renderCookidoo()
        })
        tmActions.addView(tb, Ui.lp(t = 10))
        tmActions.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.cookidooAuto) { a.prefs.cookidooAuto = it }, Ui.lp(t = 10))
        tmActions.addView(toggle(tr("Recettes en polonais aussi"), a.prefs.cookidooPolish) { a.prefs.cookidooPolish = it }, Ui.lp(t = 10))
        tmActions.addView(Ui.text(c, tr("Nouvelles recettes ajoutées à chaque synchro (par langue)"), 16f, Ui.TEXT2), Ui.lp(t = 12))
        val batches = listOf(0, 50, 150, 300).map { it.toString() to if (it == 0) tr("Aucune (seulement mes listes)") else tr("%d recettes", it) }
        tmActions.addView(dropdown(batches, a.prefs.cookidooBatch.toString()) { v -> a.prefs.cookidooBatch = v.toInt() },
            Ui.lp(Ui.MATCH, Ui.u(58), t = 6))
        tmActions.addView(Ui.text(c, tr("Les recettes des listes de votre compte (créées sur le téléphone dans l'appli Thermomix) sont importées en premier et marquées en favori. Le reste du catalogue arrive petit à petit, à un rythme qui ne fait pas bloquer le compte."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
        tm.addView(tmActions)
        renderCookidoo()

        // ---- Spotify
        val sp = section(right, tr("Spotify"), Icon.Kind.PLAY)
        sp.addView(Ui.text(c, tr("Le robot apparaît comme enceinte « %s » dans l'appli Spotify (compte Premium). Premier appairage : Médias › Spotify.", a.prefs.spotifyName),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        sp.addView(toggle(tr("Enceinte Spotify sur le robot"), a.prefs.spotifyEnabled) { on ->
            a.prefs.spotifyEnabled = on
            if (on) SpotifyService.start(a) else SpotifyService.stop(a)
        }, Ui.lp(t = 10))
        sp.addView(Ui.button(c, tr("Délier le compte Spotify"), Ui.Style.GHOST, height = 52) {
            a.confirm(tr("Délier le compte Spotify ?"), tr("Il faudra refaire l'appairage avec un code."), tr("Délier"), danger = true) {
                SpotifyService.unlink(a.applicationContext)
                a.toast(tr("Compte Spotify délié"), Ui.OK)
            }
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))

        // ---- Commande vocale
        val vo = section(right, tr("Commande vocale"), Icon.Kind.PLAY)
        vo.addView(Ui.text(c, tr("Hors ligne : rien n'est envoyé sur Internet. Dites « robot » puis la commande, par exemple « robot, minuteur pâtes huit minutes » ou « robot, étape suivante ». La voix peut mettre en pause ou arrêter une cuisson, jamais démarrer le moteur."),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        vo.addView(toggle(tr("Commande vocale activée"), a.prefs.voiceEnabled) { on ->
            a.prefs.voiceEnabled = on
            if (on) VoiceService.start(a.applicationContext) else VoiceService.stop(a.applicationContext)
            renderVoice()
        }, Ui.lp(t = 10))
        vo.addView(toggle(tr("Mot d'appel « robot » obligatoire"), a.prefs.voiceWake) { on ->
            a.prefs.voiceWake = on
            VoiceService.restart(a.applicationContext)
        }, Ui.lp(t = 10))
        vo.addView(Ui.text(c, tr("Sensibilité du micro"), 16f, Ui.TEXT2), Ui.lp(t = 12))
        val gains = listOf(4 to tr("Normale"), 8 to tr("Forte"), 16 to tr("Très forte"), 24 to tr("Maximale"))
        vo.addView(dropdown(gains.map { it.first.toString() to it.second }, a.prefs.voiceGain.toString()) { v ->
            a.prefs.voiceGain = v.toInt()
            VoiceService.restart(a.applicationContext)
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 6))
        vo.addView(Ui.text(c, tr("Langue écoutée (une à la fois)"), 16f, Ui.TEXT2), Ui.lp(t = 12))
        voiceLangBox = Ui.horizontal(c)
        vo.addView(voiceLangBox, Ui.lp(t = 6))
        voiceText = Ui.text(c, "", 15f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        vo.addView(voiceText, Ui.lp(t = 10))
        voiceBtn = Ui.button(c, tr("Télécharger la voix"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) { downloadVoice() }
        vo.addView(voiceBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        vo.addView(Ui.text(c, tr("Commandes : minuteur [nom] [durée] · arrête le minuteur · étape suivante / précédente · suivant · précédent · pause · stop · lecture · plus fort · moins fort.\nPolski : minutnik [nazwa] [czas] · wyłącz minutnik · następny krok · poprzedni krok · pauza · stop · graj · głośniej · ciszej."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.12f) }, Ui.lp(t = 10))
        renderVoice()

        // ---- Micro
        val mic = section(right, tr("Micro"), Icon.Kind.PLAY)
        mic.addView(Ui.text(c, tr("Touchez « Tester », puis parlez près du robot pendant 5 secondes : la jauge doit bouger."), 15f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) })
        val micBar = app.toque.ui.ProgressLine(c)
        mic.addView(micBar, Ui.lp(Ui.MATCH, Ui.u(10), t = 12))
        val micText = Ui.text(c, "", 16f, Ui.TEXT)
        mic.addView(micText, Ui.lp(t = 8))
        mic.addView(Ui.button(c, tr("Tester le micro"), Ui.Style.SECONDARY, Icon.Kind.PLAY, height = 52) {
            MicTest.run(a, { level -> view.post { micBar.progress = level } }) { msg -> view.post { micText.text = msg } }
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))

        // ---- Réception (Médias)
        val recv = section(left, tr("Réception depuis le téléphone"), Icon.Kind.LINK)
        recv.addView(Ui.text(c, tr("Appli « Gotuj Send » sur le téléphone, puis Partager › Gotuj z Kasią."),
            15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.1f) })
        recv.addView(toggle(tr("Recevoir les envois du téléphone"), a.prefs.receiverEnabled) { on ->
            a.prefs.receiverEnabled = on
            if (on) ReceiverService.start(a) else ReceiverService.stop(a)
            renderReceiver()
        }, Ui.lp(t = 10))
        recvText = Ui.text(c, "", 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.2f) }
        recv.addView(recvText, Ui.lp(t = 10))
        recv.addView(Ui.button(c, tr("Nouveau code"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) {
            a.prefs.newReceiverCode()
            renderReceiver()
            a.toast(tr("Nouveau code : à saisir à nouveau sur les téléphones"), Ui.OK)
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        recv.addView(Ui.text(c, tr("Place maximale pour les fichiers reçus"), 16f, Ui.TEXT2), Ui.lp(t = 12))
        val sizes = listOf(512, 1024, 2048, 4096).map { it.toString() to if (it < 1024) "$it Mo" else "${it / 1024} Go" }
        recv.addView(dropdown(sizes, a.prefs.receivedMaxMb.toString()) { v -> a.prefs.receivedMaxMb = v.toInt() },
            Ui.lp(Ui.MATCH, Ui.u(58), t = 6))
        renderReceiver()

        // ---- Mises à jour
        val updates = section(left, tr("Mises à jour"), Icon.Kind.INFO)
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updates.addView(updText)
        updButtons = Ui.horizontal(c)
        updates.addView(updButtons, Ui.lp(t = 12))
        Updater.onInstallError = { msg -> updateFailed(msg) }
        renderUpdate()

        // ---- Outils
        val tools = section(right, tr("Outils"), Icon.Kind.SETTINGS)
        tools.addView(link(tr("Diagnostic de la base moteur")) { a.push(DiagnosticsScreen(a)) })
        tools.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })
        tools.addView(link(tr("Journal")) { a.push(LogScreen(a)) })
        tools.addView(link(tr("Paramètres Android")) { a.openAndroidSettings() })
        tools.addView(link(tr("Revenir à l'appli d'origine")) { restoreVendor() })
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        tools.addView(Ui.text(c, tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", version), 14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))

        return Ui.scroll(c, col)
    }

    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind): LinearLayout {
        val card = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 10))
        parent.addView(card, Ui.lp(b = 16))
        return card
    }

    private fun field(value: String, hint: String) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        var state = initial
        val sw = Ui.text(c, "", 16f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        fun paint() {
            sw.text = if (state) tr("Oui") else tr("Non")
            sw.setTextColor(if (state) 0xFF1A0E05.toInt() else Ui.TEXT2)
            sw.background = if (state) Ui.round(Ui.ACCENT, 24f) else Ui.round(Ui.SURFACE3, 24f)
        }
        paint()
        sw.setOnClickListener { state = !state; paint(); onChange(state) }
        row.addView(sw, LinearLayout.LayoutParams(Ui.u(84), Ui.u(46)))
        return row
    }

    private fun link(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.u(14), 0, Ui.u(14))
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(0, 8f), 8f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Menu déroulant : la valeur choisie, et la liste qui s'ouvre en dessous. */
    private fun dropdown(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = Ui.horizontal(c)
        box.setPadding(Ui.u(16), 0, Ui.u(14), 0)
        box.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        val label = options.firstOrNull { it.first == selected }?.second ?: options.first().second
        box.addView(Ui.text(c, label, 19f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { rotation = 90f }, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)))
        box.isClickable = true
        box.setOnClickListener {
            val list = Ui.vertical(c)
            list.setPadding(0, Ui.u(6), 0, Ui.u(6))
            val scroll = ScrollView(c).apply {
                addView(list)
                background = Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f)
            }
            val height = if (options.size > 7) Ui.u(400) else ViewGroup.LayoutParams.WRAP_CONTENT
            val popup = PopupWindow(scroll, box.width, height, false)
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == selected) Ui.ACCENT else Ui.TEXT)
                row.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14))
                row.background = Ui.pressable(Ui.round(0, 0f), 0f)
                row.isClickable = true
                row.setOnClickListener {
                    popup.dismiss()
                    if (code != selected) onPick(code)
                }
                list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.isOutsideTouchable = true
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
        }
        return box
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> tr("Démo")
                Prefs.Mode.TWIN -> tr("Jumeau (PC)")
                Prefs.Mode.HARDWARE -> tr("Machine réelle")
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
        linkText.text = when (mode) {
            Prefs.Mode.DEMO -> tr("Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android.")
            Prefs.Mode.TWIN -> tr("Jumeau numérique du SDK lancé sur un PC (tools/twin.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1.")
            Prefs.Mode.HARDWARE -> tr("Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible).")
        }
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                tr("Piloter la vraie machine"),
                tr("Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur."),
                tr("Continuer"), danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.bootLaunch = true
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast(tr("Mode : %s", mode.label), Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast(tr("Vérifications…"))
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += tr("accès root refusé")
            val sync = LidlSync(a, a.prefs, a.app.recipesFr, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += tr("impossible de couper l'appli d'origine du moteur")
                sync.forceStop()
            }
            view.post {
                if (problems.isNotEmpty()) {
                    a.confirm(tr("Machine réelle indisponible"), problems.joinToString("\n") { "• $it" }, "OK", tr("Fermer")) {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            tr("Revenir à l'appli d'origine"),
            tr("Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Toque ne s'ouvrira plus au démarrage du robot."),
            tr("Revenir"),
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.prefs.bootLaunch = false
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipesFr, a.app.log)
                    sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    view.post {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast(tr("Appli d'origine introuvable"), Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(online: Boolean) {
        if (syncing) return
        if (!online && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        syncing = true
        syncText.text = tr("Synchronisation en cours… (quelques minutes)")
        Thread {
            val r = if (online) OnlineSync.syncAll(a, a.app) { msg -> view.post { syncText.text = msg } }
            else LidlSync(a, a.prefs, a.app.recipesFr, a.app.log).importNow { msg -> view.post { syncText.text = msg } }
            view.post {
                syncing = false
                refreshSync()
                a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
            }
        }.start()
    }

    private fun refreshTime(message: String? = null) {
        val zone = TimeSync.current()
        val mode = if (a.prefs.timeZone == ZoneLookup.AUTO) tr("automatique") else tr("choisi à la main")
        timeText.text = tr("Il est %s · fuseau %s (%s)", TimeSync.clock(), zone.replace('_', ' '), mode) +
            (message?.let { "\n" + it } ?: "")
    }

    private fun setClock() {
        if (settingTime) return
        settingTime = true
        timeText.text = tr("Réglage de l'heure…")
        Thread {
            val r = try {
                TimeSync(a, a.prefs, a.app.log).run()
            } catch (e: Exception) {
                TimeSync.Result(false, TimeSync.current(), e.message ?: e.toString())
            }
            a.runOnUiThread {
                settingTime = false
                refreshTime(if (r.ok) null else r.message)
                if (r.ok) a.toast(r.message, Ui.OK)
            }
        }.start()
    }

    private fun renderVoice() {
        voiceLangBox.removeAllViews()
        for ((code, label) in listOf("fr" to "Français", "pl" to "Polski")) {
            voiceLangBox.addView(Ui.toggleChip(c, label, a.prefs.voiceLang == code) {
                a.prefs.voiceLang = code
                VoiceService.restart(a.applicationContext)
                renderVoice()
            }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        val lang = a.prefs.voiceLang
        val ok = VoiceModels.installed(a, lang)
        if (!voiceBusy) {
            val st = if (a.prefs.voiceEnabled) VoiceModels.status(a) else null
            voiceText.text = if (ok) tr("Voix installée (%s).", lang.uppercase()) +
                (if (a.prefs.voiceEnabled) " " + tr("Le robot écoute.") else "") +
                (if (st != null) "\n" + tr("Niveau du micro : %d %%", st.first) +
                    (if (st.second.isEmpty()) "\n" + tr("Rien reconnu pour l'instant.") else "\n" + tr("Dernières phrases reconnues :") + "\n" + st.second.joinToString("\n")) else "")
            else tr("Voix %s à télécharger (environ 45 Mo, une seule fois).", lang.uppercase())
        }
        voiceBtn.visibility = if (ok || voiceBusy) View.GONE else View.VISIBLE
    }

    private fun downloadVoice() {
        if (voiceBusy) return
        voiceBusy = true
        val lang = a.prefs.voiceLang
        renderVoice()
        val app = a.applicationContext
        Thread {
            val err = try {
                VoiceModels.download(app, lang) { pct -> view.post { voiceText.text = tr("Téléchargement de la voix : %d %%", pct) } }
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            view.post {
                voiceBusy = false
                if (err != null) voiceText.text = tr("Téléchargement impossible : %s", err) else {
                    renderVoice()
                    VoiceService.start(app)
                }
                voiceBtn.visibility = if (VoiceModels.installed(a, lang)) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun renderCookidoo() {
        val client = CookidooClient(a)
        tmLogin.visibility = if (client.loggedIn) View.GONE else View.VISIBLE
        tmActions.visibility = if (client.loggedIn) View.VISIBLE else View.GONE
        tmText.text = when {
            CookidooProgress.running -> CookidooProgress.text
            client.loggedIn -> tr("Connecté : %s", a.prefs.cookidooEmail) + "\n" +
                tr("%d recettes en français · %d en polonais", a.app.tmFr.count, a.app.tmPl.count) +
                (if (a.prefs.cookidooResult.isNotEmpty()) "\n" + a.prefs.cookidooResult else "")
            else -> tr("Non connecté")
        }
    }

    private fun renderReceiver() {
        val ip = Received.localIp()
        recvText.text = if (!a.prefs.receiverEnabled) tr("Réception désactivée.")
        else tr("Code d'appairage : %s", a.prefs.receiverCode) + "\n" +
            tr("Adresse du robot : %s", if (ip.isEmpty()) tr("pas de Wi-Fi") else "$ip:${ReceiverService.PORT}")
    }

    private fun refreshSync() {
        val last = a.prefs.lastSyncResult
        syncText.text = (if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernières synchros :") + "\n" + last) +
            "\n" + tr("%d recettes en français", a.app.recipesFr.count) +
            (if (a.prefs.syncPolish || a.app.recipesPl.count > 0) " · " + tr("%d en polonais", a.app.recipesPl.count) else "")
    }

    // ------------------------------------------------------------------ mises à jour

    private fun renderUpdate() {
        val r = release
        val installed = tr("Version installée : %s", updater.installedVersion)
        updText.setTextColor(if (upd == Upd.ERROR) Ui.WARN else if (upd == Upd.AVAILABLE) Ui.TEXT else Ui.TEXT2)
        updText.text = when (upd) {
            Upd.IDLE -> installed + "\n" + tr("Les nouvelles versions sont publiées sur GitHub.")
            Upd.CHECKING -> installed + "\n" + tr("Recherche sur GitHub…")
            Upd.UP_TO_DATE -> installed + "\n" + tr("Toque est à jour.")
            Upd.AVAILABLE -> installed + "\n" + tr("Nouvelle version disponible : %s", r?.version) + notes(r)
            Upd.DOWNLOADING -> tr("Téléchargement de la version %s… %d %%", r?.version, updPercent)
            Upd.INSTALLING -> tr("Installation de la version %s… Toque va redémarrer.\n" +
                "Si Android demande une confirmation, touchez « Installer ».", r?.version)
            Upd.ERROR -> installed + "\n" + updMessage
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { checkUpdate() })
            Upd.AVAILABLE -> {
                updButtons.addView(Ui.button(c, tr("Installer la version %s", r?.version), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { installUpdate() },
                    Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                updButtons.addView(Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 58) { upd = Upd.IDLE; renderUpdate() })
            }
            else -> {}
        }
    }

    private fun notes(r: Release?): String {
        val text = r?.notes?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString("\n") ?: ""
        if (text.isEmpty()) return ""
        return "\n\n" + if (text.length > 280) text.substring(0, 280) + "…" else text
    }

    private fun checkUpdate() {
        if (upd == Upd.CHECKING || upd == Upd.DOWNLOADING || upd == Upd.INSTALLING) return
        upd = Upd.CHECKING
        renderUpdate()
        Thread {
            try {
                val r = updater.latest()
                view.post {
                    release = r
                    upd = if (updater.isNewer(r)) Upd.AVAILABLE else Upd.UP_TO_DATE
                    renderUpdate()
                    if (upd == Upd.AVAILABLE) installUpdate()
                }
            } catch (e: Updater.Failure) {
                view.post { upd = Upd.ERROR; updMessage = e.message ?: tr("Erreur"); renderUpdate() }
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
                view.post { upd = Upd.ERROR; updMessage = tr("Erreur inattendue : %s", e.message); renderUpdate() }
            }
        }.start()
    }

    /** Propose l'installation dans une fenêtre (toujours visible, même si la carte est hors écran). */
    private fun installUpdate() {
        val r = release ?: return
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(
            tr("Version %s disponible", r.version),
            notes(r).trim().let { if (it.isEmpty()) "" else it + "\n\n" } +
                tr("Toque va la télécharger depuis GitHub, l'installer puis redémarrer (environ une minute). " +
                    "Le moteur reste arrêté pendant l'opération."),
            tr("Installer"), tr("Plus tard"), icon = Icon.Kind.SYNC,
        ) {
            a.machine.suspendPort { v ->
                if (!v.ok) { a.refusal(v); return@suspendPort }
                upd = Upd.DOWNLOADING
                updPercent = 0
                renderUpdate()
                Thread {
                    try {
                        val file = updater.download(r) { p -> view.post { updPercent = p; renderUpdate() } }
                        view.post { upd = Upd.INSTALLING; renderUpdate() }
                        updater.install(file)
                    } catch (e: Updater.Failure) {
                        view.post { updateFailed(e.message ?: tr("Erreur")) }
                    } catch (e: Exception) {
                        a.app.log.crash("maj", e)
                        view.post { updateFailed(tr("Erreur inattendue : %s", e.message)) }
                    }
                }.start()
            }
        }
    }

    private fun updateFailed(message: String) {
        a.machine.resumePort()
        upd = Upd.ERROR
        updMessage = message
        renderUpdate()
        a.toast(message, Ui.WARN)
    }

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        renderReceiver()
        if (!syncing) refreshSync()
        // Synchro lancée ailleurs (nuit, autre écran) : son avancement s'affiche ici aussi.
        unsubProgress = SyncProgress.onChange { if (SyncProgress.running) syncText.text = SyncProgress.text else if (!syncing) refreshSync() }
        if (SyncProgress.running) syncText.text = SyncProgress.text
        unsubTm = CookidooProgress.onChange { renderCookidoo() }
        view.post(voiceTick)
        renderCookidoo()
    }

    override fun onHide() {
        unsubProgress?.invoke()
        unsubProgress = null
        unsubTm?.invoke()
        unsubTm = null
        view.removeCallbacks(voiceTick)
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée")
    }
}
