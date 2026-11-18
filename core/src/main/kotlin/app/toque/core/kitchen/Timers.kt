package app.toque.core.kitchen

import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.list
import app.toque.core.json.long
import app.toque.core.json.obj
import app.toque.core.json.str

/**
 * Minuteur de cuisine (four, pâtes, repos…), indépendant de la cuisson du robot.
 * L'échéance est une heure murale ([endAt], ms) : le minuteur survit à un redémarrage de l'appli.
 */
data class KitchenTimer(
    val id: String,
    val name: String,
    val durationMs: Long,
    /** Heure de fin si le minuteur tourne, 0 s'il est en pause. */
    val endAt: Long,
    /** Temps restant au moment de la pause. */
    val pausedLeftMs: Long = 0,
    /** Sonnerie arrêtée par l'utilisateur. */
    val dismissed: Boolean = false,
) {
    val paused: Boolean get() = endAt == 0L
    fun leftMs(now: Long): Long = if (paused) pausedLeftMs else (endAt - now).coerceAtLeast(0)
    fun ringing(now: Long): Boolean = !paused && !dismissed && now >= endAt
    fun finished(now: Long): Boolean = !paused && now >= endAt
}

/** Ensemble des minuteurs ; toutes les opérations prennent l'heure courante en paramètre (testable). */
class TimerBoard(timers: List<KitchenTimer> = emptyList()) {
    private val list = ArrayList(timers)
    val timers: List<KitchenTimer> get() = list.toList()
    private var seq = System.currentTimeMillis()

    fun start(name: String, durationMs: Long, now: Long): KitchenTimer {
        val t = KitchenTimer((++seq).toString(36), name.trim().ifEmpty { "Minuteur" }, durationMs, now + durationMs)
        list += t
        return t
    }

    fun get(id: String) = list.firstOrNull { it.id == id }

    private fun update(id: String, f: (KitchenTimer) -> KitchenTimer): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = f(list[i])
        return true
    }

    fun pause(id: String, now: Long) = update(id) { if (it.paused || it.finished(now)) it else it.copy(endAt = 0, pausedLeftMs = it.leftMs(now)) }
    fun resume(id: String, now: Long) = update(id) { if (it.paused) it.copy(endAt = now + it.pausedLeftMs, pausedLeftMs = 0) else it }

    /** Ajoute du temps ; sur un minuteur qui sonne, le relance pour cette durée. */
    fun add(id: String, ms: Long, now: Long) = update(id) {
        when {
            it.paused -> it.copy(pausedLeftMs = it.pausedLeftMs + ms, durationMs = it.durationMs + ms)
            it.finished(now) -> it.copy(endAt = now + ms, durationMs = ms, dismissed = false)
            else -> it.copy(endAt = it.endAt + ms, durationMs = it.durationMs + ms)
        }
    }

    fun dismiss(id: String) = update(id) { it.copy(dismissed = true) }
    fun remove(id: String) = list.removeAll { it.id == id }

    /** Minuteurs qui sonnent maintenant. */
    fun ringing(now: Long) = list.filter { it.ringing(now) }

    /** Prochaine échéance à programmer (minuteurs actifs non terminés), ou null. */
    fun nextEnd(now: Long): Long? = list.filter { !it.paused && it.endAt > now }.minOfOrNull { it.endAt }

    /** Retire les minuteurs terminés et arrêtés depuis plus de [keepMs]. */
    fun purge(now: Long, keepMs: Long = 10 * 60_000L) = list.removeAll { it.dismissed && it.finished(now) && now - it.endAt > keepMs }

    fun encode(): String = Json.write(list.map {
        linkedMapOf("id" to it.id, "name" to it.name, "durationMs" to it.durationMs, "endAt" to it.endAt,
            "pausedLeftMs" to it.pausedLeftMs, "dismissed" to it.dismissed)
    })

    companion object {
        fun decode(text: String): TimerBoard = TimerBoard(try {
            Json.parse(text).list().mapNotNull { a ->
                val m = a.obj()
                val id = m.str("id") ?: return@mapNotNull null
                KitchenTimer(id, m.str("name").orEmpty(), m.long("durationMs") ?: 0, m.long("endAt") ?: 0,
                    m.long("pausedLeftMs") ?: 0, m.bool("dismissed") ?: false)
            }
        } catch (_: Exception) {
            emptyList()
        })

        /** « 1:05:00 », « 4:07 ». */
        fun format(ms: Long): String {
            val s = (ms + 999) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
