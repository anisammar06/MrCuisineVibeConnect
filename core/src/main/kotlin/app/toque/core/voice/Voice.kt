package app.toque.core.voice

/**
 * Commandes vocales de la cuisine, en français et en polonais.
 *
 * Le moteur de reconnaissance (hors ligne) ne cherche que les mots de [grammar] : il ne transcrit
 * pas les conversations, il reconnaît des commandes courtes. Le mot d'appel (« robot ») évite les
 * déclenchements involontaires. Le moteur du robot ne démarre JAMAIS à la voix : seules la pause
 * et l'arrêt agissent sur la cuisson.
 */
sealed class VoiceCommand {
    /** Mot d'appel seul : le robot écoute la commande qui suit. */
    object Wake : VoiceCommand()
    data class Timer(val name: String, val seconds: Int) : VoiceCommand()
    object StopTimer : VoiceCommand()
    /** Étape suivante / précédente d'une recette (explicite : « étape », « krok »). */
    object NextStep : VoiceCommand()
    object PrevStep : VoiceCommand()
    /** « suivant » seul : étape si une recette guidée est affichée, sinon morceau suivant. */
    object Next : VoiceCommand()
    object Prev : VoiceCommand()
    /** Morceau suivant / précédent (explicite : « musique », « chanson »…). */
    object NextTrack : VoiceCommand()
    object PrevTrack : VoiceCommand()
    object Pause : VoiceCommand()
    object Stop : VoiceCommand()
    object Play : VoiceCommand()
    object VolumeUp : VoiceCommand()
    object VolumeDown : VoiceCommand()
}

object Voice {
    val WAKE = mapOf("fr" to listOf("robot"), "pl" to listOf("robot", "robocie", "robota"))

    private val FR_UNITS = mapOf(
        "zéro" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15,
        "seize" to 16, "vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
    )
    private val PL_UNITS = mapOf(
        "zero" to 0, "jeden" to 1, "jedna" to 1, "jedną" to 1, "jedno" to 1, "dwa" to 2, "dwie" to 2, "trzy" to 3, "cztery" to 4,
        "pięć" to 5, "sześć" to 6, "siedem" to 7, "osiem" to 8, "dziewięć" to 9, "dziesięć" to 10, "jedenaście" to 11,
        "dwanaście" to 12, "trzynaście" to 13, "czternaście" to 14, "piętnaście" to 15, "szesnaście" to 16,
        "siedemnaście" to 17, "osiemnaście" to 18, "dziewiętnaście" to 19, "dwadzieścia" to 20, "trzydzieści" to 30,
        "czterdzieści" to 40, "pięćdziesiąt" to 50, "sześćdziesiąt" to 60, "siedemdziesiąt" to 70, "osiemdziesiąt" to 80,
        "dziewięćdziesiąt" to 90,
    )

    private val PL_HOURS = setOf("godzina", "godziny", "godzin", "godzinę")
    private val PL_MINUTES = setOf("minuta", "minuty", "minut", "minutę")
    private val PL_SECONDS = setOf("sekunda", "sekundy", "sekund", "sekundę")

    /** Noms de minuteur reconnus → nom affiché. */
    private val FR_NAMES = mapOf("pâtes" to "Pâtes", "riz" to "Riz", "four" to "Four", "œufs" to "Œufs", "oeufs" to "Œufs",
        "œuf" to "Œufs", "légumes" to "Légumes", "repos" to "Repos", "gâteau" to "Gâteau", "viande" to "Viande",
        "pomme" to "Pommes de terre", "pommes" to "Pommes de terre", "thé" to "Thé")
    private val PL_NAMES = mapOf("makaron" to "Makaron", "ryż" to "Ryż", "piekarnik" to "Piekarnik", "jajka" to "Jajka",
        "jajko" to "Jajka", "warzywa" to "Warzywa", "ciasto" to "Ciasto", "mięso" to "Mięso", "ziemniaki" to "Ziemniaki",
        "herbata" to "Herbata", "odpoczynek" to "Odpoczynek")

    private val FR_WORDS = listOf(
        "minuteur", "minuterie", "chrono", "minute", "minutes", "seconde", "secondes", "heure", "heures", "et", "demie", "demi",
        "arrête", "arrêter", "arrêtez", "stop", "annule", "annuler", "étape", "suivante", "suivant", "précédente", "précédent",
        "pause", "lecture", "reprends", "reprendre", "joue", "musique", "chanson", "morceau", "plus", "moins", "fort",
        "monte", "baisse", "le", "son", "volume", "la", "l'", "de", "du", "les", "pour",
    )
    private val PL_WORDS = listOf(
        "minutnik", "timer", "stoper", "minuta", "minuty", "minut", "minutę", "sekunda", "sekundy", "sekund", "sekundę",
        "godzina", "godziny", "godzin", "godzinę", "i", "pół", "półtorej", "wyłącz", "zatrzymaj", "stop", "anuluj", "stój",
        "następny", "następna", "następne", "dalej", "poprzedni", "poprzednia", "poprzednie", "wstecz", "cofnij", "krok",
        "pauza", "graj", "wznów", "odtwarzaj", "muzyka", "muzykę", "piosenka", "utwór", "głośniej", "ciszej", "na",
    )

    /** Mots que le moteur doit chercher (plus « [unk] » pour tout le reste). */
    fun grammar(lang: String): List<String> {
        val words = LinkedHashSet<String>()
        words += WAKE[lang].orEmpty()
        if (lang == "pl") {
            words += PL_WORDS; words += PL_UNITS.keys; words += PL_NAMES.keys
        } else {
            words += FR_WORDS; words += FR_UNITS.keys; words += FR_NAMES.keys
            // Le modèle peut écrire les nombres composés avec ou sans trait d'union.
            listOf("dix-sept", "dix-huit", "dix-neuf", "vingt-cinq", "quatre-vingt", "quatre-vingts").forEach { words += it }
        }
        words += "[unk]"
        return words.toList()
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').replace("-", " ").split(Regex("[\\s,.!?]+")).filter { it.isNotEmpty() && it != "[unk]" }

    /** Le texte commence-t-il par le mot d'appel ? Renvoie le reste de la phrase, ou null. */
    fun afterWake(text: String, lang: String): List<String>? {
        val t = tokens(text)
        val i = t.indexOfFirst { it in WAKE[lang].orEmpty() }
        if (i < 0) return null
        return t.drop(i + 1)
    }

    /** Commande reconnue dans une phrase (sans le mot d'appel), ou null. */
    fun parse(text: String, lang: String): VoiceCommand? = parseTokens(tokens(text), lang)

    fun parseTokens(t: List<String>, lang: String): VoiceCommand? {
        if (t.isEmpty()) return null
        val has = { w: String -> t.contains(w) }
        val any = { ws: List<String> -> ws.any { t.contains(it) } }
        return if (lang == "pl") {
            when {
                any(listOf("minutnik", "timer", "stoper")) || (durationPl(t) > 0 && !any(listOf("głośniej", "ciszej"))) ->
                    if (any(listOf("wyłącz", "zatrzymaj", "stop", "anuluj", "stój"))) VoiceCommand.StopTimer
                    else durationPl(t).takeIf { it > 0 }?.let { VoiceCommand.Timer(name(t, PL_NAMES) ?: "Minutnik", it) }
                any(listOf("krok", "dalej")) && any(listOf("następny", "następna", "następne", "dalej")) -> VoiceCommand.NextStep
                has("krok") && any(listOf("poprzedni", "poprzednia", "poprzednie", "wstecz", "cofnij")) -> VoiceCommand.PrevStep
                any(listOf("piosenka", "utwór", "muzyka", "muzykę")) && any(listOf("następny", "następna", "następne")) -> VoiceCommand.NextTrack
                any(listOf("piosenka", "utwór", "muzyka", "muzykę")) && any(listOf("poprzedni", "poprzednia", "poprzednie")) -> VoiceCommand.PrevTrack
                any(listOf("następny", "następna", "następne")) -> VoiceCommand.Next
                any(listOf("poprzedni", "poprzednia", "poprzednie", "wstecz", "cofnij")) -> VoiceCommand.Prev
                has("pauza") -> VoiceCommand.Pause
                any(listOf("stop", "zatrzymaj", "stój", "wyłącz")) -> VoiceCommand.Stop
                has("głośniej") -> VoiceCommand.VolumeUp
                has("ciszej") -> VoiceCommand.VolumeDown
                any(listOf("graj", "wznów", "odtwarzaj", "muzyka", "muzykę")) -> VoiceCommand.Play
                else -> null
            }
        } else {
            when {
                any(listOf("minuteur", "minuterie", "chrono")) ->
                    if (any(listOf("arrête", "arrêter", "arrêtez", "stop", "annule", "annuler"))) VoiceCommand.StopTimer
                    else durationFr(t).takeIf { it > 0 }?.let { VoiceCommand.Timer(name(t, FR_NAMES) ?: "Minuteur", it) }
                has("étape") && any(listOf("suivante", "suivant")) -> VoiceCommand.NextStep
                has("étape") && any(listOf("précédente", "précédent")) -> VoiceCommand.PrevStep
                any(listOf("musique", "chanson", "morceau")) && any(listOf("suivante", "suivant")) -> VoiceCommand.NextTrack
                any(listOf("musique", "chanson", "morceau")) && any(listOf("précédente", "précédent")) -> VoiceCommand.PrevTrack
                any(listOf("suivante", "suivant")) -> VoiceCommand.Next
                any(listOf("précédente", "précédent")) -> VoiceCommand.Prev
                has("pause") -> VoiceCommand.Pause
                any(listOf("stop", "arrête", "arrêter", "arrêtez")) -> VoiceCommand.Stop
                (has("plus") && has("fort")) || has("monte") -> VoiceCommand.VolumeUp
                (has("moins") && has("fort")) || has("baisse") -> VoiceCommand.VolumeDown
                any(listOf("lecture", "reprends", "reprendre", "joue", "musique")) -> VoiceCommand.Play
                durationFr(t) > 0 -> VoiceCommand.Timer(name(t, FR_NAMES) ?: "Minuteur", durationFr(t))
                else -> null
            }
        }
    }

    private fun name(t: List<String>, names: Map<String, String>): String? = t.firstNotNullOfOrNull { names[it] }

    /** « huit minutes », « une minute et demie », « vingt et une minutes trente secondes », « une heure ». */
    fun durationFr(t: List<String>): Int {
        var total = 0
        var n = 0
        var have = false
        var prev = ""
        for (w in t) {
            val v = FR_UNITS[w]
            when {
                v != null -> {
                    n = if ((w == "vingt" || w == "vingts") && prev == "quatre") n - 4 + 80 else n + v
                    have = true
                }
                (w == "heure" || w == "heures") -> { total += (if (have) n else 1) * 3600; n = 0; have = false }
                (w == "minute" || w == "minutes") -> { total += (if (have) n else 1) * 60; n = 0; have = false }
                (w == "seconde" || w == "secondes") -> { total += if (have) n else 1; n = 0; have = false }
                w == "demie" || w == "demi" -> total += if (prev == "et" && total >= 3600 && total % 3600 == 0) 1800 else 30
            }
            prev = w
        }
        // « minuteur cinq » : minutes par défaut.
        if (have && n > 0) total += n * 60
        return total
    }

    /** « pięć minut », « półtorej minuty », « pół godziny », « dwadzieścia pięć minut i trzydzieści sekund ». */
    fun durationPl(t: List<String>): Int {
        var total = 0
        var n = 0
        var have = false
        var half = false
        var oneAndHalf = false
        for (w in t) {
            val v = PL_UNITS[w]
            when {
                v != null -> { n += v; have = true }
                w == "pół" -> half = true
                w == "półtorej" -> oneAndHalf = true
                w in PL_HOURS -> { total += unit(n, have, half, oneAndHalf, 3600); n = 0; have = false; half = false; oneAndHalf = false }
                w in PL_MINUTES -> { total += unit(n, have, half, oneAndHalf, 60); n = 0; have = false; half = false; oneAndHalf = false }
                w in PL_SECONDS -> { total += unit(n, have, half, oneAndHalf, 1); n = 0; have = false; half = false; oneAndHalf = false }
            }
        }
        if (have && n > 0) total += n * 60
        return total
    }

    private fun unit(n: Int, have: Boolean, half: Boolean, oneAndHalf: Boolean, size: Int): Int = when {
        oneAndHalf -> size * 3 / 2
        half && !have -> size / 2
        have -> n * size + if (half) size / 2 else 0
        else -> size
    }
}
