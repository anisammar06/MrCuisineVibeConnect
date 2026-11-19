package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Écriture synchrone (commit) : le processus machine relit le fichier juste après.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) {
            p.edit().putString("mode", v.key).commit()
        }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String
        get() = p.getString("language", I18n.AUTO) ?: I18n.AUTO
        set(v) {
            p.edit().putString("language", v).commit()
        }

    /** Langue des recettes affichées : « fr » ou « pl » (catalogues gardés en parallèle). */
    var recipeLang: String
        get() = p.getString("recipeLang", "fr") ?: "fr"
        set(v) {
            p.edit().putString("recipeLang", v).commit()
        }

    /** Synchroniser aussi le catalogue polonais. */
    var syncPolish: Boolean
        get() = p.getBoolean("syncPolish", true)
        set(v) {
            p.edit().putBoolean("syncPolish", v).commit()
        }

    /** Langue des recettes Thermomix affichées : « fr » ou « pl ». */
    var tmLang: String
        get() = p.getString("tmLang", "fr") ?: "fr"
        set(v) {
            p.edit().putString("tmLang", v).commit()
        }

    /** Compte Cookidoo connecté (le mot de passe n'est jamais enregistré). */
    var cookidooEmail: String
        get() = p.getString("cookidooEmail", "") ?: ""
        set(v) {
            p.edit().putString("cookidooEmail", v).commit()
        }

    /** Jeton d'accès Cookidoo. */
    var cookidooAccess: String
        get() = p.getString("cookidooAccess", "") ?: ""
        set(v) {
            p.edit().putString("cookidooAccess", v).commit()
        }

    /** Jeton de renouvellement Cookidoo. */
    var cookidooRefresh: String
        get() = p.getString("cookidooRefresh", "") ?: ""
        set(v) {
            p.edit().putString("cookidooRefresh", v).commit()
        }

    /** Expiration du jeton d'accès (ms). */
    var cookidooExpires: Long
        get() = p.getLong("cookidooExpires", 0)
        set(v) {
            p.edit().putLong("cookidooExpires", v).commit()
        }

    /** Recettes Thermomix en polonais aussi. */
    var cookidooPolish: Boolean
        get() = p.getBoolean("cookidooPolish", true)
        set(v) {
            p.edit().putBoolean("cookidooPolish", v).commit()
        }

    /** Synchro Cookidoo chaque nuit (listes, puis un lot de nouvelles recettes). */
    var cookidooAuto: Boolean
        get() = p.getBoolean("cookidooAuto", true)
        set(v) {
            p.edit().putBoolean("cookidooAuto", v).commit()
        }

    /** Nouvelles recettes Cookidoo ajoutées à chaque synchro, par langue. */
    var cookidooBatch: Int
        get() = p.getInt("cookidooBatch", 150)
        set(v) {
            p.edit().putInt("cookidooBatch", v).commit()
        }

    /** Résultat de la dernière synchro Cookidoo. */
    var cookidooResult: String
        get() = p.getString("cookidooResult", "") ?: ""
        set(v) {
            p.edit().putString("cookidooResult", v).commit()
        }

    /** Heure de la dernière synchro Cookidoo. */
    var cookidooLastMs: Long
        get() = p.getLong("cookidooLastMs", 0)
        set(v) {
            p.edit().putLong("cookidooLastMs", v).commit()
        }

    /** Réception des contenus envoyés depuis le téléphone (appli « Gotuj Send »). */
    var receiverEnabled: Boolean
        get() = p.getBoolean("receiverEnabled", true)
        set(v) {
            p.edit().putBoolean("receiverEnabled", v).commit()
        }

    /** Code d'appairage à 6 chiffres, tiré au hasard la première fois. */
    var receiverCode: String
        get() = p.getString("receiverCode", null) ?: newReceiverCode()
        set(v) {
            p.edit().putString("receiverCode", v).commit()
        }

    fun newReceiverCode(): String {
        val code = String.format(java.util.Locale.US, "%06d", java.security.SecureRandom().nextInt(1_000_000))
        p.edit().putString("receiverCode", code).commit()
        return code
    }

    /** Place maximale du dossier des fichiers reçus, en Mo (les plus anciens sont effacés). */
    var receivedMaxMb: Int
        get() = p.getInt("receivedMaxMb", 2048)
        set(v) {
            p.edit().putInt("receivedMaxMb", v).commit()
        }

    var twinHost: String
        get() = p.getString("twinHost", "127.0.0.1") ?: "127.0.0.1"
        set(v) {
            p.edit().putString("twinHost", v).commit()
        }

    var twinPort: Int
        get() = p.getInt("twinPort", 8765)
        set(v) {
            p.edit().putInt("twinPort", v).commit()
        }

    var serialDevice: String
        get() = p.getString("serialDevice", "/dev/ttyMT0") ?: "/dev/ttyMT0"
        set(v) {
            p.edit().putString("serialDevice", v).commit()
        }

    var vendorPackage: String
        get() = p.getString("vendorPackage", "de.silpion.mc2") ?: "de.silpion.mc2"
        set(v) {
            p.edit().putString("vendorPackage", v).commit()
        }

    var requireBowl: Boolean
        get() = p.getBoolean("requireBowl", false)
        set(v) {
            p.edit().putBoolean("requireBowl", v).commit()
        }

    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean
        get() = p.getBoolean("hotRamp", true)
        set(v) {
            p.edit().putBoolean("hotRamp", v).commit()
        }

    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean
        get() = p.getBoolean("chainSteps", true)
        set(v) {
            p.edit().putBoolean("chainSteps", v).commit()
        }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) {
            p.edit().putBoolean("sound", v).commit()
        }

    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String
        get() = p.getString("timeZone", "auto") ?: "auto"
        set(v) {
            p.edit().putString("timeZone", v).commit()
        }

    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String
        get() = p.getString("lastZone", "") ?: ""
        set(v) {
            p.edit().putString("lastZone", v).commit()
        }

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) {
            p.edit().putBoolean("autoSync", v).commit()
        }

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0)
        set(v) {
            p.edit().putLong("lastSyncMs", v).commit()
        }

    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) {
            p.edit().putString("lastSyncResult", v).commit()
        }

    var vendorSession: Boolean
        get() = p.getBoolean("vendorSession", false)
        set(v) {
            p.edit().putBoolean("vendorSession", v).commit()
        }

    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean
        get() = p.getBoolean("returnBubble", false)
        set(v) {
            p.edit().putBoolean("returnBubble", v).commit()
        }

    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean
        get() = p.getBoolean("bootLaunch", true)
        set(v) {
            p.edit().putBoolean("bootLaunch", v).commit()
        }

    var knobLeft: Int
        get() = p.getInt("knobLeft", -1)
        set(v) {
            p.edit().putInt("knobLeft", v).commit()
        }

    var knobRight: Int
        get() = p.getInt("knobRight", -1)
        set(v) {
            p.edit().putInt("knobRight", v).commit()
        }

    var knobPress: Int
        get() = p.getInt("knobPress", -1)
        set(v) {
            p.edit().putInt("knobPress", v).commit()
        }

    var lastCrashMs: Long
        get() = p.getLong("lastCrashMs", 0)
        set(v) {
            p.edit().putLong("lastCrashMs", v).commit()
        }

    var welcomeDone: Boolean
        get() = p.getBoolean("welcomeDone", false)
        set(v) {
            p.edit().putBoolean("welcomeDone", v).commit()
        }

    var builtinVersion: Int
        get() = p.getInt("builtinVersion", 0)
        set(v) {
            p.edit().putInt("builtinVersion", v).commit()
        }

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String
        get() = p.getString("guidedRecipe", "") ?: ""
        set(v) {
            p.edit().putString("guidedRecipe", v).commit()
        }

    var guidedStep: Int
        get() = p.getInt("guidedStep", 0)
        set(v) {
            p.edit().putInt("guidedStep", v).commit()
        }

    /** Commande vocale hors ligne. */
    var voiceEnabled: Boolean
        get() = p.getBoolean("voiceEnabled", false)
        set(v) {
            p.edit().putBoolean("voiceEnabled", v).commit()
        }

    /** Langue écoutée : « fr » ou « pl » (un seul modèle à la fois, pour la mémoire du robot). */
    var voiceLang: String
        get() = p.getString("voiceLang", "fr") ?: "fr"
        set(v) {
            p.edit().putString("voiceLang", v).commit()
        }

    /** Amplification du micro pour la commande vocale (le micro du robot est peu sensible). */
    var voiceGain: Int
        get() = p.getInt("voiceGain", 8)
        set(v) {
            p.edit().putInt("voiceGain", v).commit()
        }

    /** Mot d'appel « robot » obligatoire avant une commande. */
    var voiceWake: Boolean
        get() = p.getBoolean("voiceWake", true)
        set(v) {
            p.edit().putBoolean("voiceWake", v).commit()
        }

    /** Enceinte Spotify Connect sur le robot. */
    var spotifyEnabled: Boolean
        get() = p.getBoolean("spotifyEnabled", true)
        set(v) {
            p.edit().putBoolean("spotifyEnabled", v).commit()
        }

    /** Nom de l'enceinte dans l'appli Spotify. */
    var spotifyName: String
        get() = p.getString("spotifyName", "Gotuj z Kasią") ?: "Gotuj z Kasią"
        set(v) {
            p.edit().putString("spotifyName", v).commit()
        }

    /** Version du rangement des recettes Cookidoo (2 : par langue de la recette). */
    var cookidooSchema: Int
        get() = p.getInt("cookidooSchema", 0)
        set(v) {
            p.edit().putInt("cookidooSchema", v).commit()
        }

    /** Page de recherche Cookidoo où reprendre l'ajout progressif du catalogue. */
    fun cookidooPage(lang: String): Int = p.getInt("cookidooPage_$lang", 0)
    fun setCookidooPage(lang: String, page: Int) {
        p.edit().putInt("cookidooPage_$lang", page).commit()
    }
}
