package app.toque.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.voice.Voice
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Écoute du micro et reconnaissance vocale hors ligne (Vosk), dans un processus à part (« :voix ») :
 * un souci du moteur ne touche ni l'interface ni la commande du moteur du robot.
 * Le moteur ne cherche que les mots des commandes (grammaire fermée) ; rien n'est enregistré ni
 * envoyé sur Internet. Les commandes reconnues sont transmises à l'appli par [VoiceReceiver].
 */
class VoiceService : Service() {
    @Volatile private var running = false
    @Volatile private var pauseUntil = 0L
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs(this)
        if (!p.voiceEnabled || !VoiceModels.installed(this, p.voiceLang)) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getLongExtra(EXTRA_PAUSE_MS, 0L)?.takeIf { it > 0 }?.let { pauseUntil = System.currentTimeMillis() + it }
        if (!running) {
            running = true
            thread = Thread({ loop(p.voiceLang) }, "toque-voix").also { it.start() }
        }
        return START_STICKY
    }

    private fun loop(lang: String) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = try {
            Model(VoiceModels.dir(this, lang).path)
        } catch (e: Exception) {
            log("modèle $lang illisible : $e")
            running = false
            stopSelf()
            return
        }
        val grammar = Json.write(Voice.grammar(lang))
        val beep = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: RuntimeException) {
            null
        }
        val requireWake = Prefs(this).voiceWake
        var awaitUntil = 0L
        while (running) {
            if (System.currentTimeMillis() < pauseUntil) {
                Thread.sleep(300)
                continue
            }
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            } catch (e: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                Thread.sleep(3000)
                continue
            }
            val recognizer = Recognizer(model, rate.toFloat(), grammar)
            try {
                rec.startRecording()
                val buf = ShortArray(1600) // 0,1 s
                while (running && System.currentTimeMillis() >= pauseUntil) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (!recognizer.acceptWaveForm(buf, n)) continue
                    val text = Json.parse(recognizer.result).obj().str("text").orEmpty().trim()
                    if (text.isEmpty()) continue
                    val now = System.currentTimeMillis()
                    val words: List<String>? = when {
                        !requireWake -> Voice.afterWake(text, lang) ?: text.split(' ')
                        now < awaitUntil -> Voice.afterWake(text, lang) ?: text.split(' ')
                        else -> Voice.afterWake(text, lang)
                    }
                    if (words == null) continue
                    if (words.isEmpty() || words.all { it == "[unk]" }) {
                        // Mot d'appel seul : bip, la commande suit dans les 6 secondes.
                        awaitUntil = now + 6000
                        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        VoiceReceiver.send(this, VoiceReceiver.LISTENING, text)
                        continue
                    }
                    awaitUntil = 0
                    val cmd = Voice.parseTokens(words.filter { it != "[unk]" }, lang)
                    if (cmd == null) VoiceReceiver.send(this, VoiceReceiver.UNKNOWN, text)
                    else {
                        beep?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                        VoiceReceiver.sendCommand(this, cmd, text)
                    }
                }
            } catch (e: Exception) {
                log("écoute : $e")
                Thread.sleep(2000)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                recognizer.close()
            }
        }
        model.close()
        beep?.release()
    }

    private fun log(s: String) = ToqueApp.of(this).log.write("voix", s)

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAUSE_MS = "pause_ms"

        fun start(c: Context) {
            val p = Prefs(c)
            if (p.voiceEnabled && VoiceModels.installed(c, p.voiceLang)) c.startService(Intent(c, VoiceService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, VoiceService::class.java))
        }

        fun restart(c: Context) {
            stop(c)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(c) }, 800)
        }

        /** Libère le micro un moment (test du micro). */
        fun pauseFor(c: Context, ms: Long) {
            val p = Prefs(c)
            if (p.voiceEnabled) c.startService(Intent(c, VoiceService::class.java).putExtra(EXTRA_PAUSE_MS, ms))
        }
    }
}

/** Modèles de reconnaissance Vosk (« small », ~45 Mo chacun), téléchargés à la demande. */
object VoiceModels {
    private val URLS = mapOf(
        "fr" to "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
        "pl" to "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip",
    )

    fun dir(c: Context, lang: String) = File(c.filesDir, "voix/$lang")
    fun installed(c: Context, lang: String) = File(dir(c, lang), "am/final.mdl").exists()

    /** Télécharge et décompresse un modèle ; [progress] reçoit un pourcentage. */
    fun download(c: Context, lang: String, progress: (Int) -> Unit) {
        val url = URLS[lang] ?: throw IllegalArgumentException(lang)
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = app.toque.recipes.CookidooClient.sslFactory(c)
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLength.toLong().coerceAtLeast(1)
        val tmp = File(c.filesDir, "voix/$lang.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var read = 0L
        val counting = object : java.io.FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) { read += n; progress((read * 100 / total).toInt().coerceIn(0, 99)) }
                return n
            }
        }
        ZipInputStream(counting.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // Le zip contient un dossier « vosk-model-small-xx-0.22/ » : on le retire.
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val f = File(tmp, rel)
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zip.copyTo(it) }
                }
            }
        }
        conn.disconnect()
        val dest = dir(c, lang)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw java.io.IOException("installation du modèle impossible")
        progress(100)
    }

    fun delete(c: Context, lang: String) = dir(c, lang).deleteRecursively()
}
