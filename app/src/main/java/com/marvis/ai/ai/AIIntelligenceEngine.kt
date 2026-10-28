package com.marvis.ai.ai

import com.marvis.ai.memory.MemoryManager
import java.util.Locale

/** Languages Marvis can speak and answer in. HINGLISH is Hindi in Roman letters (spoken with the hi-IN voice). */
enum class Lang(val code: String, val ttsTag: String) {
    EN("en", "en-US"), HI("hi", "hi-IN"), HINGLISH("hi", "hi-IN"), JA("ja", "ja-JP"), KO("ko", "ko-KR"),
    ES("es", "es-ES"), FR("fr", "fr-FR"), DE("de", "de-DE");

    val locale: Locale get() = Locale.forLanguageTag(ttsTag)

    companion object {
        fun fromCode(c: String?): Lang? = when (c?.lowercase()?.take(2)) {
            "en" -> EN; "hi" -> HI; "ja" -> JA; "ko" -> KO; "es" -> ES; "fr" -> FR; "de" -> DE; else -> null
        }
    }
}

/** Short fixed lines Marvis says itself, in the active language. */
enum class Phrase { THINK, CANT_HEAR, SAVED, YES, REPEAT_ASK, HOW_MANY, SECRET_PROMPT }

object LanguageHelper {
    private val HINGLISH_WORDS = setOf(
        "hai", "hain", "kya", "nahi", "nhi", "nahin", "karo", "mujhe", "mera", "meri", "mere", "tum", "tumhe",
        "aap", "bhai", "yaar", "yeh", "kaise", "kaisa", "kyun", "kyu", "kab", "kahan", "batao", "bolo", "chalo",
        "acha", "accha", "theek", "thik", "haan", "abhi", "aaj", "kal", "raha", "rahi", "hoon", "hun", "kitna",
        "wala", "wali", "samajh", "sunao", "dikhao", "kholo", "chalu", "mausam", "khabar", "samachar", "yaad")
    private val EN_WORDS = setOf("the", "is", "are", "what", "you", "and", "how", "can", "please", "who", "why", "tell")

    /** Script first (Devanagari, kana/kanji, hangul), then Hinglish words, else keep the previous Latin language. */
    fun detect(text: String, previous: Lang): Lang {
        var dev = 0; var ja = 0; var ko = 0; var latin = 0
        for (c in text) when {
            c in '\u0900'..'\u097F' -> dev++
            c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' -> ja++
            c in '\uAC00'..'\uD7AF' -> ko++
            c.isLetter() -> latin++
        }
        if (dev > 0 && dev >= latin) return Lang.HI
        if (ja > 0 && ja >= latin / 2) return Lang.JA
        if (ko > 0) return Lang.KO
        if (latin == 0) return previous
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.any { it in HINGLISH_WORDS }) return Lang.HINGLISH
        val seemsEnglish = words.any { it in EN_WORDS }
        if (!seemsEnglish && previous in setOf(Lang.ES, Lang.FR, Lang.DE)) return previous   // stay in the Latin language we were using
        return Lang.EN
    }

    /** Language of a reply from its script alone (used when the model forgot the [lang:xx] tag). */
    fun fromScript(text: String): Lang? {
        for (c in text) when {
            c in '\u0900'..'\u097F' -> return Lang.HI
            c in '\u3040'..'\u30FF' -> return Lang.JA
            c in '\uAC00'..'\uD7AF' -> return Lang.KO
        }
        return null
    }
}

data class MemoryRequest(val payload: String)

/** Progress steps Marvis says aloud while it works ("Finding YouTube...", "Pausing media..."). */
enum class Step {
    FINDING_APP, OPENING_APP, SEARCHING, OPENING_VIDEO, OPENING_RESULT, SCROLL_DOWN, SCROLL_UP,
    MEDIA_PLAY, MEDIA_PAUSE, MEDIA_REWIND, MEDIA_FORWARD, MEDIA_NEXT, MEDIA_PREVIOUS,
    FINDING_CONTACT, TYPING_MESSAGE, SENDING, TAPPING, TYPING, SYSTEM, TOGGLING, DRAFTING,
    DONE_OPENED, DONE_GENERIC, DONE_SEARCH, RESEARCH_START, CLEANING
}

/** Called by the action router before each step; the brain speaks it and waits until it has been said. */
typealias Announcer = suspend (Step, String) -> Unit
val NO_ANNOUNCE: Announcer = { _, _ -> }

/** [command] is the task with count words and duplicates removed. */
data class RepeatInfo(val command: String, val explicitCount: Int?, val occurrences: Int)

sealed interface RepeatAnswer {
    data object Once : RepeatAnswer
    data object Multi : RepeatAnswer
    data class Count(val n: Int) : RepeatAnswer
    data object Unknown : RepeatAnswer
}

/**
 * The "intelligence" around the model: language detection, which questions deserve web search, stop and
 * memory voice commands, memory retrieval for the prompt, and tidying replies (no repeats, no cut-off tails).
 */
class AIIntelligenceEngine(private val settings: SettingsStore, private val memory: MemoryManager) {

    @Volatile var lang: Lang = Lang.EN
        private set

    fun detect(text: String): Lang { lang = LanguageHelper.detect(text, lang); return lang }

    /** Remember Latin-script languages the model told us it used (es/fr/de) so cues match next time. */
    fun noteReplyLang(code: String?) {
        Lang.fromCode(code)?.let { if (it == Lang.ES || it == Lang.FR || it == Lang.DE) lang = it }
    }

    fun phrase(p: Phrase, l: Lang = lang): String = (PHRASES[l] ?: PHRASES.getValue(Lang.EN))[p] ?: PHRASES.getValue(Lang.EN).getValue(p)

    fun localeFor(parsed: ParsedReply, fallback: Lang): Locale {
        Lang.fromCode(parsed.lang)?.let { return it.locale }
        LanguageHelper.fromScript(parsed.display)?.let { return it.locale }
        return fallback.locale
    }

    // ---- voice commands ----

    fun isStop(text: String): Boolean {
        val n = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return false
        if (n.length <= 14 && STOPS_CJK.any { n.contains(it) }) return true
        val words = n.split(' ')
        if (words.size > 4) return false
        return STOPS.any { n == it || n.startsWith("$it ") || n.endsWith(" $it") }
    }

    /** "Remember this", "Save this in memory", "Ye memory me save karo", Hindi and Japanese variants. */
    fun memoryRequest(text: String): MemoryRequest? {
        for (re in MEMORY) {
            val m = re.find(text) ?: continue
            var rest = (text.removeRange(m.range)).trim().trim(',', '.', ':', '-', '!', '।', ' ')
            for (p in listOf("that ", "ki ", "ke bare me ", "ये ", "कि ")) if (rest.lowercase().startsWith(p)) rest = rest.substring(p.length)
            return MemoryRequest(rest.trim())
        }
        return null
    }


    /** "Unlock my device" in English, Hinglish, Hindi, Japanese. Only the user's own words can start an unlock. */
    fun isUnlockCommand(text: String): Boolean = UNLOCK.any { it.containsMatchIn(text) }

    /** Text for a spoken step, in the active language (English fallback for languages without a translation). */
    fun stepText(step: Step, arg: String, l: Lang = lang): String {
        val en = STEPS.getValue(Lang.EN)
        val text = STEPS[l]?.get(step) ?: en.getValue(step)
        return text.replace("%s", arg)
    }

    // ---- repeated tasks: "scroll down, scroll down, scroll down" ----

    /** Does this sound like a phone task (open, scroll, tap, send...) rather than a question? */
    fun isTaskLike(text: String): Boolean = TASKS.any { it.containsMatchIn(text) }

    fun normalizeTask(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")

    fun sameTask(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.split(' ').toSet()
        val y = b.split(' ').toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        return x.intersect(y).size.toDouble() / x.union(y).size.toDouble() >= 0.8
    }

    /**
     * Splits an utterance into the task itself, an explicit count if the user gave one ("3 times", "teen baar"),
     * and how many times the same phrase was said ("scroll down scroll down scroll down" = 3).
     */
    fun analyzeRepeat(text: String): RepeatInfo {
        var t = text.trim()
        var explicit: Int? = null

        val cm = COUNT_RE.find(t)
        if (cm != null) {
            val n = numberOf(cm.groupValues[1])
            if (n != null) { explicit = n; t = t.removeRange(cm.range) }
        } else {
            val wm = WORD_COUNT.find(t)
            if (wm != null) {
                explicit = when (wm.value.lowercase()) { "once" -> 1; "twice" -> 2; else -> 3 }
                t = t.removeRange(wm.range)
            }
        }
        t = t.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')

        var occurrences = 1
        val rm = REPEATED.find(t)
        if (rm != null) {
            val unit = rm.groupValues[1].trim()
            if (unit.count { it.isLetter() } >= 3) {
                val n = Regex(Regex.escape(unit), RegexOption.IGNORE_CASE).findAll(rm.value).count()
                if (n >= 2) {
                    occurrences = n
                    t = (unit + " " + t.substring(rm.range.last + 1)).trim().trim(',', '.', ' ')
                }
            }
        }
        return RepeatInfo(t, explicit, occurrences)
    }

    /** Understands "once", "multiple", "3 times", "teen baar", "三回"... Unknown = not an answer (treat as a new command). */
    fun parseRepeatAnswer(text: String): RepeatAnswer {
        val n = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return RepeatAnswer.Unknown
        if (MULTI_WORDS.any { n.contains(it) }) return RepeatAnswer.Multi
        val c = countIn(n)
        if (c != null) return if (c <= 1) RepeatAnswer.Once else RepeatAnswer.Count(c)
        if (ONCE_WORDS.any { n.contains(it) }) return RepeatAnswer.Once
        return RepeatAnswer.Unknown
    }

    private fun countIn(n: String): Int? {
        val cm = COUNT_RE.find(n)
        if (cm != null) numberOf(cm.groupValues[1])?.let { return it }
        val wm = WORD_COUNT.find(n)
        if (wm != null) return when (wm.value) { "once" -> 1; "twice" -> 2; else -> 3 }
        val dm = Regex("(?<![\\p{L}\\d])(\\d{1,2})(?![\\p{L}\\d])").find(n)
        if (dm != null) return dm.groupValues[1].toInt()
        for (tok in n.split(' ')) UNAMBIGUOUS_NUMBERS[tok]?.let { return it }
        return AMBIGUOUS_NUMBERS[n]            // a lone "do" / "ek" / "दो" is a number only when it is the whole answer
    }

    private fun numberOf(w: String): Int? {
        val k = w.lowercase().trim()
        return k.toIntOrNull() ?: UNAMBIGUOUS_NUMBERS[k] ?: AMBIGUOUS_NUMBERS[k]
    }


    // ---- speed: what deserves "thinking" ----

    /** Complex = web search, vision, long or analytical questions. Everything else is answered fast, without "Let me think". */
    fun isComplex(text: String, useWeb: Boolean, vision: Boolean): Boolean {
        if (useWeb || vision) return true
        if (text.trim().split(Regex("\\s+")).size > 16) return true
        return COMPLEX.any { it.containsMatchIn(text) }
    }

    /** Questions about the screen: Marvis then looks at it by itself (no screen-share switch). */
    fun wantsVision(text: String): Boolean = VISION.any { it.containsMatchIn(text) }

    fun researchTopic(text: String): String? {
        val t = text.trim().trimEnd('.', '!', '?', '。')
        for (re in RESEARCH) re.find(t)?.let { m -> m.groupValues[1].trim().takeIf { it.length >= 2 }?.let { return it } }
        return null
    }

    fun isCleanCommand(text: String): Boolean = CLEAN.any { it.containsMatchIn(text) }

    /** Instant local answers for greetings and simple facts: no network, no delay. null = ask the model. */
    fun quickReply(text: String, l: Lang): String? {
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.split(' ').size > 6) return null
        val name = settings.wakeName.value
        val now = java.time.ZonedDateTime.now()
        val hing = l == Lang.HINGLISH
        val hi = l == Lang.HI
        val ja = l == Lang.JA
        return when {
            GREET.matches(t) -> {
                val part = when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
                when {
                    hi -> "[happy] नमस्ते! बताइए, मैं क्या कर सकता हूँ?"
                    hing -> "[happy] Namaste! Boliye, main kya kar sakta hoon?"
                    ja -> "[happy] こんにちは！何をしましょうか？"
                    else -> "[happy] Hello! $part. What can I do for you?"
                }
            }
            THANKS.matches(t) -> when {
                hi -> "[happy] कोई बात नहीं!"
                hing -> "[happy] Koi baat nahi!"
                ja -> "[happy] どういたしまして！"
                else -> "[happy] Anytime!"
            }
            HOWRU.containsMatchIn(t) -> when {
                hi -> "[happy] मैं बिल्कुल ठीक हूँ, शुक्रिया! आप कैसे हैं?"
                hing -> "[happy] Main bilkul theek hoon, shukriya! Aap kaise ho?"
                ja -> "[happy] とても元気です！あなたは？"
                else -> "[happy] I'm doing great, thanks for asking! How about you?"
            }
            WHO.containsMatchIn(t) -> when {
                hi -> "[friendly] मैं $name हूँ, आपका वॉइस साथी। मैं ऐप खोल सकता हूँ, सर्च, मीडिया और रिसर्च कर सकता हूँ।"
                hing -> "[friendly] Main $name hoon, aapka voice companion. Main apps khol sakta hoon, search, media aur research kar sakta hoon."
                ja -> "[friendly] $name です。アプリを開いたり、検索や調査ができます。"
                else -> "[friendly] I'm $name, your voice companion. I can open apps, search, control media and do research for you."
            }
            TIME.containsMatchIn(t) -> {
                val clock = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
                when {
                    hi -> "[friendly] अभी $clock हुए हैं।"
                    hing -> "[friendly] Abhi $clock ho rahe hain."
                    ja -> "[friendly] 今は${now.hour}時${now.minute}分です。"
                    else -> "[friendly] It's $clock."
                }
            }
            DATE.containsMatchIn(t) -> {
                val day = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.ENGLISH))
                when {
                    hi -> "[friendly] आज $day है।"
                    hing -> "[friendly] Aaj $day hai."
                    ja -> "[friendly] 今日は${now.monthValue}月${now.dayOfMonth}日です。"
                    else -> "[friendly] Today is $day."
                }
            }
            else -> null
        }
    }

    // ---- model prep ----

    fun needsSearch(text: String): Boolean = settings.webSearch.value && SEARCH.any { it.containsMatchIn(text) }

    suspend fun systemPrompt(now: String, foregroundApp: String?, userText: String): String =
        SystemPrompt.build(now, foregroundApp, settings.wakeName.value, memory.buildContext(userText))

    /** Drop repeated sentences and, if the model hit its token limit, cut back to the last complete sentence. */
    fun finalize(c: Completion): String {
        val actions = ACTION.findAll(c.text).map { it.value }.toList()
        var body = ACTION.replace(c.text, "").trim()
        body = dedupe(body)
        if (c.truncated) {
            val idx = body.indexOfLast { it in ".!?।。！？…" }
            if (idx > body.length / 3) body = body.substring(0, idx + 1)
        }
        return (body + " " + actions.joinToString(" ")).trim()
    }

    private fun dedupe(text: String): String {
        val parts = SENTENCE.findAll(text).map { it.value }.toList()
        if (parts.size < 2) return text
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (p in parts) {
            val key = p.replace(Regex("\\[[^\\]]*]"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            if (key.length > 12 && !seen.add(key)) continue
            sb.append(p)
        }
        return sb.toString().trim()
    }

    companion object {
        private val ACTION = Regex("\\[\\[do:\\{.*?\\}\\]\\]", RegexOption.DOT_MATCHES_ALL)
        private val SENTENCE = Regex("[^.!?।。！？]+[.!?।。！？]*")
        private val I = RegexOption.IGNORE_CASE

        private val UNLOCK = listOf(
            Regex("\\bunlock\\b.{0,20}\\b(phone|device|screen|mobile)\\b|\\b(phone|device|screen|mobile)\\b.{0,20}\\bunlock\\b", I),
            Regex("\\b(phone|mobile|screen)\\s+unlock\\b|\\bunlock\\s+kar", I),
            Regex("^\\s*unlock\\s*[.!]?\\s*$", I),
            Regex("अनलॉक"),
            Regex("ロック解除|アンロック"),
        )


        private fun steps(vararg p: Pair<Step, String>): Map<Step, String> = mapOf(*p)

        private val STEPS: Map<Lang, Map<Step, String>> = mapOf(
            Lang.EN to steps(
                Step.FINDING_APP to "Finding %s...", Step.OPENING_APP to "Opening %s...",
                Step.SEARCHING to "Searching for %s...", Step.OPENING_VIDEO to "Opening video...",
                Step.OPENING_RESULT to "Opening result %s...", Step.SCROLL_DOWN to "Scrolling down...",
                Step.SCROLL_UP to "Scrolling up...", Step.MEDIA_PLAY to "Playing media...",
                Step.MEDIA_PAUSE to "Pausing media...", Step.MEDIA_REWIND to "Rewinding...",
                Step.MEDIA_FORWARD to "Skipping forward...", Step.MEDIA_NEXT to "Playing the next one...",
                Step.MEDIA_PREVIOUS to "Going to the previous one...", Step.FINDING_CONTACT to "Finding %s...",
                Step.TYPING_MESSAGE to "Typing your message...", Step.SENDING to "Sending...",
                Step.TAPPING to "Tapping %s...", Step.TYPING to "Typing...", Step.SYSTEM to "Doing %s...",
                Step.TOGGLING to "Switching %s...", Step.DRAFTING to "Preparing your %s...",
                Step.DONE_OPENED to "%s opened. What would you like next?", Step.DONE_GENERIC to "Done. What would you like next?",
                Step.DONE_SEARCH to "Here are the results for %s. Which one should I open?",
                Step.RESEARCH_START to "Starting research on %s. I'll tell you when it's ready.", Step.CLEANING to "Cleaning up background apps..."),
            Lang.HI to steps(
                Step.FINDING_APP to "%s खोज रहा हूँ...", Step.OPENING_APP to "%s खोल रहा हूँ...",
                Step.SEARCHING to "%s सर्च कर रहा हूँ...", Step.OPENING_VIDEO to "वीडियो खोल रहा हूँ...",
                Step.OPENING_RESULT to "रिज़ल्ट %s खोल रहा हूँ...", Step.SCROLL_DOWN to "नीचे स्क्रॉल कर रहा हूँ...",
                Step.SCROLL_UP to "ऊपर स्क्रॉल कर रहा हूँ...", Step.MEDIA_PLAY to "मीडिया चला रहा हूँ...",
                Step.MEDIA_PAUSE to "मीडिया रोक रहा हूँ...", Step.MEDIA_REWIND to "पीछे कर रहा हूँ...",
                Step.MEDIA_FORWARD to "आगे कर रहा हूँ...", Step.FINDING_CONTACT to "%s खोज रहा हूँ...",
                Step.TYPING_MESSAGE to "मैसेज लिख रहा हूँ...", Step.SENDING to "भेज रहा हूँ...",
                Step.DONE_OPENED to "%s खुल गया। आगे क्या करूँ?", Step.DONE_GENERIC to "हो गया। आगे क्या करूँ?",
                Step.DONE_SEARCH to "%s के नतीजे ये रहे। कौन सा खोलूँ?",
                Step.RESEARCH_START to "%s पर रिसर्च शुरू कर रहा हूँ। तैयार होने पर बताऊँगा।", Step.CLEANING to "बैकग्राउंड ऐप्स साफ़ कर रहा हूँ..."),
            Lang.HINGLISH to steps(
                Step.FINDING_APP to "%s dhoondh raha hoon...", Step.OPENING_APP to "%s khol raha hoon...",
                Step.SEARCHING to "%s search kar raha hoon...", Step.OPENING_VIDEO to "Video khol raha hoon...",
                Step.OPENING_RESULT to "Result %s khol raha hoon...", Step.SCROLL_DOWN to "Neeche scroll kar raha hoon...",
                Step.SCROLL_UP to "Upar scroll kar raha hoon...", Step.MEDIA_PLAY to "Media chala raha hoon...",
                Step.MEDIA_PAUSE to "Media pause kar raha hoon...", Step.MEDIA_REWIND to "Peeche kar raha hoon...",
                Step.MEDIA_FORWARD to "Aage kar raha hoon...", Step.FINDING_CONTACT to "%s dhoondh raha hoon...",
                Step.TYPING_MESSAGE to "Message likh raha hoon...", Step.SENDING to "Bhej raha hoon...",
                Step.DONE_OPENED to "%s khul gaya. Aage kya karun?", Step.DONE_GENERIC to "Ho gaya. Aage kya karun?",
                Step.DONE_SEARCH to "%s ke results ye rahe. Kaun sa kholun?",
                Step.RESEARCH_START to "%s par research shuru kar raha hoon. Ready hote hi bataunga.", Step.CLEANING to "Background apps saaf kar raha hoon..."),
            Lang.JA to steps(
                Step.FINDING_APP to "%sを探しています…", Step.OPENING_APP to "%sを開いています…",
                Step.SEARCHING to "%sを検索しています…", Step.OPENING_VIDEO to "動画を開いています…",
                Step.OPENING_RESULT to "%s番目の結果を開いています…", Step.SCROLL_DOWN to "下にスクロールしています…",
                Step.SCROLL_UP to "上にスクロールしています…", Step.MEDIA_PLAY to "再生します…",
                Step.MEDIA_PAUSE to "一時停止します…", Step.MEDIA_REWIND to "巻き戻します…",
                Step.MEDIA_FORWARD to "早送りします…", Step.FINDING_CONTACT to "%sを探しています…",
                Step.TYPING_MESSAGE to "メッセージを入力しています…", Step.SENDING to "送信しています…",
                Step.DONE_OPENED to "%sを開きました。次は何をしますか？", Step.DONE_GENERIC to "完了しました。次は何をしますか？",
                Step.DONE_SEARCH to "%sの検索結果です。どれを開きますか？",
                Step.RESEARCH_START to "%sについての調査を始めます。終わったらお知らせします。", Step.CLEANING to "バックグラウンドアプリを整理しています…"),
        )


        private val COMPLEX = listOf(
            Regex("\\b(explain|why|how (?:do|does|can|to|would)|compare|difference between|analy[sz]e|summari[sz]e|calculate|solve|plan|write|essay|story|poem|code|program|translate|pros and cons|recommend|suggest|in depth|step by step|detail)\\b", I),
            Regex("\\b(samjhao|kyun|kaise|tulna|likho|detail me)\\b", I),
            Regex("समझाओ|क्यों|कैसे|तुलना|लिखो"),
            Regex("説明|なぜ|比較|要約|計算"),
        )
        private val VISION = listOf(
            Regex("\\b(on my screen|on the screen|on this screen|this screen|what do you see|what can you see|look at (?:this|my screen|the screen)|read (?:this|the screen|my screen)|what'?s this|describe (?:this|the screen))\\b", I),
            Regex("\\b(screen (?:par|pe|mein) kya|ye kya hai|yeh kya hai|isko padho|screen padho)\\b", I),
            Regex("स्क्रीन पर क्या|यह क्या है|इसे पढ़ो"),
            Regex("画面に何|これは何|画面を読んで"),
        )
        private val RESEARCH = listOf(
            Regex("^(?:please\\s+)?(?:do\\s+|run\\s+|start\\s+)?(?:a\\s+|some\\s+)?(?:deep\\s+)?research\\s+(?:on|about|into|for)?\\s*(.{3,})$", I),
            Regex("^(?:please\\s+)?(?:investigate|dig into|look into)\\s+(.{3,})$", I),
            Regex("^(.{3,}?)\\s+(?:ke bare me|ke baare mein|par|pe)\\s+research\\s+karo$", I),
            Regex("^research\\s+karo\\s+(.{3,})$", I),
            Regex("^(.{2,}?)\\s*पर\\s*रिसर्च\\s*करो$"),
            Regex("^(.{2,}?)(?:について|を)調査して(?:ください)?$"),
        )
        private val CLEAN = listOf(
            Regex("\\b(?:clean|clear|free)\\s*(?:up)?\\s*(?:the\\s+|my\\s+)?(?:background|ram|memory|apps|phone)\\b|\\bboost (?:the |my )?phone\\b|\\bclose (?:all )?(?:the )?(?:background )?apps\\b|\\bclean house protocol\\b", I),
            Regex("\\b(?:ram saaf|background apps band|memory khali|ram clean)\\b", I),
            Regex("रैम साफ|बैकग्राउंड ऐप|मेमोरी खाली"),
            Regex("メモリを解放|バックグラウンド"),
        )
        private val GREET = Regex("^(?:(?:hey|hi|hello|yo|hola|namaste|namaskar|good morning|good afternoon|good evening)(?: (?:there|buddy|friend))?|नमस्ते|नमस्कार|こんにちは|おはよう|こんばんは)$", I)
        private val THANKS = Regex("^(?:thanks|thank you|thank you so much|thanks a lot|shukriya|dhanyavad|dhanyawad|शुक्रिया|धन्यवाद|ありがとう|ありがとうございます)$", I)
        private val HOWRU = Regex("\\bhow are you\\b|\\bhow r u\\b|\\bkaise ho\\b|\\bkya haal\\b|कैसे हो|क्या हाल|元気ですか", I)
        private val WHO = Regex("\\bwho are you\\b|\\bwhat is your name\\b|\\bwhat s your name\\b|\\btumhara naam\\b|\\baapka naam\\b|तुम कौन हो|आप कौन हैं|あなたは誰", I)
        private val TIME = Regex("\\bwhat time is it\\b|\\bwhat s the time\\b|\\btime kya hai\\b|\\bkitne baje\\b|समय क्या|何時", I)
        private val DATE = Regex("\\bwhat s the date\\b|\\btoday s date\\b|\\baaj ki tarikh\\b|\\baaj kya tarikh\\b|आज की तारीख|今日は何日", I)

        private val FILLER = setOf("please", "pls", "plz", "hey", "ok", "okay", "marvis", "jarvis", "friday", "the", "a", "an", "now", "jaldi", "zara", "yaar", "bhai")

        private val TASKS = listOf(
            Regex("\\b(open|launch|start|scroll|swipe|tap|click|press|type|send|call|text|message|turn (?:on|off)|switch (?:on|off)|enable|disable|toggle|rotate|remind|set (?:a |an )?(?:reminder|alarm)|cancel|close|go (?:back|home)|back|home|screenshot|lock|mute|unmute|play|pause|next|previous|volume|search|find|rewind|forward|resume|skip|whatsapp|youtube)\\b", I),
            Regex("\\b(kholo|khol|chalao|chalu|band|bhejo|bhej|neeche|upar|piche|wapas|lagao|laga)\\b", I),
            Regex("खोलो|चालू|बंद|स्क्रॉल|भेजो|नीचे|ऊपर|वापस"),
            Regex("開いて|スクロール|送って|オン|オフ|戻って|閉じて|下に|上に"),
        )

        private const val NUM = "\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|ek|do|teen|char|chaar|paanch|panch|chhe|chhah|saat|aath|nau|das|एक|दो|तीन|चार|पाँच|पांच|छह|छः|सात|आठ|नौ|दस|[一二三四五六七八九十]"
        private val COUNT_RE = Regex("(?<![\\p{L}\\d])($NUM)\\s*(?:times?|x|baar|bar|bari|बार|回|度)(?![\\p{L}])", I)
        private val WORD_COUNT = Regex("(?<![\\p{L}])(once|twice|thrice)(?![\\p{L}])", I)
        /** The same phrase at the start, repeated (with or without separators): "scroll down, scroll down". */
        private val REPEATED = Regex("^\\s*(.{3,}?)(?:[\\s,.;!?、。]*\\1)+", I)

        private val UNAMBIGUOUS_NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
            "nine" to 9, "ten" to 10, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
            "chhe" to 6, "chhah" to 6, "saat" to 7, "aath" to 8,
            "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8,
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)
        private val AMBIGUOUS_NUMBERS = mapOf("ek" to 1, "do" to 2, "nau" to 9, "das" to 10, "एक" to 1, "दो" to 2, "नौ" to 9, "दस" to 10)

        private val ONCE_WORDS = listOf("once", "one time", "just once", "only once", "single", "ek baar", "ek hi baar", "sirf ek", "एक बार", "एक ही बार", "一回", "一度", "1回", "한 번", "una vez", "une fois", "einmal")
        private val MULTI_WORDS = listOf("multiple", "several", "many", "multi", "more than once", "again and again", "few times", "a few", "kai baar", "kayi baar", "kai bar", "bahut baar", "कई बार", "बहुत बार", "複数", "何回も", "何度も", "여러 번", "varias", "plusieurs", "mehrmals")

        private val STOPS = listOf("stop", "stop it", "stop that", "cancel", "cancel that", "never mind", "nevermind",
            "enough", "ruko", "ruk jao", "rukja", "bas", "bas karo", "band karo", "chup", "रुको", "रुक जाओ", "बस", "बस करो", "बंद करो", "चुप")
        private val STOPS_CJK = listOf("ストップ", "やめて", "止めて", "やめろ", "멈춰", "그만")

        private val MEMORY = listOf(
            Regex("(?:please\\s+)?(?:save|store|add|put|keep)\\s+(?:this|that|it)\\s+(?:in|to|into|on)\\s+(?:my\\s+|your\\s+)?memory", I),
            Regex("(?:please\\s+)?remember\\s+(?:this|that)", I),
            Regex("(?:make|take)\\s+a\\s+note(?:\\s+of)?(?:\\s+this)?", I),
            Regex("note\\s+this\\s+down", I),
            Regex("(?:ye|yeh|ise|isko|isse|yah)\\s+(?:memory|yaad)\\s*(?:me|mein|mai|main)?\\s*(?:save|rakh|rakho|store|daal|dal)\\w*(?:\\s+(?:karo|kar do|lo))?", I),
            Regex("memory\\s*(?:me|mein|mai|main)\\s*(?:save|store|rakh)\\w*(?:\\s+(?:karo|kar do|lo))?", I),
            Regex("yaad\\s*rakh\\w*(?:\\s+(?:lo|karo))?", I),
            Regex("याद\\s*रख\\w*(?:\\s+(?:लो|करो))?"),
            Regex("मेमोरी\\s*(?:में|मे)\\s*(?:सेव|रख)\\w*(?:\\s+(?:करो|कर दो))?"),
            Regex("覚えて(?:ください|おいて)?|記憶して|メモリに保存(?:して)?|メモして"),
            Regex("기억해(?:줘|)|메모리에 저장(?:해줘|)"),
        )

        private val SEARCH = listOf(
            Regex("\\b(latest|news|price of|stock price|weather|forecast|score|who won|trending|search|google|look up|find out|what happened|exchange rate|near me|open now|release date|released)\\b", I),
            Regex("\\bwho is the (?:current )?(?:president|prime minister|ceo)\\b", I),
            Regex("\\b(khabar|samachar|mausam|kimat|bhav|dhundo|search karo|kya chal raha)\\b", I),
            Regex("समाचार|खबर|मौसम|कीमत|ताज़ा|ताजा"),
            Regex("ニュース|天気|最新|検索|株価"),
        )

        private fun p(en: String, hi: String, hing: String, ja: String, ko: String, es: String, fr: String, de: String) = listOf(en, hi, hing, ja, ko, es, fr, de)

        private val PHRASES: Map<Lang, Map<Phrase, String>> = run {
            val think = p("Let me think...", "सोचने दीजिए...", "Ek second, sochne do...", "ちょっと考えさせてください…", "생각 좀 해볼게요...", "Déjame pensar...", "Laisse-moi réfléchir...", "Lass mich nachdenken...")
            val cant = p("I can't hear it, can you repeat that?", "मुझे सुनाई नहीं दिया, क्या आप दोबारा बोल सकते हैं?", "Mujhe sunai nahi diya, kya aap dobara bol sakte ho?", "聞き取れませんでした。もう一度言ってもらえますか？", "잘 안 들려요, 다시 말해 줄래요?", "No te oigo bien, ¿puedes repetirlo?", "Je n'entends pas bien, tu peux répéter ?", "Ich kann dich nicht hören, kannst du das wiederholen?")
            val saved = p("Saved to memory", "मेमोरी में सेव हो गया", "Memory me save ho gaya", "メモリに保存しました", "메모리에 저장했어요", "Guardado en la memoria", "Enregistré dans la mémoire", "Im Speicher gespeichert")
            val yes = p("Yes?", "जी?", "Haan bolo?", "はい？", "네?", "¿Sí?", "Oui ?", "Ja?")
            val rep = p("I heard that more than once. Should I do it just once, or several times?", "आपने यह कई बार कहा। क्या मैं इसे एक बार करूँ या कई बार?", "Aapne ye kai baar bola. Main ise ek baar karun ya kai baar?", "同じ指示を何度か聞きました。一回だけにしますか、それとも複数回ですか？", "같은 말씀을 여러 번 하셨어요. 한 번만 할까요, 여러 번 할까요?", "Lo has dicho varias veces. ¿Lo hago una sola vez o varias veces?", "Tu l'as dit plusieurs fois. Je le fais une seule fois ou plusieurs fois ?", "Das habe ich mehrmals gehört. Soll ich es einmal oder mehrmals machen?")
            val how = p("How many times?", "कितनी बार?", "Kitni baar?", "何回ですか？", "몇 번이요?", "¿Cuántas veces?", "Combien de fois ?", "Wie oft?")
            val sec = p("Please say your secret code.", "कृपया अपना सीक्रेट कोड बोलिए।", "Apna secret code boliye.", "秘密のコードを言ってください。", "비밀 코드를 말씀해 주세요.", "Di tu código secreto, por favor.", "Dis ton code secret, s'il te plaît.", "Bitte sag deinen Geheimcode.")
            val order = listOf(Lang.EN, Lang.HI, Lang.HINGLISH, Lang.JA, Lang.KO, Lang.ES, Lang.FR, Lang.DE)
            order.withIndex().associate { (i, l) ->
                l to mapOf(Phrase.THINK to think[i], Phrase.CANT_HEAR to cant[i], Phrase.SAVED to saved[i], Phrase.YES to yes[i], Phrase.REPEAT_ASK to rep[i], Phrase.HOW_MANY to how[i], Phrase.SECRET_PROMPT to sec[i])
            }
        }
    }
}
