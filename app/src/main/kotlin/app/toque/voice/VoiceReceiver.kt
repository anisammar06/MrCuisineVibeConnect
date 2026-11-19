package app.toque.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.toque.ToqueApp
import app.toque.assistant.Assistant
import app.toque.core.i18n.tr
import app.toque.core.kitchen.TimerBoard
import app.toque.core.voice.VoiceCommand
import app.toque.kitchen.Timers
import app.toque.media.MediaRemote
import app.toque.ui.MainActivity
import app.toque.ui.screens.CookScreen
import java.io.File

/**
 * Reçoit les commandes du processus d'écoute et les exécute dans l'appli.
 * Sécurité : la voix peut mettre en pause ou arrêter une cuisson, jamais démarrer le moteur.
 */
class VoiceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val heard = i.getStringExtra(EXTRA_TEXT).orEmpty()
        when (i.getStringExtra(EXTRA_KIND)) {
            LISTENING -> feedback(c, tr("J'écoute…"))
            UNKNOWN -> feedback(c, tr("Pas compris : « %s »", heard))
            COMMAND -> execute(c, decode(i) ?: return, heard)
            ASK -> Assistant.ask(c, File(i.getStringExtra(EXTRA_FILE) ?: return), i.getStringExtra(EXTRA_LANG) ?: "fr")
        }
    }

    private fun execute(c: Context, cmd: VoiceCommand, heard: String) {
        val app = ToqueApp.of(c)
        val snap = app.machine.snapshot
        val cooking = snap?.busy == true
        val cook = MainActivity.instance?.current as? CookScreen
        when (cmd) {
            is VoiceCommand.Timer -> {
                Timers.edit(c) { b, now -> b.start(cmd.name, cmd.seconds * 1000L, now) }
                feedback(c, tr("Minuteur « %s » : %s", cmd.name, TimerBoard.format(cmd.seconds * 1000L)))
            }
            VoiceCommand.StopTimer -> {
                val n = Timers.edit(c) { b, now ->
                    val ringing = b.ringing(now)
                    if (ringing.isNotEmpty()) { ringing.forEach { b.dismiss(it.id) }; ringing.size }
                    else b.timers.filter { !it.finished(now) }.maxByOrNull { it.endAt }?.let { b.remove(it.id); 1 } ?: 0
                }
                feedback(c, if (n > 0) tr("Minuteur arrêté") else tr("Aucun minuteur en cours"))
            }
            VoiceCommand.NextStep, VoiceCommand.PrevStep -> stepOrSay(c, cook, cmd == VoiceCommand.NextStep)
            VoiceCommand.Next -> if (cook != null) stepOrSay(c, cook, true) else MediaRemote.control(c, MediaRemote.Action.NEXT)
            VoiceCommand.Prev -> if (cook != null) stepOrSay(c, cook, false) else MediaRemote.control(c, MediaRemote.Action.PREV)
            VoiceCommand.NextTrack -> MediaRemote.control(c, MediaRemote.Action.NEXT)
            VoiceCommand.PrevTrack -> MediaRemote.control(c, MediaRemote.Action.PREV)
            VoiceCommand.Pause -> if (snap?.running == true) {
                app.machine.pause()
                feedback(c, tr("Cuisson en pause"))
            } else MediaRemote.control(c, MediaRemote.Action.PAUSE)
            VoiceCommand.Stop -> {
                val ringing = Timers.get(c).ringing(System.currentTimeMillis())
                when {
                    ringing.isNotEmpty() -> Timers.edit(c) { b, _ -> ringing.forEach { b.dismiss(it.id) } }
                    cooking -> {
                        app.machine.stop()
                        feedback(c, tr("Cuisson arrêtée"))
                    }
                    else -> MediaRemote.control(c, MediaRemote.Action.PAUSE)
                }
            }
            VoiceCommand.Play -> MediaRemote.control(c, MediaRemote.Action.PLAY)
            VoiceCommand.VolumeUp -> MediaRemote.control(c, MediaRemote.Action.VOLUME_UP)
            VoiceCommand.VolumeDown -> MediaRemote.control(c, MediaRemote.Action.VOLUME_DOWN)
            VoiceCommand.Wake -> feedback(c, tr("J'écoute…"))
        }
        app.log.write("voix", "« $heard » → ${cmd.javaClass.simpleName}")
    }

    private fun stepOrSay(c: Context, cook: CookScreen?, next: Boolean) {
        if (cook == null) feedback(c, tr("Aucune recette guidée à l'écran"))
        else Handler(Looper.getMainLooper()).post { if (next) cook.voiceNext() else cook.voicePrev() }
    }

    private fun feedback(c: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            val a = MainActivity.instance
            if (a != null && a.resumed) a.toast(text) else Toast.makeText(c.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION = "app.toque.VOIX"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SECONDS = "seconds"
        const val LISTENING = "listening"
        const val UNKNOWN = "unknown"
        const val COMMAND = "command"
        const val ASK = "ask"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_LANG = "lang"

        /** Phrase libre enregistrée, à transmettre à l'assistant (processus principal). */
        fun ask(c: Context, file: String, lang: String) {
            c.sendBroadcast(Intent(c, VoiceReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KIND, ASK)
                .putExtra(EXTRA_FILE, file).putExtra(EXTRA_LANG, lang))
        }

        fun send(c: Context, kind: String, text: String) {
            c.sendBroadcast(Intent(c, VoiceReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_TEXT, text))
        }

        fun sendCommand(c: Context, cmd: VoiceCommand, text: String) {
            val i = Intent(c, VoiceReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KIND, COMMAND).putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_CMD, cmd.javaClass.simpleName)
            if (cmd is VoiceCommand.Timer) i.putExtra(EXTRA_NAME, cmd.name).putExtra(EXTRA_SECONDS, cmd.seconds)
            c.sendBroadcast(i)
        }

        private fun decode(i: Intent): VoiceCommand? = when (i.getStringExtra(EXTRA_CMD)) {
            "Timer" -> VoiceCommand.Timer(i.getStringExtra(EXTRA_NAME).orEmpty(), i.getIntExtra(EXTRA_SECONDS, 0))
            "StopTimer" -> VoiceCommand.StopTimer
            "NextStep" -> VoiceCommand.NextStep
            "PrevStep" -> VoiceCommand.PrevStep
            "Next" -> VoiceCommand.Next
            "Prev" -> VoiceCommand.Prev
            "NextTrack" -> VoiceCommand.NextTrack
            "PrevTrack" -> VoiceCommand.PrevTrack
            "Pause" -> VoiceCommand.Pause
            "Stop" -> VoiceCommand.Stop
            "Play" -> VoiceCommand.Play
            "VolumeUp" -> VoiceCommand.VolumeUp
            "VolumeDown" -> VoiceCommand.VolumeDown
            "Wake" -> VoiceCommand.Wake
            else -> null
        }
    }
}
