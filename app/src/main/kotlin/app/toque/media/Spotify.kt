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
            |metadata:
            |  enabled: true
            |  max_tracks: 500
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

    /** Élément de bibliothèque ou de recherche : playlist, album, artiste ou titre. */
    class Item(val uri: String, val name: String, val sub: String, val image: String?) {
        val kind: String get() = uri.split(':').getOrNull(1).orEmpty()
    }

    class Tracks(val ready: Boolean, val complete: Boolean, val items: List<Item>)

    class Results(val tracks: List<Item>, val artists: List<Item>, val albums: List<Item>, val playlists: List<Item>)

    const val LIKED = "spotify:collection:tracks"

    private fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 4000): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${SpotifyService.API_PORT}$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1500
            c.readTimeout = timeoutMs
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

    private fun names(v: Any?) = v.list().mapNotNull { it as? String }.joinToString(", ")

    private fun page(path: String, sub: (Map<String, Any?>) -> String): List<Item>? = try {
        val (code, text) = call("GET", path, timeoutMs = 20_000)
        if (code != 200) null else Json.parse(text).obj()["items"].list().map { it.obj() }.map {
            Item(it.str("uri").orEmpty(), it.str("name").orEmpty(), sub(it), it.str("image_url"))
        }
    } catch (_: Exception) {
        null
    }

    /** Playlists de la bibliothèque (null : pas de session). */
    fun playlists(): List<Item>? = page("/library/playlists?limit=500") { m ->
        val n = (m["length"] as? Number)?.toInt() ?: 0
        listOf(m.str("owner_username").orEmpty().let { if (it == "spotify") "Spotify" else it }, if (n > 0) "$n titres" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun albums(): List<Item>? = page("/library/albums?limit=500") { m ->
        listOf(names(m["artist_names"]), (m["year"] as? Number)?.toInt()?.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun artists(): List<Item>? = page("/library/artists?limit=500") { "" }

    /** Titres d'une playlist, d'un album, d'un artiste ou des titres likés (rempli en arrière-plan). */
    fun tracks(uri: String): Tracks? = try {
        val (code, text) = call("GET", "/context/tracks?uri=" + java.net.URLEncoder.encode(uri, "UTF-8"), timeoutMs = 10_000)
        if (code != 200) null else {
            val m = Json.parse(text).obj()
            val items = m["tracks"].list().map { it.obj() }.map { e ->
                val t = e["track"]?.obj()?.takeIf { it.isNotEmpty() }
                Item(e.str("uri").orEmpty(), t?.str("name") ?: "…", t?.let { names(it["artist_names"]) }.orEmpty(), t?.str("album_cover_url"))
            }
            Tracks(m.bool("ready") ?: false, (m.int("cached") ?: 0) >= (m.int("length") ?: 0), items)
        }
    } catch (_: Exception) {
        null
    }

    /** Lance une playlist / un album / un artiste / un titre, éventuellement à partir d'un titre précis. */
    fun play(uri: String, skipTo: String? = null): Boolean = post("/player/play", Json.write(linkedMapOf<String, Any?>("uri" to uri).apply {
        if (!skipTo.isNullOrBlank()) put("skip_to_uri", skipTo)
    }))

    fun shuffle(on: Boolean) = post("/player/shuffle_context", """{"shuffle_context":$on}""")

    private fun token(): String? = try {
        val (code, text) = call("POST", "/token")
        if (code != 200) null else Json.parse(text).obj().str("token")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private var appToken: String? = null
    private var appTokenUntil = 0L

    /**
     * Jeton de l'appli développeur de l'utilisateur (identifiants client) : la recherche avec le jeton
     * de l'enceinte est presque toujours refusée par Spotify (quota partagé, erreur 429).
     */
    @Synchronized
    private fun appToken(c: android.content.Context): String? {
        val p = Prefs(c)
        if (p.spotifyClientId.isBlank() || p.spotifyClientSecret.isBlank()) return null
        if (appToken != null && System.currentTimeMillis() < appTokenUntil) return appToken
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as javax.net.ssl.HttpsURLConnection
        return try {
            conn.sslSocketFactory = app.toque.recipes.CookidooClient.sslFactory(c)
            conn.connectTimeout = 8000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            val basic = android.util.Base64.encodeToString("${p.spotifyClientId.trim()}:${p.spotifyClientSecret.trim()}".toByteArray(), android.util.Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $basic")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
            if (conn.responseCode != 200) return null
            val m = Json.parse(conn.inputStream.bufferedReader().readText()).obj()
            appToken = m.str("access_token")
            appTokenUntil = System.currentTimeMillis() + ((m.int("expires_in") ?: 3600) - 120) * 1000L
            appToken
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Vrai si la recherche a ses identifiants (appli développeur Spotify). */
    fun canSearch(c: android.content.Context) = Prefs(c).let { it.spotifyClientId.isNotBlank() && it.spotifyClientSecret.isNotBlank() }

    /** Recherche dans tout le catalogue Spotify (API Web). */
    fun search(c: android.content.Context, q: String, limit: Int = 10): Results? {
        val tok = appToken(c) ?: token() ?: return null
        val url = "https://api.spotify.com/v1/search?type=track,artist,album,playlist&market=from_token&limit=$limit&q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
        val conn = URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        return try {
            conn.sslSocketFactory = app.toque.recipes.CookidooClient.sslFactory(c)
            conn.connectTimeout = 8000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $tok")
            if (conn.responseCode != 200) return null
            val m = Json.parse(conn.inputStream.bufferedReader().readText()).obj()
            fun img(o: Map<String, Any?>) = o["images"].list().firstOrNull()?.obj()?.str("url")
            fun list(key: String, f: (Map<String, Any?>) -> Item) = m[key]?.obj()?.get("items").list().mapNotNull { it as? Map<*, *> }
                .map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }.map(f)
            Results(
                list("tracks") { t -> Item(t.str("uri").orEmpty(), t.str("name").orEmpty(),
                    t["artists"].list().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.joinToString(", "), t["album"]?.obj()?.let { img(it) }) },
                list("artists") { t -> Item(t.str("uri").orEmpty(), t.str("name").orEmpty(), "", img(t)) },
                list("albums") { t -> Item(t.str("uri").orEmpty(), t.str("name").orEmpty(),
                    t["artists"].list().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.joinToString(", "), img(t)) },
                list("playlists") { t -> Item(t.str("uri").orEmpty(), t.str("name").orEmpty(), t["owner"]?.obj()?.str("display_name").orEmpty(), img(t)) },
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
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
