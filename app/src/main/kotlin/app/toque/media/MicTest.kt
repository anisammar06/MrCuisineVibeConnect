package app.toque.media

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Test du micro : 5 s d'écoute, niveau affiché en direct, verdict à la fin. */
object MicTest {
    @Volatile private var busy = false

    fun run(c: Context, level: (Float) -> Unit, result: (String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                result(tr("Écoute… parlez maintenant."))
                result(listen(c, level))
            } finally {
                busy = false
            }
        }.start()
    }

    private fun listen(c: Context, level: (Float) -> Unit): String {
        if (c.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RootShell.run("pm grant ${c.packageName} android.permission.RECORD_AUDIO")
            if (c.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return tr("Autorisation du micro refusée.")
            }
        }
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return tr("Aucune entrée micro sur ce robot.")
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
        } catch (e: Exception) {
            return tr("Micro inaccessible : %s", e.message ?: e.toString())
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return tr("Micro inaccessible (déjà utilisé ou absent).")
        }
        val buf = ShortArray(1600)
        var peak = 0
        var quietPeak = Int.MAX_VALUE
        var total = 0L
        var samples = 0L
        try {
            rec.startRecording()
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var p = 0
                for (i in 0 until n) {
                    val v = abs(buf[i].toInt())
                    if (v > p) p = v
                    total += v
                }
                samples += n
                peak = max(peak, p)
                quietPeak = minOf(quietPeak, p)
                level((log10(1.0 + p) / log10(32768.0)).toFloat().coerceIn(0f, 1f))
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            level(0f)
        }
        val pct = peak * 100 / 32767
        val avg = if (samples > 0) total / samples else 0
        return when {
            peak == 0 -> tr("Aucun son reçu : pas de micro branché (signal nul).")
            peak - quietPeak < 300 -> tr("Signal constant (pic %d %%, moyenne %d) : probablement pas de vrai micro.", pct, avg)
            else -> tr("Micro détecté : pic %d %% (silence %d %%). La commande vocale est possible.", pct, quietPeak * 100 / 32767)
        }
    }
}
