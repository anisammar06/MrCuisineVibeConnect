package app.toque.core

import app.toque.core.voice.Voice
import app.toque.core.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTest {
    private fun fr(s: String) = Voice.parse(s, "fr")
    private fun pl(s: String) = Voice.parse(s, "pl")

    @Test
    fun frenchTimers() {
        assertEquals(VoiceCommand.Timer("Pâtes", 480), fr("minuteur pâtes huit minutes"))
        assertEquals(VoiceCommand.Timer("Minuteur", 90), fr("minuteur une minute et demie"))
        assertEquals(VoiceCommand.Timer("Minuteur", 21 * 60 + 30), fr("minuteur vingt et une minutes trente secondes"))
        assertEquals(VoiceCommand.Timer("Minuteur", 17 * 60), fr("minuteur dix-sept minutes"))
        assertEquals(VoiceCommand.Timer("Four", 3600 + 1800), fr("minuteur four une heure et demie"))
        assertEquals(VoiceCommand.Timer("Minuteur", 300), fr("minuteur cinq"))
        assertEquals(VoiceCommand.StopTimer, fr("arrête le minuteur"))
    }

    @Test
    fun frenchControls() {
        assertEquals(VoiceCommand.NextStep, fr("étape suivante"))
        assertEquals(VoiceCommand.PrevStep, fr("étape précédente"))
        assertEquals(VoiceCommand.Next, fr("suivant"))
        assertEquals(VoiceCommand.NextTrack, fr("chanson suivante"))
        assertEquals(VoiceCommand.Pause, fr("pause"))
        assertEquals(VoiceCommand.Stop, fr("stop"))
        assertEquals(VoiceCommand.VolumeUp, fr("plus fort"))
        assertEquals(VoiceCommand.VolumeDown, fr("baisse le son"))
        assertEquals(VoiceCommand.Play, fr("lecture"))
        assertNull(fr("le la de"))
    }

    @Test
    fun polish() {
        assertEquals(VoiceCommand.Timer("Makaron", 480), pl("minutnik makaron osiem minut"))
        assertEquals(VoiceCommand.Timer("Minutnik", 90), pl("minutnik półtorej minuty"))
        assertEquals(VoiceCommand.Timer("Minutnik", 1800), pl("minutnik pół godziny"))
        assertEquals(VoiceCommand.Timer("Minutnik", 25 * 60 + 30), pl("minutnik dwadzieścia pięć minut i trzydzieści sekund"))
        assertEquals(VoiceCommand.StopTimer, pl("wyłącz minutnik"))
        assertEquals(VoiceCommand.NextStep, pl("następny krok"))
        assertEquals(VoiceCommand.PrevStep, pl("poprzedni krok"))
        assertEquals(VoiceCommand.Pause, pl("pauza"))
        assertEquals(VoiceCommand.VolumeUp, pl("głośniej"))
        assertEquals(VoiceCommand.Play, pl("graj"))
    }

    @Test
    fun wakeWord() {
        assertEquals(listOf("pause"), Voice.afterWake("robot pause", "fr"))
        assertEquals(emptyList<String>(), Voice.afterWake("robot", "fr"))
        assertNull(Voice.afterWake("pause", "fr"))
        assertEquals(listOf("pauza"), Voice.afterWake("robocie pauza", "pl"))
    }
}
