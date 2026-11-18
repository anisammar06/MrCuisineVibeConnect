package app.toque.kitchen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.toque.core.kitchen.ShoppingList
import app.toque.core.kitchen.TimerBoard
import java.io.File

/** Abonnés notifiés sur le fil principal. */
open class Listeners {
    private val main = Handler(Looper.getMainLooper())
    private val list = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit): () -> Unit {
        main.post { list += l }
        return { main.post { list -= l } }
    }

    fun changed() {
        main.post { for (l in ArrayList(list)) l() }
    }
}

/** Liste de courses partagée entre l'écran, les recettes et la page web du téléphone. */
object Shopping : Listeners() {
    private var list: ShoppingList? = null
    private lateinit var file: File

    @Synchronized
    fun get(c: Context): ShoppingList {
        list?.let { return it }
        file = File(c.applicationContext.filesDir, "courses.json")
        val l = if (file.exists()) ShoppingList.decode(file.readText()) else ShoppingList()
        list = l
        return l
    }

    /** Modifie la liste, l'enregistre et prévient les écrans. */
    fun <T> edit(c: Context, f: (ShoppingList) -> T): T {
        val r: T
        synchronized(this) {
            val l = get(c)
            r = f(l)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(l.encode())
            tmp.renameTo(file)
        }
        changed()
        return r
    }
}

/**
 * Minuteurs de cuisine : enregistrés (ils survivent à un redémarrage), réveil programmé avec
 * AlarmManager, et rafraîchissement chaque seconde tant qu'un minuteur tourne.
 */
object Timers : Listeners() {
    private var board: TimerBoard? = null
    private lateinit var file: File
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            check(app)
            val b = board ?: return
            if (b.timers.any { !it.paused && !it.dismissed }) main.postDelayed(this, 1000)
        }
    }

    @Synchronized
    fun get(c: Context): TimerBoard {
        board?.let { return it }
        app = c.applicationContext
        file = File(app.filesDir, "minuteurs.json")
        val b = if (file.exists()) TimerBoard.decode(file.readText()) else TimerBoard()
        board = b
        return b
    }

    fun <T> edit(c: Context, f: (TimerBoard, Long) -> T): T {
        val r: T
        synchronized(this) {
            val b = get(c)
            r = f(b, System.currentTimeMillis())
            save()
        }
        schedule(c)
        changed()
        main.removeCallbacks(tick)
        main.post(tick)
        return r
    }

    private fun save() {
        val b = board ?: return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(b.encode())
        tmp.renameTo(file)
    }

    /** Au démarrage de l'appli : reprogramme le réveil et relance le décompte. */
    fun restore(c: Context) {
        get(c)
        schedule(c)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Fait sonner les minuteurs arrivés à échéance ; appelé chaque seconde et par l'alarme. */
    fun check(c: Context) {
        val now = System.currentTimeMillis()
        val ringing = synchronized(this) {
            val b = get(c)
            if (b.purge(now)) save()
            b.ringing(now)
        }
        if (ringing.isNotEmpty()) TimerRing.start(c) else TimerRing.stop(c)
        changed()
    }

    private fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 7, Intent(c, TimerAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        val next = synchronized(this) { get(c).nextEnd(System.currentTimeMillis()) }
        if (next == null) {
            am.cancel(pi)
            return
        }
        if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setExact(AlarmManager.RTC_WAKEUP, next, pi)
    }
}

class TimerAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Timers.restore(c)
        Timers.check(c)
    }
}
