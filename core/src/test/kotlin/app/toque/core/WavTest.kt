package app.toque.core

import app.toque.core.voice.Wav
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test
    fun header() {
        val b = Wav.encode(shortArrayOf(1, -2, 300), 16_000)
        assertEquals(50, b.size)
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals("data", String(b, 36, 4))
        assertEquals(6, b[40].toInt())
        assertEquals(300, (b[48].toInt() and 0xFF) or (b[49].toInt() shl 8))
    }

    @Test
    fun normalizeQuietVoice() {
        val n = Wav.normalize(shortArrayOf(1000, -500))
        assertEquals(29_500, n[0].toInt())
        assertEquals(-14_750, n[1].toInt())
        assertEquals(3_200, Wav.normalize(shortArrayOf(100))[0].toInt()) // gain borné à 32
    }
}
