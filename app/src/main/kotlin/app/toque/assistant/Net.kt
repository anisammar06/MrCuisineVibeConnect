package app.toque.assistant

import android.content.Context
import app.toque.recipes.CookidooClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Appels HTTPS des API (Anthropic, OpenAI), avec les certificats racines fournis par l'appli. */
internal object Net {
    class Reply(val code: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    fun post(c: Context, url: String, headers: Map<String, String>, body: ByteArray, type: String, timeoutMs: Int = 60_000): Reply {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = CookidooClient.sslFactory(c)
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 20 * 1024 * 1024) throw IOException("réponse trop grande")
                }
            }
            return Reply(code, out.toByteArray())
        } finally {
            conn.disconnect()
        }
    }

    /** Corps multipart/form-data : champs texte + un fichier. */
    class Multipart {
        val boundary = "toque" + System.nanoTime()
        private val out = ByteArrayOutputStream()

        fun field(name: String, value: String) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }

        fun file(name: String, filename: String, type: String, data: ByteArray) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }

        fun bytes(): ByteArray {
            out.write("--$boundary--\r\n".toByteArray())
            return out.toByteArray()
        }

        val type: String get() = "multipart/form-data; boundary=$boundary"
    }
}
