package app.toque.assistant

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.toque.Prefs
import app.toque.ScreenPower
import app.toque.ToqueApp
import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.kitchen.Timers
import app.toque.ui.MainActivity
import app.toque.voice.VoiceService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Assistant conversationnel : phrase enregistrée → texte (OpenAI) → Claude avec des outils
 * (recettes, historique, minuteurs, courses, musique) → réponse lue à voix haute (OpenAI).
 * Sécurité : aucun outil ne démarre le moteur ; l'assistant peut seulement mettre en pause ou arrêter.
 */
object Assistant {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-assistant") }
    private val messages = ArrayList<Map<String, Any?>>()
    private var lastAt = 0L
    @Volatile var busy = false
        private set

    /** Phrase enregistrée par le processus d'écoute. */
    fun ask(c: Context, wav: File, lang: String, offline: () -> Boolean = { false }) {
        val app = c.applicationContext
        exec.execute { run(app, lang, offline) { transcribe(app, wav, lang).also { wav.delete() } } }
    }

    /** Question tapée (test depuis les réglages). */
    fun askText(c: Context, text: String, lang: String) {
        val app = c.applicationContext
        exec.execute { run(app, lang) { text } }
    }

    /** Lit une phrase (test de la voix). */
    fun say(c: Context, text: String) {
        val app = c.applicationContext
        exec.execute {
            VoiceService.pauseFor(app, 60_000)
            try { speak(app, text) } finally { VoiceService.resume(app, follow = false) }
        }
    }

    private fun run(c: Context, lang: String, offline: () -> Boolean = { false }, question: () -> String) {
        busy = true
        ScreenPower.wake(c, 20_000)
        var follow = false
        try {
            Bubble.show(c, "", pick(lang, "J'écoute…", "Słucham…"))
            val q = question().trim()
            if (q.isEmpty() || q.length < 2) {
                Bubble.show(c, "", pick(lang, "Je n'ai rien entendu.", "Nic nie usłyszałem."))
                Bubble.hideLater(4000)
                return
            }
            Bubble.show(c, q, pick(lang, "Je réfléchis…", "Myślę…"))
            val answer = converse(c, q, lang).ifBlank { pick(lang, "D'accord.", "Dobrze.") }
            Bubble.show(c, q, answer)
            ToqueApp.of(c).log.write("assistant", "« $q » → « ${answer.take(200)} »")
            speak(c, answer)
            follow = true
            Bubble.hideLater(15_000)
        } catch (e: Exception) {
            ToqueApp.of(c).log.write("assistant", "erreur : $e")
            if (e is IOException && offline()) {
                Bubble.hideLater(0)
                return
            }
            val msg = pick(lang, "Désolé, je n'arrive pas à joindre l'assistant.", "Przepraszam, nie mogę połączyć się z asystentem.")
            Bubble.show(c, "", msg + "\n" + (e.message ?: e.toString()).take(160))
            try { speakLocal(c, msg, lang) } catch (_: Exception) {}
            Bubble.hideLater(10_000)
        } finally {
            busy = false
            VoiceService.resume(c, follow)
        }
    }

    // ------------------------------------------------------------------ transcription (OpenAI)

    private fun transcribe(c: Context, wav: File, lang: String): String {
        val key = Prefs(c).openaiKey
        if (key.isBlank()) throw IOException("clé OpenAI manquante")
        val data = wav.readBytes()
        var last = ""
        for (model in listOf("gpt-transcribe", "gpt-4o-mini-transcribe")) {
            val name = Prefs(c).assistantName.replaceFirstChar { it.uppercase() }
            val body = Net.Multipart().field("model", model).field("language", lang)
                .field("prompt", if (lang == "pl") "$name, robot kuchenny, przepisy, minutnik." else "$name, robot de cuisine, recettes, minuteur.")
                .file("file", "question.wav", "audio/wav", data)
            val r = Net.post(c, "https://api.openai.com/v1/audio/transcriptions",
                mapOf("Authorization" to "Bearer $key"), body.bytes(), body.type, 45_000)
            if (r.code == 200) return Json.parse(r.text).obj().str("text").orEmpty()
            last = "transcription ${r.code} : ${apiError(r.text)}"
            if (r.code == 401 || r.code == 429) break // clé refusée ou quota : inutile d'essayer l'autre modèle
        }
        throw IOException(last)
    }

    // ------------------------------------------------------------------ conversation (Claude)

    private fun converse(c: Context, question: String, lang: String): String {
        val prefs = Prefs(c)
        val key = prefs.anthropicKey
        if (key.isBlank()) throw IOException("clé Anthropic manquante")
        val now = System.currentTimeMillis()
        if (now - lastAt > 15 * 60_000L) messages.clear()
        lastAt = now
        messages += mapOf("role" to "user", "content" to question)
        trim()
        val tools = Tools(c)
        repeat(6) {
            val body = linkedMapOf<String, Any?>(
                "model" to prefs.assistantModel,
                "max_tokens" to 1500,
                "system" to system(c, lang),
                "tools" to Tools.DEFINITIONS + webSearch(prefs),
                "messages" to messages,
            )
            val r = Net.post(c, "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                Json.write(body).toByteArray(Charsets.UTF_8), "application/json", 60_000)
            if (r.code != 200) {
                messages.removeAt(messages.size - 1)
                throw IOException("Claude ${r.code} : ${apiError(r.text)}")
            }
            val res = Json.parse(r.text).obj()
            val content = res["content"].list()
            messages += mapOf("role" to "assistant", "content" to content)
            val stop = res.str("stop_reason")
            // Recherche web longue côté serveur : on renvoie tel quel pour qu'il continue.
            if (stop == "pause_turn") return@repeat
            if (stop != "tool_use") {
                lastAt = System.currentTimeMillis()
                return content.map { it.obj() }.filter { it.str("type") == "text" }.joinToString(" ") { it.str("text").orEmpty() }.trim()
            }
            val results = ArrayList<Map<String, Any?>>()
            for (block in content.map { it.obj() }.filter { it.str("type") == "tool_use" }) {
                val out = try {
                    tools.run(block.str("name").orEmpty(), block["input"].obj())
                } catch (e: Exception) {
                    Json.write(mapOf("error" to (e.message ?: e.toString())))
                }
                results += linkedMapOf("type" to "tool_result", "tool_use_id" to block.str("id"), "content" to out)
            }
            messages += mapOf("role" to "user", "content" to results)
        }
        return pick(lang, "Je n'ai pas réussi à terminer, peux-tu reformuler ?", "Nie udało mi się, możesz powtórzyć inaczej?")
    }

    /** Recherche web exécutée par Anthropic (météo, actualité, tout sujet récent). */
    private fun webSearch(p: Prefs): Map<String, Any?> {
        val loc = linkedMapOf<String, Any?>("type" to "approximate", "timezone" to java.util.TimeZone.getDefault().id)
        if (p.assistantCity.isNotBlank()) loc["city"] = p.assistantCity
        return linkedMapOf("type" to "web_search_20250305", "name" to "web_search", "max_uses" to 3, "user_location" to loc)
    }

    /** Garde la conversation courte : on coupe au début d'une vraie question (pas un résultat d'outil). */
    private fun trim() {
        while (messages.size > 24) {
            messages.removeAt(0)
            while (messages.isNotEmpty() && !(messages[0]["role"] == "user" && messages[0]["content"] is String)) messages.removeAt(0)
        }
    }

    private fun system(c: Context, lang: String): String {
        val now = Date()
        val day = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE).format(now)
        val t = System.currentTimeMillis()
        val timers = Timers.get(c).timers.filter { !it.dismissed && it.leftMs(t) > 0 }
            .joinToString(", ") { "${it.name} (${it.leftMs(t) / 1000} s restantes${if (it.paused) ", en pause" else ""})" }
        val screen = MainActivity.instance?.current?.title.orEmpty()
        val snap = ToqueApp.of(c).machine.snapshot
        val machine = when {
            snap == null -> "inconnu"
            snap.running -> "programme en cours"
            snap.busy -> "occupé (pause ou fin de programme)"
            else -> "à l'arrêt"
        }
        val spoken = if (lang == "pl") "polonais" else "français"
        val city = Prefs(c).assistantCity.ifBlank { "inconnue (demande-la si besoin, ou utilise le fuseau horaire)" }
        val name = Prefs(c).assistantName.replaceFirstChar { it.uppercase() }
        return """Tu t'appelles $name. Tu es l'assistant vocal de la famille, intégré à leur robot de cuisine Monsieur Cuisine Connect (appli « Gotuj z Kasią »). Tu es un assistant généraliste : on peut te parler de n'importe quel sujet (cuisine, météo, actualité, culture, devoirs, conseils, calculs, traductions, discussion…) et tu réponds avec plaisir, comme Claude.
Ta réponse est LUE À VOIX HAUTE : phrases naturelles, pas de liste à puces, pas de markdown, pas d'emoji, pas d'URL ni d'identifiants. Sois bref par défaut (1 à 4 phrases) ; développe davantage seulement si on te le demande ou si le sujet l'exige, sans dépasser une minute de lecture. Pour proposer des plats, 2 ou 3 idées maximum, chacune en quelques mots.
Réponds dans la langue de la personne (on t'a parlé en $spoken ; si elle parle polonais, réponds en polonais).
La phrase vient d'une transcription automatique : elle peut contenir des fautes ou commencer par ton prénom « $name » ; devine le sens.
Pour la météo, l'actualité, les horaires, les prix, les résultats sportifs ou tout ce qui peut avoir changé, utilise la recherche web (web_search) plutôt que ta mémoire. Ville de la famille : $city.
Pour la cuisine, utilise les outils des recettes du robot (Lidl et Thermomix, français et polonais) et l'historique de ce qui a été cuisiné, pour proposer des choses variées (évite ce qui a été fait ces derniers jours, sauf demande). Quand la personne choisit une recette, ouvre-la à l'écran avec open_recipe. Tu peux aussi lancer des minuteurs, gérer la liste de courses et la musique.
SÉCURITÉ : tu ne peux jamais démarrer le moteur, chauffer ou lancer une étape ; c'est la personne qui appuie sur Démarrer à l'écran. Tu peux seulement mettre en pause ou arrêter.
Si tu poses une question, la personne peut répondre directement sans redire ton prénom.
Contexte : nous sommes le $day. Écran affiché : ${screen.ifEmpty { "accueil" }}. Robot : $machine. Minuteurs en cours : ${timers.ifEmpty { "aucun" }}."""
    }

    private fun apiError(body: String): String = try {
        Json.parse(body).obj()["error"].obj().str("message") ?: body.take(160)
    } catch (_: Exception) {
        body.take(160)
    }

    // ------------------------------------------------------------------ voix (OpenAI, sinon Android)

    private fun speak(c: Context, text: String) {
        val p = Prefs(c)
        val lang = if (Regex("[ąćęłńśźż]", RegexOption.IGNORE_CASE).containsMatchIn(text)) "pl" else p.voiceLang
        try {
            if (p.openaiKey.isBlank()) throw IOException("clé OpenAI manquante")
            val body = linkedMapOf(
                "model" to "gpt-4o-mini-tts",
                "voice" to p.assistantVoice,
                "input" to text.take(3000),
                "instructions" to if (lang == "pl") "Mów po polsku, ciepło i naturalnie, w żywym tempie, bez długich pauz." else "Parle en français, d'une voix chaleureuse et naturelle, à un rythme vif, sans longues pauses.",
                "response_format" to "mp3",
            )
            val r = Net.post(c, "https://api.openai.com/v1/audio/speech", mapOf("Authorization" to "Bearer ${p.openaiKey}"),
                Json.write(body).toByteArray(Charsets.UTF_8), "application/json", 45_000)
            if (r.code != 200) throw IOException("voix ${r.code} : ${apiError(r.text)}")
            val f = File(c.cacheDir, "reponse.mp3")
            f.writeBytes(r.body)
            play(c, f)
        } catch (e: Exception) {
            ToqueApp.of(c).log.write("assistant", "voix OpenAI indisponible ($e), voix d'Android")
            speakLocal(c, text, lang)
        }
    }

    private fun play(c: Context, f: File) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            val speed = Prefs(c).assistantSpeed.coerceIn(80, 200) / 100f
            if (speed != 1f) {
                try {
                    mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(1f)
                } catch (_: Exception) {
                }
            }
            mp.start()
            done.await(maxOf(5_000L, (mp.duration / speed).toLong() + 3_000), TimeUnit.MILLISECONDS)
        } finally {
            mp.release()
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    /** Synthèse vocale d'Android (hors ligne, voix moins naturelle) en secours. */
    private fun speakLocal(c: Context, text: String, lang: String) {
        val ready = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            tts = TextToSpeech(c) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        }
        if (!ready.await(8, TimeUnit.SECONDS) || !ok) {
            tts?.shutdown()
            return
        }
        val engine = tts ?: return
        try {
            engine.language = if (lang == "pl") Locale("pl", "PL") else Locale.FRANCE
            engine.setSpeechRate(Prefs(c).assistantSpeed.coerceIn(80, 200) / 100f)
            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("API 21")
                override fun onError(id: String?) = done.countDown()
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "toque")
            done.await(60, TimeUnit.SECONDS)
        } finally {
            engine.shutdown()
        }
    }

    private fun pick(lang: String, fr: String, pl: String) = if (lang == "pl") pl else fr
}
