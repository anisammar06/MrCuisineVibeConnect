package app.toque.send

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Le robot « Gotuj z Kasią » vu du téléphone : adresse, code d'appairage, envois. */
class Robot(private val context: Context) {
    private val prefs = context.getSharedPreferences("robot", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", PORT)
        set(v) = prefs.edit().putInt("port", v).apply()

    var code: String
        get() = prefs.getString("code", "") ?: ""
        set(v) = prefs.edit().putString("code", v.trim()).apply()

    val configured: Boolean get() = host.isNotEmpty() && code.length == 6

    class Result(val ok: Boolean, val message: String)

    /** Cherche le robot sur le Wi-Fi (mDNS « _gotuj._tcp »), réponse sur le fil principal. */
    fun discover(timeoutMs: Long = 6_000, found: (host: String?, port: Int) -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = Handler(Looper.getMainLooper())
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish(h: String?, p: Int) {
            if (done) return
            done = true
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
            main.post { found(h, p) }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = finish(null, 0)
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        finish(i.host?.hostAddress, i.port)
                    }
                })
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            main.post { found(null, 0) }
            return
        }
        main.postDelayed({ finish(null, 0) }, timeoutMs)
    }

    private fun url(path: String) = URL("http://$host:$port$path")

    private fun call(path: String, body: ByteArray?, contentType: String = "text/plain; charset=utf-8"): Result {
        val c = url(path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 20_000
            c.setRequestProperty("X-Code", code)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            } else c.requestMethod = "POST".takeIf { path != "/info" } ?: "GET"
            return answer(c)
        } catch (e: IOException) {
            return Result(false, "Robot injoignable (${e.message ?: e.javaClass.simpleName}). Même Wi-Fi ? Robot allumé ?")
        } finally {
            c.disconnect()
        }
    }

    private fun answer(c: HttpURLConnection): Result {
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        val message = try {
            JSONObject(text).optString("message", text)
        } catch (_: Exception) {
            text
        }
        return Result(code == 200, if (code == 401) "Code d'appairage incorrect" else message.ifEmpty { "Réponse $code" })
    }

    fun check(): Result = call("/check", ByteArray(0))

    fun sendLink(text: String): Result = call("/link", text.toByteArray(Charsets.UTF_8))

    /** Envoie un fichier du téléphone ; [progress] reçoit le pourcentage (hors fil principal). */
    fun sendFile(uri: Uri, progress: (Int) -> Unit): Result {
        var name = uri.lastPathSegment ?: "fichier"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size <= 0) {
            size = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }
        if (size <= 0) return Result(false, "Taille du fichier inconnue")
        val mime = context.contentResolver.getType(uri) ?: ""
        val path = "/file?name=" + URLEncoder.encode(name, "UTF-8") + "&mime=" + URLEncoder.encode(mime, "UTF-8")
        val c = url(path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 120_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("X-Code", code)
            c.setRequestProperty("Content-Type", mime.ifEmpty { "application/octet-stream" })
            c.setFixedLengthStreamingMode(size)
            val input = context.contentResolver.openInputStream(uri) ?: return Result(false, "Fichier illisible")
            input.use { inp ->
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val pct = (sent * 100 / size).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            return answer(c)
        } catch (e: IOException) {
            return Result(false, "Envoi interrompu (${e.message ?: e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val PORT = 8770
        const val SERVICE_TYPE = "_gotuj._tcp."
    }
}
