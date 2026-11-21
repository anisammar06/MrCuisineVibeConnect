package app.toque.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Chargement d'images économe en mémoire (sous-échantillonnage + cache) pour 1 Go de RAM. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val exec = Executors.newFixedThreadPool(2) { r -> Thread(r, "toque-images").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, file: File?, targetWidth: Int) {
        view.tag = file?.absolutePath
        view.setImageDrawable(null)
        if (file == null) return
        val key = file.absolutePath + "@" + targetWidth
        cache.get(key)?.let { view.setImageBitmap(it); return }
        exec.execute {
            val bmp = decode(file, targetWidth) ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == file.absolutePath) view.setImageBitmap(bmp) }
        }
    }

    /** Photo web (étapes des recettes polonaises), gardée en cache disque après le premier affichage. */
    fun loadUrl(view: ImageView, url: String, targetWidth: Int) {
        val dir = File(view.context.cacheDir, "web-img").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        if (f.exists()) { load(view, f, targetWidth); return }
        view.tag = f.absolutePath
        view.setImageDrawable(null)
        exec.execute {
            try {
                val conn = java.net.URL(url).openConnection()
                if (conn is javax.net.ssl.HttpsURLConnection) conn.sslSocketFactory = app.toque.recipes.CookidooClient.sslFactory(view.context)
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) Toque")
                val bytes = conn.getInputStream().use { it.readBytes() }
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            } catch (_: Exception) {
                return@execute
            }
            main.post { if (view.tag == f.absolutePath) load(view, f, targetWidth) }
        }
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Throwable) {
        null
    }
}
