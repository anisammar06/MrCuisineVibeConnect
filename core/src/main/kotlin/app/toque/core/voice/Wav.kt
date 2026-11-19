package app.toque.core.voice

/** Fichiers WAV PCM 16 bits mono (phrase envoyée à la transcription). */
object Wav {
    /** Remonte le volume pour que le pic atteigne ~90 % (gain borné), sans saturer. */
    fun normalize(pcm: ShortArray, maxGain: Int = 32): ShortArray {
        var peak = 1
        for (v in pcm) peak = maxOf(peak, Math.abs(v.toInt()))
        val gain = (29_500.0 / peak).coerceIn(1.0, maxGain.toDouble())
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun encode(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2
        val out = ByteArray(44 + data)
        fun str(at: Int, s: String) = s.forEachIndexed { i, ch -> out[at + i] = ch.code.toByte() }
        fun i32(at: Int, v: Int) { for (k in 0 until 4) out[at + k] = (v shr (8 * k)).toByte() }
        fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + data); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        str(36, "data"); i32(40, data)
        for (i in pcm.indices) i16(44 + i * 2, pcm[i].toInt())
        return out
    }
}
