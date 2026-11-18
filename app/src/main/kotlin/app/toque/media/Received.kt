package app.toque.media

import android.content.Context
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** Fichiers reçus depuis le téléphone : dossier dédié, tri, ménage, type de contenu. */
object Received {
    enum class Kind { VIDEO, AUDIO, IMAGE, OTHER }

    private val VIDEO = setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "Recus").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun kindOf(name: String, mime: String = ""): Kind {
        val m = mime.lowercase()
        if (m.startsWith("video/")) return Kind.VIDEO
        if (m.startsWith("audio/")) return Kind.AUDIO
        if (m.startsWith("image/")) return Kind.IMAGE
        val ext = name.substringAfterLast('.', "").lowercase().substringBefore('?')
        return when (ext) {
            in VIDEO -> Kind.VIDEO
            in AUDIO -> Kind.AUDIO
            in IMAGE -> Kind.IMAGE
            else -> Kind.OTHER
        }
    }

    /** Nom de fichier sûr (pas de chemin), unique dans le dossier. */
    fun target(c: Context, wanted: String): File {
        val clean = wanted.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimStart('.').take(120).ifEmpty { "fichier" }
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var f = File(dir(c), clean)
        var i = 2
        while (f.exists()) f = File(dir(c), "$base ($i)$ext").also { i++ }
        return f
    }

    /** Efface les plus anciens fichiers au-delà de [maxMb] Mo (le plus récent est toujours gardé). */
    fun trim(c: Context, maxMb: Int) {
        val max = maxMb.toLong() * 1024 * 1024
        var total = 0L
        for ((i, f) in list(c).withIndex()) {
            total += f.length()
            if (i > 0 && total > max) f.delete()
        }
    }

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.FRANCE, "%.1f Go", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / 1048576.0)
        else -> "${(bytes + 1023) / 1024} Ko"
    }

    /** Adresse IPv4 du robot sur le Wi-Fi (affichée dans les réglages, utile en secours). */
    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: ""
    } catch (_: Exception) {
        ""
    }
}
