package app.toque

import android.content.Context
import android.os.PowerManager
import app.toque.core.machine.RootShell

/**
 * Mise en veille automatique de l'écran. Android éteint l'écran après le délai système ; Toque ne le
 * garde allumé que quand c'est utile (cuisson en cours, cuisson guidée, minuteur, assistant qui parle).
 * Un minuteur qui sonne ou l'assistant rallument l'écran.
 */
object ScreenPower {
    /** Applique le délai choisi (en minutes, 0 = jamais) au réglage système, via root. */
    fun applyTimeout(c: Context) {
        val min = Prefs(c).screenSleepMin
        val ms = if (min <= 0) Int.MAX_VALUE.toLong() else min * 60_000L
        Thread {
            try {
                RootShell.run("settings put system screen_off_timeout $ms", 8_000)
            } catch (_: Exception) {
            }
        }.start()
    }

    private var cpu: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Sans veille profonde : le robot reste joignable écran éteint (assistant, commande vocale,
     * Spotify, réception depuis le téléphone). Il est branché sur le secteur.
     */
    @Synchronized
    fun applyAwake(c: Context) {
        val stayAwake = !Prefs(c).deepSleepAllowed
        try {
            if (stayAwake) {
                if (cpu == null) cpu = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toque:eveille").apply { setReferenceCounted(false); acquire() }
                if (wifi == null) wifi = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "toque:wifi").apply { setReferenceCounted(false); acquire() }
            } else {
                cpu?.release(); cpu = null
                wifi?.release(); wifi = null
            }
        } catch (_: Exception) {
        }
    }

    /** Rallume l'écran (et le garde allumé [ms] millisecondes au moins). */
    @Suppress("DEPRECATION")
    fun wake(c: Context, ms: Long = 15_000) {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive && ms <= 0) return
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "toque:reveil").acquire(maxOf(ms, 1000))
        } catch (_: Exception) {
        }
    }
}
