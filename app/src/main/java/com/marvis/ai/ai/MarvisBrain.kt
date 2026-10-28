package com.marvis.ai.ai

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.device.ActionRouter
import com.marvis.ai.device.AppForeground
import com.marvis.ai.device.MarvisAccessibilityService
import com.marvis.ai.device.ScreenCaptureHelper
import com.marvis.ai.MainActivity
import com.marvis.ai.MarvisApp
import com.marvis.ai.device.ActionResult
import com.marvis.ai.device.SystemCleaner
import com.marvis.ai.device.UnlockController
import com.marvis.ai.research.ResearchEngine
import com.marvis.ai.memory.MemoryManager
import com.marvis.ai.memory.SaveResult
import com.marvis.ai.security.SecurityStore
import com.marvis.ai.voice.TtsManager
import com.marvis.ai.voice.VoiceBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    val apiStatus: ApiStatus = ApiStatus.CHECKING,
    val apiDetail: String = "",
)

/**
 * The single mind of Marvis (one per process).
 *
 *  - Repeat guard: a task said several times is not run several times; Marvis asks "once or several times?".
 *  - Secret voice code: unlocking the phone, and (optionally) sending messages or email, first need the user's
 *    spoken secret code. The spoken code is checked locally, never shown in the captions, never stored in the chat
 *    history and never sent to the AI. A wrong code gets "Denied request" and the task is aborted.
 *  - Unlocking is started ONLY by the user's own spoken command. The AI model can't trigger it.
 *  - [cancelAll] is the stop button for everything: running turn, HTTP call, speech and pending questions.
 */
class MarvisBrain(
    ctx: Context,
    private val settings: SettingsStore,
    private val memory: MemoryManager,
    private val security: SecurityStore,
) {
    companion object {
        private const val MAX_REPEAT = 10
        private const val REPEAT_WINDOW_MS = 60_000L
        private const val ANSWER_WINDOW_MS = 25_000L
        private const val SECRET_WINDOW_MS = 25_000L
        private val REPEATABLE = setOf("scroll", "tap", "tap_text", "system")
    }

    private enum class Stage { MODE, COUNT }
    private class Pending(val command: String, val stage: Stage, val until: Long)
    private class SecureChallenge(val result: CompletableDeferred<Boolean>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val repo = OpenRouterRepository(settings)
    private val engine = AIIntelligenceEngine(settings, memory)
    private val router = ActionRouter(ctx)
    private val unlocker = UnlockController(ctx, security)
    private val cleaner = SystemCleaner(ctx)
    private val research = ResearchEngine(repo, settings)
    private val appCtx = ctx.applicationContext
    private var fallbackNoted = false
    private val history = mutableListOf<Turn>()
    private var nextId = 0L

    private var pending: Pending? = null
    private var pendingTimeout: Job? = null
    private var secure: SecureChallenge? = null
    private var lastTask: String? = null
    private var lastTaskAt = 0L
    private val activeTasks = HashSet<String>()

    private val _state = MutableStateFlow(BrainState())
    val state: StateFlow<BrainState> = _state.asStateFlow()

    /** True while Marvis waits for an answer (repeat question or secret code): no wake word needed. */
    private val _awaiting = MutableStateFlow(false)
    val awaitingAnswer: StateFlow<Boolean> = _awaiting.asStateFlow()

    /** True while waiting for the secret code: the voice service hides live captions so the code never shows. */
    private val _awaitingSecret = MutableStateFlow(false)
    val awaitingSecret: StateFlow<Boolean> = _awaitingSecret.asStateFlow()

    private val tts = TtsManager(
        ctx,
        depth = { settings.voiceDepth.value },
        preferredVoice = { settings.voiceName.value },
    ) { speaking, emo ->
        _state.update { it.copy(speaking = speaking, emotion = emo ?: it.emotion) }
    }

    val lang: Lang get() = engine.lang

    init { refreshStatus() }

    // ---------------- public API ----------------

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !MarvisRuntime.active) return
        if (engine.isStop(t)) { cancelAll(); return }

        // 0. Secret code answer: handled here, never echoed, never stored, never sent to the AI.
        val c = secure
        if (c != null) { checkSecret(c, t); return }

        // 1. "Once or several times?" answer
        val p = pending
        if (p != null) {
            if (System.currentTimeMillis() <= p.until && handleRepeatAnswer(p, t)) return
            clearPending()
        }

        // 2. "Unlock my device": only the user's own words can start this.
        if (engine.isUnlockCommand(t)) {
            val lang = engine.detect(t)
            addMessage(Role.USER, t)
            scope.launch { mutex.withLock { unlockFlow(lang) } }
            return
        }

        // 2b. Research and "clean up background apps": started right here, no model round-trip.
        engine.researchTopic(t)?.let { topic -> startResearch(t, topic); return }
        if (engine.isCleanCommand(t)) { cleanFlow(t); return }

        // 3. Repeated tasks
        if (engine.isTaskLike(t)) {
            val info = engine.analyzeRepeat(t)
            val norm = engine.normalizeTask(info.command)

            val explicit = info.explicitCount
            if (explicit != null) { addMessage(Role.USER, t); launchTurn(info.command, explicit, echoUser = false); return }

            if (!settings.askRepeat.value) { launchTurn(t, 1); return }
            if (norm in activeTasks) return

            val again = lastTask?.let { engine.sameTask(it, norm) } == true &&
                System.currentTimeMillis() - lastTaskAt < REPEAT_WINDOW_MS
            if (info.occurrences >= 2 || again) { askHowOften(t, info.command); return }
        }
        launchTurn(t, 1)
    }

    fun cancelAll() {
        clearPending()
        secure?.result?.complete(false)
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

    /** Is this the "unlock my device" phrase, with voice unlock switched on? (Lets it work without the wake word.) */
    fun isUnlockPhrase(text: String) = security.voiceUnlockEnabled.value && engine.isUnlockCommand(text)
    fun stopSpeaking() = tts.stop()
    fun voiceNames(): List<String> = tts.voiceNames()

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

    // ---------------- secret code + unlock ----------------

    /**
     * Settings > "Test unlock": locks the phone, waits, then runs the unlock. It skips the spoken secret code
     * because it can only be started from inside the app while the phone is already unlocked and in your hand.
     */
    fun testUnlock() {
        scope.launch {
            mutex.withLock {
                val lang = engine.lang
                val s = MarvisAccessibilityService.instance
                when {
                    !security.unlockReady() -> reply("[concerned] Save your lock code or pattern first.", lang)
                    s == null -> reply("[concerned] Turn on the Accessibility service for Marvis first.", lang)
                    else -> {
                        addMessage(Role.SYSTEM, "Testing unlock: locking the phone now")
                        s.global("lock_screen")
                        delay(3500)
                        val r = unlocker.unlock()
                        reply(if (r.ok) "[happy] Unlock test worked." else "[concerned] Unlock test failed: ${r.message}.", lang)
                    }
                }
            }
        }
    }


    private suspend fun unlockFlow(lang: Lang) {
        when {
            !security.available -> reply("[concerned] Secure storage isn't available on this phone, so voice unlock is off.", lang)
            !security.voiceUnlockEnabled.value -> reply("[concerned] Voice unlock is turned off. Turn it on in Settings under Security.", lang)
            !security.hasSecret.value -> reply("[concerned] Set your secret voice code in Settings first.", lang)
            !security.unlockReady() -> reply("[concerned] Save your lock code or pattern in Settings first.", lang)
            else -> {
                if (!requireSecret(lang)) return
                val r = unlocker.unlock()
                reply(if (r.ok) "[happy] ${r.message.replaceFirstChar { it.uppercase() }}." else "[concerned] Sorry, ${r.message}.", lang)
            }
        }
    }

    /**
     * Asks for the secret code and waits for the spoken answer.
     * @return true only if the code was right. On a wrong code Marvis says "Denied request" and the caller must abort.
     */
    private suspend fun requireSecret(lang: Lang): Boolean {
        val wait = security.lockoutRemainingMs()
        if (wait > 0) {
            val mins = (wait / 60_000L) + 1
            addMessage(Role.SYSTEM, "Denied request")
            tts.speak(listOf(Segment(Emotion.CONCERNED, "Denied request. Too many wrong codes. Try again in $mins minutes.")), flush = true)
            return false
        }
        val c = SecureChallenge(CompletableDeferred())
        secure = c
        _awaitingSecret.value = true
        _awaiting.value = true

        val prompt = engine.phrase(Phrase.SECRET_PROMPT, lang)
        addMessage(Role.ASSISTANT, prompt, Emotion.CALM)
        tts.speak(listOf(Segment(Emotion.CALM, prompt)), flush = true, locale = lang.locale)

        val ok = try {
            withTimeoutOrNull(SECRET_WINDOW_MS) { c.result.await() }
        } finally {
            secure = null
            _awaitingSecret.value = false
            _awaiting.value = pending != null
        }
        if (ok == null) {
            addMessage(Role.SYSTEM, "No code heard. Cancelled.")
            tts.speak(listOf(Segment(Emotion.CALM, "No code heard. Cancelled.")), flush = false)
            return false
        }
        return ok
    }

    private fun checkSecret(c: SecureChallenge, spoken: String) {
        if (security.verifySecret(spoken)) {
            security.resetFailures()
            addMessage(Role.SYSTEM, "Code accepted")
            c.result.complete(true)
        } else {
            security.recordFailure()
            addMessage(Role.SYSTEM, "Denied request")
            tts.speak(listOf(Segment(Emotion.CONCERNED, "Denied request")), flush = true)
            c.result.complete(false)
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
                is RepeatAnswer.Multi -> { addMessage(Role.USER, t); clearPending(); launchTurn(p.command, 3, echoUser = false) }
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
        _awaiting.value = secure != null
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

        // Greetings and simple facts are answered on the spot: no network, no "thinking", no delay.
        engine.quickReply(userText, lang)?.let { quick ->
            history += Turn("user", userText)
            history += Turn("assistant", quick)
            reply(quick, lang)
            return
        }

        val useWeb = engine.needsSearch(userText)
        val vision = engine.wantsVision(userText)
        val complex = engine.isComplex(userText, useWeb, vision)

        // "Thinking" shows at once for complex work, but only after 0.9 s for a simple question.
        var thinkingTimer: Job? = null
        if (complex) _state.update { it.copy(thinking = true) }
        else thinkingTimer = scope.launch { delay(900); _state.update { it.copy(thinking = true) } }

        try {
            if (settings.apiKey.value.isBlank()) {
                _state.update { it.copy(apiStatus = ApiStatus.KEY_MISSING, apiDetail = "Add your OpenRouter key") }
                reply("[concerned] I need your OpenRouter API key first. Open Settings and paste it.", lang)
                return
            }

            // "Let me think..." only for complex questions, searches and analysis.
            if (complex && settings.thinkCue.value) {
                tts.speak(listOf(Segment(Emotion.THOUGHTFUL, engine.phrase(Phrase.THINK, lang))), flush = true, locale = lang.locale)
            }

            // Full-screen vision happens by itself when the question is about the screen. No switch needed.
            var shot: String? = null
            if (vision) {
                AppForeground.ensureBackground()
                shot = ScreenCaptureHelper.captureJpegBase64()
                if (shot == null) addMessage(Role.SYSTEM, "I can't see the screen. Turn on the Accessibility service for Marvis.")
            }
            history.forEachIndexed { i, t -> if (t.imageB64 != null) history[i] = t.copy(imageB64 = null) }
            history += Turn("user", userText, shot)

            var pendingShot: String? = null
            var round = 0
            while (true) {
                trimHistory()
                val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (zzzz)"))
                val system = engine.systemPrompt(now, MarvisAccessibilityService.currentPackage, userText)
                val completion = repo.complete(
                    if (complex || round > 0) history else window(8),     // simple questions send less history = faster
                    system,
                    webSearch = useWeb && round == 0,
                    maxTokens = if (complex) 1100 else 280,
                    effort = if (complex) null else "low",
                )
                _state.update { it.copy(apiStatus = ApiStatus.CONNECTED, apiDetail = "") }

                val primary = settings.model.value.ifBlank { OpenRouterRepository.DEFAULT_MODEL }
                if (completion.model.isNotBlank() && completion.model != primary && !fallbackNoted) {
                    fallbackNoted = true
                    addMessage(Role.SYSTEM, "Answered by ${completion.model}: your main model wasn't available or needs credits.")
                }

                val cleaned = engine.finalize(completion)
                history += Turn("assistant", cleaned)
                val parsed = ReplyParser.parse(cleaned)
                engine.noteReplyLang(parsed.lang)
                val needsData = parsed.actions.any { it.optString("a") in setOf("read_screen", "list_reminders", "look") }
                val speakNow = !needsData || round >= 1

                if (speakNow) {
                    if (completion.webDowngraded) addMessage(Role.SYSTEM, "Web search needs OpenRouter credits, so I answered without it.")
                    if (parsed.display.isNotBlank()) {
                        addMessage(Role.ASSISTANT, parsed.display, parsed.segments.firstOrNull()?.emotion)
                        if (parsed.actions.isEmpty()) tts.speak(parsed.segments, flush = false, locale = engine.localeFor(parsed, lang))
                    }
                    if (completion.sources.isNotEmpty()) addMessage(Role.SYSTEM, "Sources: " + completion.sources.take(3).joinToString(" · "))
                }

                val data = StringBuilder()
                val announcer: Announcer = { step, arg -> speakAndWait(engine.stepText(step, arg, lang), lang) }
                val done = ArrayList<Pair<JSONObject, ActionResult>>()
                var failed = false

                for (a in collapseDuplicates(parsed.actions)) {
                    val name = a.optString("a")

                    if (name == "research") {                         // runs in the background, reports back when done
                        val topic = a.optString("topic")
                        if (topic.isNotBlank()) startResearch(userText, topic, echo = false)
                        continue
                    }
                    if (name == "look") {                             // the model asks to see the screen
                        AppForeground.ensureBackground()
                        pendingShot = ScreenCaptureHelper.captureJpegBase64()
                        data.append("look: ").append(if (pendingShot != null) "screenshot attached" else "failed, the Accessibility service is off").append('\n')
                        continue
                    }

                    // Sensitive: sending a message or email needs the secret code first (when one is set).
                    if (router.willSend(a, userText) && security.requireCodeForSend.value && security.hasSecret.value) {
                        if (!requireSecret(lang)) {
                            addMessage(Role.SYSTEM, "Send cancelled")
                            failed = true
                            continue
                        }
                    }

                    val times = if (repeat > 1 && name in REPEATABLE) repeat else 1
                    if (repeat > 1 && name !in REPEATABLE) addMessage(Role.SYSTEM, "I did \"$name\" once. Repeating it wouldn't make sense.")

                    var i = 0
                    while (i < times) {
                        val r = router.execute(a, userText, if (i == 0) announcer else NO_ANNOUNCE)
                        if (!r.ok) {
                            failed = true
                            addMessage(Role.SYSTEM, "⚠ ${r.message}")
                            tts.speak(listOf(Segment(Emotion.CONCERNED, "Sorry, ${r.message}")), flush = false)
                            break
                        }
                        if (i == 0) {
                            r.data?.let { data.append(name).append(": ").append(it).append('\n') }
                            done += a to r
                        }
                        i++
                        delay(if (times > 1) 550 else 350)
                    }
                }

                // After the actions: the model's own words, or a spoken status ("YouTube opened. What next?"),
                // then the microphone stays open for the next command (no wake word needed).
                if (parsed.actions.isNotEmpty() && speakNow) {
                    if (parsed.display.isNotBlank()) {
                        tts.speak(parsed.segments, flush = false, locale = engine.localeFor(parsed, lang))
                    } else if (!failed) {
                        feedbackFor(done, lang)?.let { tts.speak(listOf(Segment(Emotion.FRIENDLY, it)), flush = false, locale = lang.locale) }
                    }
                    if (!failed && done.isNotEmpty()) followUp()
                }

                if (!needsData || round >= 1 || data.isEmpty()) break
                history += Turn("user", "[system data, not from the user]\n$data", pendingShot)
                pendingShot = null
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
            thinkingTimer?.cancel()
            _state.update { it.copy(thinking = false) }
        }
    }

    /** What Marvis says after an action finished fine. */
    private fun feedbackFor(done: List<Pair<JSONObject, ActionResult>>, lang: Lang): String? {
        val (a, r) = done.lastOrNull() ?: return null
        return when (a.optString("a")) {
            "open_app" -> engine.stepText(Step.DONE_OPENED, r.message.removePrefix("Opened ").trim(), lang)
            "search_in_app" ->
                if (a.optBoolean("open_first")) engine.stepText(Step.DONE_GENERIC, "", lang)
                else engine.stepText(Step.DONE_SEARCH, a.optString("query"), lang)
            "open_result", "scroll", "tap", "tap_text", "type", "system", "media", "whatsapp",
            "wifi", "bluetooth", "mobile_data", "rotate" -> engine.stepText(Step.DONE_GENERIC, "", lang)
            else -> null
        }
    }

    /** Opens the mic for the next command without the wake word. Takes effect once Marvis has finished speaking. */
    private fun followUp() { VoiceBus.requests.tryEmit(Unit) }

    /** The newest [n] turns, starting at a real user turn (keeps simple questions fast). */
    private fun window(n: Int): List<Turn> {
        var from = (history.size - n).coerceAtLeast(0)
        while (from < history.size && (history[from].role != "user" || history[from].text.startsWith("[system data"))) from++
        return history.subList(from, history.size).toList()
    }

    // ---------------- autonomous research (background) ----------------

    /**
     * "Research <topic>": acknowledges at once, then works in the background (you can keep talking). When done it
     * speaks a summary, shows the full report in the captions, saves it in Memory and posts a notification.
     */
    private fun startResearch(original: String, topic: String, echo: Boolean = true) {
        val lang = engine.detect(original)
        if (echo) addMessage(Role.USER, original)
        val ack = engine.stepText(Step.RESEARCH_START, topic, lang)
        addMessage(Role.ASSISTANT, ack, Emotion.THOUGHTFUL)
        tts.speak(listOf(Segment(Emotion.THOUGHTFUL, ack)), flush = true, locale = lang.locale)

        val languageName = when (lang) {
            Lang.HI -> "Hindi (Devanagari script)"; Lang.HINGLISH -> "Hinglish (Hindi in Roman letters)"
            Lang.JA -> "Japanese"; Lang.KO -> "Korean"; Lang.ES -> "Spanish"; Lang.FR -> "French"; Lang.DE -> "German"
            else -> "English"
        }
        val wikiLang = when (lang) {
            Lang.HI -> "hi"; Lang.JA -> "ja"; Lang.KO -> "ko"; Lang.ES -> "es"; Lang.FR -> "fr"; Lang.DE -> "de"; else -> "en"
        }
        scope.launch {
            try {
                val report = research.run(topic, languageName, wikiLang) { msg -> addMessage(Role.SYSTEM, "Research: $msg") }
                memory.add("Research: ${report.topic}\n\n${report.summary}\n\n${report.body}")       // readable in Settings > Memory
                addMessage(Role.ASSISTANT, "${report.topic}\n\n${report.body}", Emotion.THOUGHTFUL)
                val spoken = ReplyParser.parse("[thoughtful] ${report.summary}")
                tts.speak(spoken.segments, flush = false, locale = lang.locale)
                notifyResearchReady(report.topic)
                followUp()
            } catch (e: CancellationException) {
                addMessage(Role.SYSTEM, "Research cancelled")
                throw e
            } catch (e: ApiException) {
                reply("[concerned] ${friendly(e)}", lang)
            } catch (e: Exception) {
                reply("[concerned] I couldn't finish the research: ${e.message}.", lang)
            }
        }
    }

    private fun notifyResearchReady(topic: String) {
        runCatching {
            val open = PendingIntent.getActivity(appCtx, 3, Intent(appCtx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(appCtx, MarvisApp.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Research ready")
                .setContentText(topic)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            appCtx.getSystemService(NotificationManager::class.java).notify(9100, n)
        }
    }

    // ---------------- "clean up background apps" ----------------

    private fun cleanFlow(original: String) {
        val lang = engine.detect(original)
        addMessage(Role.USER, original)
        scope.launch {
            mutex.withLock {
                speakAndWait(engine.stepText(Step.CLEANING, "", lang), lang)
                val r = cleaner.clean()
                val text = if (lang == Lang.EN) "Done. ${r.summary()} What would you like next?" else engine.stepText(Step.DONE_GENERIC, "", lang)
                addMessage(Role.ASSISTANT, if (lang == Lang.EN) r.summary() else text, Emotion.FRIENDLY)
                tts.speak(listOf(Segment(Emotion.FRIENDLY, text)), flush = false, locale = lang.locale)
                followUp()
            }
        }
    }

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

    /** Says a progress step aloud and waits until it has finished, so the action starts right after the words. */
    private suspend fun speakAndWait(text: String, lang: Lang) {
        tts.speak(listOf(Segment(Emotion.CALM, text)), flush = true, locale = lang.locale)
        withTimeoutOrNull(900) { state.first { it.speaking } }
        withTimeoutOrNull(6_000) { state.first { !it.speaking } }
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
