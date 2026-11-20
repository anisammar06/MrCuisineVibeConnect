package app.toque

import java.io.OutputStream

/**
 * Anneau lumineux autour du bouton rotatif (LED rouge, verte et bleue de la tablette, pilotées par
 * /sys/class/leds via un shell root gardé ouvert). États par priorité : alerte > minuteur > parole >
 * réflexion > écoute.
 */
object Ring {
    enum class State { LISTENING, THINKING, SPEAKING, TIMER, ALERT }

    private val active = HashMap<State, Long>() // état → fin (0 = jusqu'à nouvel ordre)
    private var shell: Process? = null
    private var out: OutputStream? = null
    private var thread: Thread? = null
    private var last = intArrayOf(-1, -1, -1)
    @Volatile var enabled = true

    @Synchronized
    fun set(s: State, ms: Long = 0) {
        active[s] = if (ms > 0) System.currentTimeMillis() + ms else 0L
        start()
    }

    @Synchronized
    fun clear(s: State) {
        active.remove(s)
    }

    @Synchronized
    private fun current(now: Long): State? {
        active.entries.removeAll { it.value in 1..now }
        return State.values().reversed().firstOrNull { it in active }
    }

    @Synchronized
    private fun start() {
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "toque-anneau").apply { isDaemon = true; start() }
    }

    private fun loop() {
        var idle = 0
        while (true) {
            val now = System.currentTimeMillis()
            val s = if (enabled) current(now) else null
            val t = (now % 100_000) / 1000.0
            val rgb = when (s) {
                null -> intArrayOf(0, 0, 0)
                State.LISTENING -> intArrayOf(0, 60, 255)
                State.THINKING -> breathe(intArrayOf(170, 0, 255), t, 1.6)
                State.SPEAKING -> intArrayOf(0, 255, 40)
                State.TIMER -> if ((now / 400) % 2 == 0L) intArrayOf(255, 90, 0) else intArrayOf(0, 0, 0)
                State.ALERT -> intArrayOf(255, 0, 0)
            }
            write(rgb)
            if (s == null) {
                if (++idle > 10) {
                    close()
                    synchronized(this) { if (active.isEmpty()) { thread = null; return } }
                }
            } else idle = 0
            Thread.sleep(if (s == State.THINKING) 60 else 150)
        }
    }

    private fun breathe(c: IntArray, t: Double, period: Double): IntArray {
        val k = 0.15 + 0.85 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t / period))
        return IntArray(3) { (c[it] * k).toInt() }
    }

    private fun write(rgb: IntArray) {
        if (rgb.contentEquals(last)) return
        try {
            if (out == null) {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                shell = p
                out = p.outputStream
                // On lit la sortie pour que le shell ne se bloque jamais.
                Thread { try { p.inputStream.use { it.copyTo(NullOut) } } catch (_: Exception) {} }.apply { isDaemon = true; start() }
            }
            val o = out ?: return
            o.write("echo ${rgb[0]} > /sys/class/leds/red/brightness; echo ${rgb[1]} > /sys/class/leds/green/brightness; echo ${rgb[2]} > /sys/class/leds/blue/brightness\n".toByteArray())
            o.flush()
            last = rgb
        } catch (_: Exception) {
            close()
        }
    }

    private fun close() {
        try { out?.write("exit\n".toByteArray()); out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        shell?.destroy()
        shell = null
        out = null
    }

    /** Éteint l'anneau tout de suite (au démarrage de l'appli). */
    fun off() {
        last = intArrayOf(-1, -1, -1)
        write(intArrayOf(0, 0, 0))
        close()
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
