package app.toque.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Le robot devient une enceinte Spotify Connect « Gotuj z Kasią ».
 *
 * go-librespot (logiciel libre, compilé pour le robot et livré dans l'APK) parle à Spotify ; il
 * écrit le son dans un tube (PCM 44,1 kHz, stéréo, 16 bits) que ce service joue avec AudioTrack.
 * Il expose aussi une petite API locale (127.0.0.1:3678) : morceau en cours, pochette, pause,
 * suivant, volume — utilisée par l'écran Spotify de Toque.
 * Premier appairage : code à saisir sur spotify.com/pair, affiché par Toque.
 */
class SpotifyService : Service() {
    @Volatile private var running = false
    private var process: Process? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).spotifyEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) start()
        return START_STICKY
    }

    private fun start() {
        running = true
        val dir = dir(this)
        dir.mkdirs()
        val fifo = File(dir, "audio.fifo")
        if (!fifo.exists()) try {
            Os.mkfifo(fifo.path, 384) // 0600
        } catch (e: Exception) {
            log("tube audio impossible : $e")
        }
        writeConfig(dir, fifo)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("toque-spotify").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
        Thread({ audioLoop(fifo) }, "toque-spotify-son").start()
        Thread({ daemonLoop(dir) }, "toque-spotify").start()
    }

    private fun writeConfig(dir: File, fifo: File) {
        val name = Prefs(this).spotifyName.replace("'", "")
        File(dir, "config.yml").writeText(
            """
            |device_name: '$name'
            |device_type: speaker
            |audio_backend: pipe
            |audio_output_pipe: '${fifo.path}'
            |audio_output_pipe_format: s16le
            |audio_output_pipe_wait_for_reader: true
            |bitrate: 160
            |volume_steps: 100
            |initial_volume: 70
            |zeroconf_enabled: true
            |credentials:
            |  type: device_auth
            |server:
            |  enabled: true
            |  address: 127.0.0.1
            |  port: $API_PORT
            |  image_size: large
            |""".trimMargin(),
        )
    }

    private fun daemonLoop(dir: File) {
        val bin = File(applicationInfo.nativeLibraryDir, "libgolibrespot.so")
        var delay = 2_000L
        while (running) {
            if (!bin.exists()) {
                log("programme Spotify absent de l'APK")
                return
            }
            val started = System.currentTimeMillis()
            try {
                val pb = ProcessBuilder(bin.path, "--config_dir", dir.path).redirectErrorStream(true)
                pb.environment()["HOME"] = dir.path
                pb.environment()["XDG_CACHE_HOME"] = File(cacheDir, "spotify").path
                pb.environment()["XDG_CONFIG_HOME"] = dir.path
                pb.environment()["XDG_STATE_HOME"] = dir.path
                val p = pb.start()
                process = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(LAST) {
                        LAST += line.take(300)
                        while (LAST.size > 60) LAST.removeAt(0)
                    }
                    if (line.contains("level=error") || line.contains("level=fatal") || line.contains("authenticated")) log(line.take(200))
                }
                p.waitFor()
            } catch (e: Exception) {
                log("arrêt : $e")
            }
            process = null
            if (!running) break
            // Relance avec un délai croissant si le programme s'arrête tout de suite.
            delay = if (System.currentTimeMillis() - started > 60_000) 2_000L else minOf(delay * 2, 60_000L)
            Thread.sleep(delay)
        }
    }

    /** Lit le son du tube et le joue ; recommence à chaque nouvelle lecture. */
    private fun audioLoop(fifo: File) {
        val rate = 44_100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 4, 64 * 1024)
        while (running) {
            var track: AudioTrack? = null
            try {
                FileInputStream(fifo).use { input -> // bloque jusqu'à l'ouverture côté Spotify
                    @Suppress("DEPRECATION")
                    val t = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
                    track = t
                    t.play()
                    val buf = ByteArray(8192)
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) t.write(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                if (running) Thread.sleep(1_000)
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                track?.release()
            }
        }
    }

    private fun log(s: String) = ToqueApp.of(this).log.write("spotify", s)

    override fun onDestroy() {
        running = false
        process?.destroy()
        multicast?.release()
        super.onDestroy()
    }

    companion object {
        const val API_PORT = 3678
        /** Dernières lignes du programme (affichées en cas de souci). */
        val LAST = ArrayList<String>()

        fun dir(c: Context) = File(c.filesDir, "spotify")

        fun start(c: Context) {
            if (Prefs(c).spotifyEnabled) c.startService(Intent(c, SpotifyService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, SpotifyService::class.java))
        }

        /** Oublie le compte associé (nouvel appairage au prochain démarrage). */
        fun unlink(c: Context) {
            stop(c)
            File(dir(c), "state.json").delete()
            File(dir(c), "credentials.json").delete()
            Handler(Looper.getMainLooper()).postDelayed({ start(c) }, 1500)
        }
    }
}

/** Accès à l'API locale de go-librespot. */
object SpotifyApi {
    class Track(val name: String, val artists: String, val album: String, val cover: String?, val position: Long, val duration: Long)
    class Status(val active: Boolean, val user: String, val paused: Boolean, val stopped: Boolean, val volume: Int, val volumeMax: Int,
                 val track: Track?, val context: String)
    class Pairing(val url: String, val code: String)

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${SpotifyService.API_PORT}$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1500
            c.readTimeout = 4000
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            } else if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** null : le programme Spotify ne répond pas (pas encore démarré). */
    fun status(): Status? {
        val (code, text) = try {
            call("GET", "/status")
        } catch (_: Exception) {
            return null
        }
        if (code == 204 || text.isBlank()) return Status(false, "", true, true, 0, 100, null, "")
        if (code != 200) return null
        val m = Json.parse(text).obj()
        val t = m["track"]?.obj()?.takeIf { it.isNotEmpty() }?.let {
            Track(it.str("name").orEmpty(), it["artist_names"].list().mapNotNull { a -> a as? String }.joinToString(", "),
                it.str("album_name").orEmpty(), it.str("album_cover_url"),
                (it["position"] as? Number)?.toLong() ?: 0, (it["duration"] as? Number)?.toLong() ?: 0)
        }
        return Status(true, m.str("username").orEmpty(), m.bool("paused") ?: false, m.bool("stopped") ?: true,
            m.int("volume") ?: 0, m.int("volume_steps") ?: 100, t, m.str("context_name").orEmpty())
    }

    fun pairing(): Pairing? = try {
        val (code, text) = call("GET", "/auth/code")
        if (code != 200) null else Json.parse(text).obj().let { Pairing(it.str("url").orEmpty(), it.str("code").orEmpty()) }
    } catch (_: Exception) {
        null
    }

    fun playPause() = post("/player/playpause")
    fun next() = post("/player/next")
    fun prev() = post("/player/prev")
    fun volume(delta: Int) = post("/player/volume", """{"volume":$delta,"relative":true}""")

    private fun post(path: String, body: String? = null) = try {
        call("POST", path, body).first in 200..299
    } catch (_: Exception) {
        false
    }
}
