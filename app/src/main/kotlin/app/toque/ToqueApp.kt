package app.toque

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import app.toque.core.i18n.I18n
import app.toque.machine.MachineClient
import app.toque.recipes.RecipeRepo
import app.toque.ui.MainActivity
import java.util.Locale

class ToqueApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var log: Logbook
        private set
    val machine: MachineClient by lazy { MachineClient(this) }
    val recipesFr: RecipeRepo by lazy { RecipeRepo(this, "fr") }
    val recipesPl: RecipeRepo by lazy { RecipeRepo(this, "pl") }

    /** Catalogue dans la langue choisie sur l'écran Recettes (bouton FR | PL). */
    val recipes: RecipeRepo get() = repo(prefs.recipeLang)

    fun repo(lang: String): RecipeRepo = if (lang == "pl") recipesPl else recipesFr

    /** Recettes Thermomix (Cookidoo) converties, en français et en polonais. */
    val tmFr: RecipeRepo by lazy { RecipeRepo(this, "tm-fr") }
    val tmPl: RecipeRepo by lazy { RecipeRepo(this, "tm-pl") }

    fun tm(lang: String): RecipeRepo = if (lang == "pl") tmPl else tmFr

    /** Catalogue Thermomix dans la langue choisie sur son écran (bouton FR | PL). */
    val thermomix: RecipeRepo get() = tm(prefs.tmLang)

    /** Catalogue qui contient une recette (pour reprendre une cuisson guidée). */
    fun repoOf(id: String): RecipeRepo = listOf(recipesFr, recipesPl, tmFr, tmPl).firstOrNull { it.get(id) != null } ?: recipes

    /** Langues à synchroniser depuis monsieur-cuisine.com. */
    fun syncLangs(): List<String> = if (prefs.syncPolish) listOf("fr", "pl") else listOf("fr")

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = Logbook(this)
        LanguageFiles.install(this, log)
        I18n.select(I18n.resolve(prefs.language, Locale.getDefault().language).code)
        if (!isMainProcess()) return
        Prefs(this).migrateBowlCheck()
        ScreenPower.applyAwake(this)
        Thread { Ring.off() }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log.crash("interface", error)
                val now = System.currentTimeMillis()
                val recent = now - prefs.lastCrashMs < 10_000
                prefs.lastCrashMs = now
                if (recent) throw error // évite une boucle de relances
                // Relance discrète de l'interface ; le processus machine, lui, continue.
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_ONE_SHOT)
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
        log.write("interface", "démarrage")
        TimeSync.start(this, prefs, log)
        app.toque.kitchen.Timers.restore(this)
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: return true
        return name == packageName
    }

    companion object {
        fun of(c: Context): ToqueApp = c.applicationContext as ToqueApp
    }
}
