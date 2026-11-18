package app.toque.recipes

import android.os.Handler
import android.os.Looper

/** Avancement de la synchro en cours, visible de tous les écrans (accueil, réglages). */
object SyncProgress {
    @Volatile var text: String = ""
        private set
    @Volatile var running = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    fun update(running: Boolean, text: String) {
        this.running = running
        this.text = text
        main.post { for (l in ArrayList(listeners)) l() }
    }
}
