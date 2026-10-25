package com.marvis.ai.ai

import android.content.Context
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.device.ActionRouter
import com.marvis.ai.device.AppForeground
import com.marvis.ai.device.MarvisAccessibilityService
import com.marvis.ai.device.ScreenCaptureHelper
import com.marvis.ai.memory.MemoryManager
import com.marvis.ai.memory.SaveResult
import com.marvis.ai.voice.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Role { USER, ASSISTANT, SYSTEM }
data class ChatMessage(val id: Long, val role: Role, val text: String, val emotion: Emotion? = null)
enum class ApiStatus { KEY_MISSING, CHECKING, CONNECTED, ERROR }

data class BrainState(
    val messages: List<ChatMessage> = emptyList(),
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    val emotion: Emotion = Emotion.CALM,
    val screenShare: Boolean = false,
    val apiStatus: ApiStatus = ApiStatus.CHECKING,
    val apiDetail: String = "",
)

/**
 * The single mind of Marvis (one per process).
 *
 * Repeat guard: if a phone task arrives more than once ("scroll down, scroll down, scroll down", or the same
 * command again within a minute), Marvis does NOT run it several times. It asks "once or several times?",
 * then "how many?" if needed, and does exactly what you answer. An explicit count ("3 times", "teen baar")
 * skips the question. Identical commands that arrive while the first is still running are dropped.
 *
 * [cancelAll] is the stop button: cancels the running turn, any in-flight HTTP call, the speech and any
 * pending question.
 */
class MarvisBrain(ctx: Context, private val settings: SettingsStore, private val memory: MemoryManager) {
    companion object {
        private const val MAX_REPEAT = 10
        private const val REPEAT_WINDOW_MS = 60_000L
        private const val ANSWER_WINDOW_MS = 25_000L
        /** Only these make sense to repeat. Sending messages, opening apps, reminders etc. always run once. */
        private val REPEATABLE = setOf("scroll", "tap", "tap_text", "system")
    }

    private enum class Stage { MODE, COUNT }
    private class Pending(val command: String, val stage: Stage, val until: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val repo = OpenRouterRepository(settings)
    private val engine = AIIntelligenceEngine(settings, memory)
    private val router = ActionRouter(ctx)
    private val history = mutableListOf<Turn>()
    private var nextId = 0L

    private var pending: Pending? = null
    private var pendingTimeout: Job? = null
    private var lastTask: String? = null
    private var lastTaskAt = 0L
    private val activeTasks = HashSet<String>()

    private val _state = MutableStateFlow(BrainState())
    val state: StateFlow<BrainState> = _state.asStateFlow()

    /** True while Marvis waits for "once or several times?" (the voice service then needs no wake word). */
    private val _awaiting = MutableStateFlow(false)
    val awaitingAnswer: StateFlow<Boolean> = _awaiting.asStateFlow()

    private val tts = TtsManager(ctx) { speaking, emo ->
        _state.update { it.copy(speaking = speaking, emotion = emo ?: it.emotion) }
    }

    val lang: Lang get() = engine.lang

    init { refreshStatus() }

    // ---------------- public API ----------------

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !MarvisRuntime.active) return
        if (engine.isStop(t)) { cancelAll(); return }

        // 1. Are we waiting for "once or several times?"
        val p = pending
        if (p != null) {
            if (System.currentTimeMillis() <= p.until && handleRepeatAnswer(p, t)) return
            clearPending()                               // expired, or the user said something else: new command
        }

        // 2. Repeated tasks
        if (engine.isTaskLike(t)) {
            val info = engine.analyzeRepeat(t)
            val norm = engine.normalizeTask(info.command)

            val explicit = info.explicitCount
            if (explicit != null) { addMessage(Role.USER, t); launchTurn(info.command, explicit, echoUser = false); return }

            if (!settings.askRepeat.value) { launchTurn(t, 1); return }

            if (norm in activeTasks) return              // same task already running or queued: drop the duplicate

            val again = lastTask?.let { engine.sameTask(it, norm) } == true &&
                System.currentTimeMillis() - lastTaskAt < REPEAT_WINDOW_MS
            if (info.occurrences >= 2 || again) { askHowOften(t, info.command); return }
        }
        launchTurn(t, 1)
    }

    /** Hard stop: cancels the turn, the HTTP call, the speech and any pending question. */
    fun cancelAll() {
        clearPending()
        activeTasks.clear()
        scope.coroutineContext.cancelChildren()
        tts.stop()
        _state.update { it.copy(thinking = false) }
    }

    fun say(text: String, emotion: Emotion = Emotion.NEUTRAL) {
        if (!MarvisRuntime.active) return
        tts.speak(listOf(Segment(emotion, text)), locale = engine.lang.locale)
    }

    fun sayPhrase(p: Phrase, emotion: Emotion = Emotion.NEUTRAL) = say(engine.phrase(p), emotion)

    fun isStopCommand(text: String) = engine.isStop(text)
    fun stopSpeaking() = tts.stop()
    fun setScreenShare(on: Boolean) = _state.update { it.copy(screenShare = on) }

    fun refreshStatus() {
        scope.launch {
            if (settings.apiKey.value.isBlank()) {
                _state.update { it.copy(apiStatus = ApiStatus.KEY_MISSING, apiDetail = "Add your OpenRouter key") }
                return@launch
            }
            _state.update { it.copy(apiStatus = ApiStatus.CHECKING, apiDetail = "") }
            try {
                repo.checkKey()
                _state.update { it.copy(apiStatus = ApiStatus.CONNECTED, apiDetail = "") }
            } catch (e: ApiException) {
                _state.update { it.copy(apiStatus = ApiStatus.ERROR, apiDetail = friendly(e)) }
            }
        }
    }

    // ---------------- repeat guard ----------------

    private fun askHowOften(original: String, command: String) {
        val lang = engine.detect(original)
        addMessage(Role.USER, original)
        setPending(Pending(command, Stage.MODE, System.currentTimeMillis() + ANSWER_WINDOW_MS))
        val q = engine.phrase(Phrase.REPEAT_ASK, lang)
        addMessage(Role.ASSISTANT, q, Emotion.FRIENDLY)
        tts.speak(listOf(Segment(Emotion.FRIENDLY, q)), flush = true, locale = lang.locale)
    }

    /** @return true if [t] was an answer to the pending question. */
    private fun handleRepeatAnswer(p: Pending, t: String): Boolean {
        val lang = engine.detect(t)
        val answer = engine.parseRepeatAnswer(t)
        when (p.stage) {
            Stage.MODE -> when (answer) {
                is RepeatAnswer.Once -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, 1, echoUser = false) }
                is RepeatAnswer.Count -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, answer.n, echoUser = false) }
                is RepeatAnswer.Multi -> {
                    addMessage(Role.USER, t)
                    setPending(Pending(p.command, Stage.COUNT, System.currentTimeMillis() + ANSWER_WINDOW_MS))
                    val q = engine.phrase(Phrase.HOW_MANY, lang)
                    addMessage(Role.ASSISTANT, q, Emotion.FRIENDLY)
                    tts.speak(listOf(Segment(Emotion.FRIENDLY, q)), flush = true, locale = lang.locale)
                }
                is RepeatAnswer.Unknown -> return false
            }
            Stage.COUNT -> when (answer) {
                is RepeatAnswer.Count -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, answer.n, echoUser = false) }
                is RepeatAnswer.Once -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, 1, echoUser = false) }
                is RepeatAnswer.Multi -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, 3, echoUser = false) }  // "several" = 3
                is RepeatAnswer.Unknown -> return false
            }
        }
        return true
    }

    private fun setPending(p: Pending) {
        pendingTimeout?.cancel()
        pending = p
        _awaiting.value = true
        pendingTimeout = scope.launch {
            delay(ANSWER_WINDOW_MS)
            if (pending === p) clearPending()
        }
    }

    private fun clearPending() {
        pendingTimeout?.cancel()
        pendingTimeout = null
        pending = null
        _awaiting.value = false
    }

    private fun launchTurn(command: String, times: Int, echoUser: Boolean = true) {
        val n = times.coerceIn(1, MAX_REPEAT)
        if (times > MAX_REPEAT) addMessage(Role.SYSTEM, "I'll repeat it at most $MAX_REPEAT times.")
        val task = engine.isTaskLike(command)
        val norm = engine.normalizeTask(command)
        if (task) activeTasks += norm
        scope.launch {
            try { mutex.withLock { turn(command, n, echoUser) } }
            finally { if (task) activeTasks.remove(norm) }
        }
    }

    // ---------------- one turn ----------------

    private suspend fun turn(userText: String, repeat: Int, echoUser: Boolean) {
        val lang = engine.detect(userText)
        if (echoUser) addMessage(Role.USER, userText)
        if (engine.isTaskLike(userText)) {
            lastTask = engine.normalizeTask(userText)
            lastTaskAt = System.currentTimeMillis()
        }
        if (repeat > 1) addMessage(Role.SYSTEM, "Doing it $repeat times")

        engine.memoryRequest(userText)?.let { handleMemory(it, lang); return }

        _state.update { it.copy(thinking = true) }
        try {
            if (settings.apiKey.value.isBlank()) {
                _state.update { it.copy(apiStatus = ApiStatus.KEY_MISSING, apiDetail = "Add your OpenRouter key") }
                reply("[concerned] I need your OpenRouter API key first. Open Settings and paste it.", lang)
                return
            }

            if (settings.thinkCue.value) {
                tts.speak(listOf(Segment(Emotion.THOUGHTFUL, engine.phrase(Phrase.THINK, lang))), flush = true, locale = lang.locale)
            }

            var shot: String? = null
            if (_state.value.screenShare) {
                AppForeground.ensureBackground()
                shot = ScreenCaptureHelper.captureJpegBase64()
            }
            history.forEachIndexed { i, t -> if (t.imageB64 != null) history[i] = t.copy(imageB64 = null) }
            history += Turn("user", userText, shot)

            val useWeb = engine.needsSearch(userText)
            var round = 0
            while (true) {
                trimHistory()
                val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (zzzz)"))
                val system = engine.systemPrompt(now, MarvisAccessibilityService.currentPackage, userText)
                val completion = repo.complete(history, system, webSearch = useWeb && round == 0)
                _state.update { it.copy(apiStatus = ApiStatus.CONNECTED, apiDetail = "") }

                val cleaned = engine.finalize(completion)
                history += Turn("assistant", cleaned)
                val parsed = ReplyParser.parse(cleaned)
                engine.noteReplyLang(parsed.lang)
                val needsData = parsed.actions.any { it.optString("a") in setOf("read_screen", "list_reminders") }

                if (!needsData || round >= 1) {
                    if (completion.webDowngraded) addMessage(Role.SYSTEM, "Web search needs OpenRouter credits, so I answered without it.")
                    if (parsed.display.isNotBlank()) {
                        addMessage(Role.ASSISTANT, parsed.display, parsed.segments.firstOrNull()?.emotion)
                        tts.speak(parsed.segments, flush = false, locale = engine.localeFor(parsed, lang))
                    }
                    if (completion.sources.isNotEmpty()) addMessage(Role.SYSTEM, "Sources: " + completion.sources.take(3).joinToString(" · "))
                }

                val data = StringBuilder()
                for (a in collapseDuplicates(parsed.actions)) {
                    val name = a.optString("a")
                    val times = if (repeat > 1 && name in REPEATABLE) repeat else 1
                    if (repeat > 1 && name !in REPEATABLE) addMessage(Role.SYSTEM, "I did \"$name\" once. Repeating it wouldn't make sense.")

                    var i = 0
                    while (i < times) {
                        val r = router.execute(a, userText)
                        if (!r.ok) {
                            addMessage(Role.SYSTEM, "⚠ ${r.message}")
                            tts.speak(listOf(Segment(Emotion.CONCERNED, "Sorry, ${r.message}")), flush = false)
                            break
                        }
                        if (i == 0) r.data?.let { data.append(name).append(": ").append(it).append('\n') }
                        i++
                        delay(if (times > 1) 550 else 350)
                    }
                }

                if (!needsData || round >= 1 || data.isEmpty()) break
                history += Turn("user", "[system data, not from the user]\n$data")
                round++
            }
        } catch (e: CancellationException) {
            addMessage(Role.SYSTEM, "Stopped")
            throw e
        } catch (e: ApiException) {
            _state.update { it.copy(apiStatus = ApiStatus.ERROR, apiDetail = friendly(e)) }
            reply("[concerned] ${friendly(e)}", lang)
        } catch (e: Exception) {
            reply("[concerned] Something went wrong: ${e.message}", lang)
        } finally {
            _state.update { it.copy(thinking = false) }
        }
    }

    /** The model sometimes repeats an action tag by itself; run each distinct consecutive action once. */
    private fun collapseDuplicates(actions: List<JSONObject>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (a in actions) if (out.isEmpty() || out.last().toString() != a.toString()) out += a
        return out
    }

    private suspend fun handleMemory(req: MemoryRequest, lang: Lang) {
        val payload = req.payload.ifBlank { lastExchange() }
        if (payload.isBlank()) {
            reply("[friendly] There's nothing to save yet. Tell me what to remember.", lang)
            return
        }
        when (memory.saveFromVoice(payload)) {
            SaveResult.OK -> {
                val msg = engine.phrase(Phrase.SAVED, lang)
                addMessage(Role.ASSISTANT, msg, Emotion.HAPPY)
                tts.speak(listOf(Segment(Emotion.HAPPY, msg)), flush = true, locale = lang.locale)
            }
            SaveResult.FULL -> reply("[concerned] My memory is full. Delete some notes in Settings.", lang)
            SaveResult.EMPTY -> reply("[friendly] I didn't catch what to save.", lang)
        }
    }

    private fun lastExchange(): String {
        val a = history.lastOrNull { it.role == "assistant" } ?: return ""
        val u = history.lastOrNull { it.role == "user" && !it.text.startsWith("[system data") }
        return buildString {
            u?.let { append("User asked: ").append(it.text).append('\n') }
            append("Marvis said: ").append(ReplyParser.parse(a.text).display)
        }
    }

    private fun reply(raw: String, lang: Lang) {
        val p = ReplyParser.parse(raw)
        addMessage(Role.ASSISTANT, p.display, p.segments.firstOrNull()?.emotion)
        tts.speak(p.segments, flush = false, locale = lang.locale)
    }

    private fun addMessage(role: Role, text: String, emotion: Emotion? = null) =
        _state.update { it.copy(messages = (it.messages + ChatMessage(nextId++, role, text, emotion)).takeLast(60)) }

    private fun trimHistory() {
        while (history.size > 24) history.removeAt(0)
        while (history.isNotEmpty() && (history.first().role != "user" || history.first().text.startsWith("[system data"))) history.removeAt(0)
    }

    private fun friendly(e: ApiException) = when (e.kind) {
        ApiException.Kind.AUTH -> "OpenRouter rejected the API key. Open Settings and check it."
        ApiException.Kind.CREDITS -> "Your OpenRouter account is out of credits for this model. Try the free router model."
        ApiException.Kind.RATE_LIMIT -> "The free model is rate-limited right now. Try again in a minute."
        ApiException.Kind.SERVER -> "OpenRouter's servers are overloaded. Try again shortly."
        ApiException.Kind.MODEL -> "That model isn't available. Change it in Settings, or use openrouter/free."
        ApiException.Kind.NETWORK -> "I can't reach the internet right now."
        ApiException.Kind.OTHER -> "The API returned an error: ${e.message}"
    }
}
