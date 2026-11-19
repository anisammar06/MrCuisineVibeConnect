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
import app.toque.core.voice.VoiceCommand
import app.toque.core.voice.Wav
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
    /** Fenêtre de suite de conversation (sans mot d'appel) après une réponse de l'assistant. */
    @Volatile private var followUntil = 0L
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs(this)
        if (!p.voiceEnabled || !VoiceModels.installed(this, p.voiceLang)) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getLongExtra(EXTRA_PAUSE_MS, 0L)?.takeIf { it > 0 }?.let { pauseUntil = System.currentTimeMillis() + it }
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            pauseUntil = 0
            // Le micro met ~1 s à repartir : la fenêtre compte depuis la reprise.
            followUntil = if (intent.getBooleanExtra(EXTRA_FOLLOW, false)) System.currentTimeMillis() + 9000 else 0
        }
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
        val prefs = Prefs(this)
        val requireWake = prefs.voiceWake
        // Assistant en ligne : une phrase libre après « robot » part vers Claude.
        val assistant = prefs.assistantReady
        var awaitUntil = 0L
        var pending: List<String> = emptyList()
        // Mémoire des 20 dernières secondes de son brut, pour envoyer la phrase entière à l'assistant.
        val ring = ShortArray(RATE * 20)
        var total = 0L
        while (running) {
            if (System.currentTimeMillis() < pauseUntil) {
                Thread.sleep(300)
                continue
            }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            } catch (e: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                Thread.sleep(3000)
                continue
            }
            val recognizer = Recognizer(model, RATE.toFloat(), grammar)
            // Le micro du robot capte faiblement la voix (pics de 3 % à un mètre) : contrôle
            // automatique du gain d'Android si disponible, puis amplification logicielle réglable.
            val agc = try {
                if (android.media.audiofx.AutomaticGainControl.isAvailable())
                    android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } else null
            } catch (_: Exception) {
                null
            }
            val gain = prefs.voiceGain.coerceIn(1, 32)
            try {
                rec.startRecording()
                val buf = ShortArray(1600) // 0,1 s
                var peak = 0
                var lastLevel = System.currentTimeMillis()
                var uttStart = total
                while (running && System.currentTimeMillis() >= pauseUntil) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (k in 0 until n) {
                        ring[((total + k) % ring.size).toInt()] = buf[k]
                        val amp = (buf[k] * gain).coerceIn(-32768, 32767)
                        buf[k] = amp.toShort()
                        val v = Math.abs(amp)
                        if (v > peak) peak = v
                    }
                    total += n
                    if (System.currentTimeMillis() - lastLevel > 5000) {
                        level = peak * 100 / 32767
                        peak = 0
                        lastLevel = System.currentTimeMillis()
                        writeStatus()
                    }
                    if (!recognizer.acceptWaveForm(buf, n)) continue
                    val segStart = uttStart
                    uttStart = total
                    val text = Json.parse(recognizer.result).obj().str("text").orEmpty().trim()
                    if (text.isEmpty()) continue
                    val now = System.currentTimeMillis()
                    // Suite de conversation : juste après une réponse, pas besoin de redire « robot ».
                    if (assistant && now < followUntil) {
                        followUntil = 0
                        heard(text, "→ assistant (suite)")
                        ask(ring, total, segStart, lang)
                        continue
                    }
                    val after = Voice.afterWake(text, lang)
                    val words: List<String>? = when {
                        after != null -> after
                        !requireWake || now < awaitUntil -> text.split(' ')
                        else -> null
                    }
                    if (words == null) {
                        heard(text, "ignoré (pas de « robot »)")
                        continue
                    }
                    val clean = words.filter { it != "[unk]" }
                    // Mots hors commandes (« [unk] ») après le mot d'appel.
                    val raw = text.split(' ')
                    val wakeAt = raw.indexOfFirst { it in Voice.WAKE[lang].orEmpty() }
                    val unknown = raw.drop(if (after != null) wakeAt + 1 else 0).count { it == "[unk]" }
                    val joined = if (after == null && now < awaitUntil) pending + clean else clean
                    val cmd = if (joined.isEmpty()) null else Voice.parseTokens(joined, lang)
                    // Phrase libre (surtout des mots hors commandes) : c'est pour l'assistant.
                    val free = unknown > 0 && unknown * 2 >= clean.size + unknown
                    if (assistant && clean.size + unknown > 0 && (cmd == null || free)) {
                        pending = emptyList()
                        awaitUntil = 0
                        heard(text, "→ assistant")
                        ask(ring, total, segStart, lang)
                        continue
                    }
                    if (joined.isEmpty()) {
                        pending = emptyList()
                        awaitUntil = now + 6000
                        beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        VoiceReceiver.send(this, VoiceReceiver.LISTENING, text)
                        heard(text, "j'écoute…")
                        continue
                    }
                    if (cmd == null) {
                        if (pending.isEmpty() || after != null) {
                            // Début de commande incomplet : on attend la suite 6 secondes.
                            pending = joined.takeLast(8)
                            awaitUntil = now + 6000
                            beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                            heard(text, "incomplet, j'attends la suite")
                        } else {
                            pending = emptyList()
                            awaitUntil = 0
                            VoiceReceiver.send(this, VoiceReceiver.UNKNOWN, joined.joinToString(" "))
                            heard(text, "pas compris")
                        }
                    } else {
                        pending = emptyList()
                        awaitUntil = 0
                        beep?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                        VoiceReceiver.sendCommand(this, cmd, joined.joinToString(" "))
                        heard(text, "→ " + describe(cmd))
                    }
                }
            } catch (e: Exception) {
                log("écoute : $e")
                Thread.sleep(2000)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                agc?.release()
                rec.release()
                recognizer.close()
            }
        }
        model.close()
        beep?.release()
    }

    /**
     * Envoie la phrase (son brut des [from]..[to] derniers échantillons, volume remonté) à l'assistant
     * de l'appli, et coupe l'écoute le temps qu'il réponde (il la relance ensuite).
     */
    private fun ask(ring: ShortArray, to: Long, from: Long, lang: String) {
        val len = (to - from).coerceIn(0, ring.size.toLong() - RATE.toLong()).toInt()
        if (len < RATE / 3) return
        val pcm = ShortArray(len) { ring[((to - len + it) % ring.size).toInt()] }
        val f = File(filesDir, "voix/question.wav")
        try {
            f.writeBytes(Wav.encode(Wav.normalize(pcm), RATE))
        } catch (e: Exception) {
            log("question : $e")
            return
        }
        pauseUntil = System.currentTimeMillis() + 90_000
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply { startTone(ToneGenerator.TONE_PROP_ACK, 150) }
        } catch (_: RuntimeException) {
        }
        VoiceReceiver.ask(this, f.path, lang)
    }

    private fun log(s: String) = ToqueApp.of(this).log.write("voix", s)

    // Diagnostic affiché dans les réglages : niveau du micro et dernières phrases reconnues.
    @Volatile private var level = 0
    private val lastHeard = ArrayList<String>()

    private fun heard(text: String, outcome: String) {
        synchronized(lastHeard) {
            if (lastHeard.isEmpty()) loadHeard()
            lastHeard += java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) +
                "  « " + text + " »  " + outcome
            while (lastHeard.size > 6) lastHeard.removeAt(0)
        }
        writeStatus()
    }

    // Les phrases précédentes survivent au redémarrage de l'écoute (changement de réglage).
    private fun loadHeard() {
        try {
            val f = File(filesDir, "voix/etat.txt")
            if (f.exists()) lastHeard += f.readLines().drop(1).filter { it.isNotBlank() }.takeLast(6)
        } catch (_: Exception) {
        }
    }

    private fun describe(c: VoiceCommand): String = when (c) {
        is VoiceCommand.Timer -> "minuteur « ${c.name} » " + (c.seconds / 60).let { m -> if (c.seconds % 60 == 0) "$m min" else "$m min ${c.seconds % 60} s" }
        else -> c.javaClass.simpleName
    }

    private fun writeStatus() {
        try {
            val lines = synchronized(lastHeard) { if (lastHeard.isEmpty()) loadHeard(); lastHeard.toList() }
            File(filesDir, "voix/etat.txt").writeText("niveau=$level\n" + lines.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAUSE_MS = "pause_ms"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_FOLLOW = "follow"
        const val RATE = 16_000

        /** Relance l'écoute après une réponse de l'assistant ([follow] : la suite sans « robot »). */
        fun resume(c: Context, follow: Boolean) {
            val p = Prefs(c)
            if (p.voiceEnabled) c.startService(Intent(c, VoiceService::class.java).putExtra(EXTRA_RESUME, true).putExtra(EXTRA_FOLLOW, follow))
        }

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

    /**
     * Empreintes SHA-256 des modèles. Le site utilise la nouvelle chaîne Let's Encrypt (« Root YR »),
     * inconnue d'Android 6 : la connexion est acceptée sans vérifier le certificat, mais le fichier
     * reçu doit avoir exactement cette empreinte, sinon il est rejeté.
     */
    private val SHA256 = mapOf(
        "fr" to "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad",
        "pl" to "c4cd16498ea544f446f9e9a55cbd602b71cfe5a2b6f2b0834d81e1b6fce15f0d",
    )

    private val trustAll: javax.net.ssl.SSLSocketFactory by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    fun dir(c: Context, lang: String) = File(c.filesDir, "voix/$lang")
    fun installed(c: Context, lang: String) = File(dir(c, lang), "am/final.mdl").exists()

    /** Télécharge et décompresse un modèle ; [progress] reçoit un pourcentage. */
    fun download(c: Context, lang: String, progress: (Int) -> Unit) {
        val url = URLS[lang] ?: throw IllegalArgumentException(lang)
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = trustAll
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLength.toLong().coerceAtLeast(1)
        val tmp = File(c.filesDir, "voix/$lang.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var read = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val counting = object : java.io.FilterInputStream(java.security.DigestInputStream(conn.inputStream, digest)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) { read += n; progress((read * 100 / total).toInt().coerceIn(0, 99)) }
                return n
            }
        }
        val buffered = counting.buffered()
        val zip = ZipInputStream(buffered)
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
        // Lire la fin du fichier (répertoire du zip) pour que l'empreinte le couvre en entier.
        val rest = ByteArray(16384)
        while (buffered.read(rest, 0, rest.size) >= 0) { }
        zip.close()
        conn.disconnect()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != SHA256[lang]) {
            tmp.deleteRecursively()
            throw java.io.IOException("fichier de la voix altéré (empreinte inattendue)")
        }
        val dest = dir(c, lang)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw java.io.IOException("installation du modèle impossible")
        progress(100)
    }

    fun delete(c: Context, lang: String) = dir(c, lang).deleteRecursively()

    /** Niveau du micro (0–100, sur les 5 dernières secondes) et dernières phrases reconnues. */
    fun status(c: Context): Pair<Int, List<String>>? {
        val f = File(c.filesDir, "voix/etat.txt")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 15_000) return null
        val lines = f.readLines()
        val level = lines.firstOrNull()?.substringAfter('=')?.toIntOrNull() ?: 0
        return level to lines.drop(1).filter { it.isNotBlank() }
    }
}
