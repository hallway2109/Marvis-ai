package com.marvis.ai.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import com.marvis.ai.MarvisApp
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.ai.Announcer
import com.marvis.ai.ai.NO_ANNOUNCE
import com.marvis.ai.ai.Step
import com.marvis.ai.reminders.Repeat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ActionResult(val ok: Boolean, val message: String, val data: String? = null)

/**
 * Executes the [[do:{...}]] actions the model emits. Every step is announced aloud through [Announcer] first
 * ("Finding YouTube...", "Pausing media..."). Unlocking the phone is NOT an action: only the user's own spoken
 * command can start it.
 */
class ActionRouter(private val ctx: Context) {

    private fun good(msg: String = "done", data: String? = null) = ActionResult(true, msg, data)
    private fun fail(msg: String) = ActionResult(false, msg)
    private fun Boolean.res(yes: String, no: String) = if (this) good(yes) else fail(no)

    private val noA11y = "the Accessibility service is off; enable Marvis in Accessibility settings"
    private val media by lazy { MediaControl(ctx) }

    private fun desired(a: JSONObject): Boolean? = when (a.optString("state")) {
        "on" -> true; "off" -> false; else -> null
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (MarvisAccessibilityService.instance ?: ctx).startActivity(i)
    }

    private fun userSaidSend(userText: String) =
        Regex("\\bsend\\b|\\bbhej", RegexOption.IGNORE_CASE).containsMatchIn(userText)

    /** Will this action really send a message or email? (The brain asks for the secret code first.) */
    fun willSend(a: JSONObject, userText: String): Boolean =
        a.optString("a") in setOf("sms", "email", "whatsapp") && a.optBoolean("send") && userSaidSend(userText)

    suspend fun execute(a: JSONObject, userText: String, announce: Announcer = NO_ANNOUNCE): ActionResult {
        val name = a.optString("a")
        if (!MarvisRuntime.active) return fail("Marvis is stopped. Open the app to wake me up")
        when (name) {
            "reminder" -> return setReminder(a)
            "list_reminders" -> return listReminders()
            "cancel_reminder" -> { MarvisApp.instance.reminders.cancel(a.optLong("id")); return good("Reminder cancelled") }
            "media" -> return mediaCommand(a, announce)            // needs no accessibility, no need to leave Marvis
        }

        AppForeground.ensureBackground()                            // step aside so we act on the OTHER app
        val s = MarvisAccessibilityService.instance
        val needsA11y = setOf("scroll", "tap", "tap_text", "type", "system", "wifi", "bluetooth", "mobile_data",
            "read_screen", "search_in_app", "open_result", "whatsapp")
        if (name in needsA11y && s == null) return fail(noA11y)
        val said = userSaidSend(userText)

        return when (name) {
            "open_app" -> openApp(a.optString("name"), announce)
            "search_in_app" -> searchInApp(s!!, a.optString("app"), a.optString("query"), a.optBoolean("open_first"), announce)
            "open_result" -> {
                val n = a.optInt("n", 1).coerceAtLeast(1)
                announceOpening(announce, n)
                s!!.openResult(n).res("Opened result $n", "I couldn't find that result on screen")
            }
            "whatsapp" -> whatsapp(s!!, a, said, announce)
            "scroll" -> {
                val down = a.optString("dir") != "up"
                announce(if (down) Step.SCROLL_DOWN else Step.SCROLL_UP, "")
                s!!.scroll(down).res("Scrolled", "couldn't scroll here")
            }
            "tap" -> { announce(Step.TAPPING, "the screen"); s!!.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat()).res("Tapped", "tap failed") }
            "tap_text" -> { announce(Step.TAPPING, a.optString("text")); s!!.clickByLabel(listOf(a.optString("text"))).res("Clicked", "I couldn't find that on screen") }
            "type" -> { announce(Step.TYPING, ""); s!!.typeText(a.optString("text")).res("Typed", "no text field is focused") }
            "system" -> { announce(Step.SYSTEM, a.optString("action")); s!!.global(a.optString("action")).res("Done", "that system action failed") }
            "read_screen" -> good("Screen read", s!!.dumpScreenText().ifBlank { "(no readable text on screen)" })
            "wifi" -> toggle(s!!, Intent(Settings.Panel.ACTION_WIFI), listOf("Wi-Fi", "Wi‑Fi", "Use Wi-Fi"), a, "Wi-Fi", announce)
            "bluetooth" -> toggle(s!!, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), listOf("Use Bluetooth", "Bluetooth"), a, "Bluetooth", announce)
            "mobile_data" -> toggle(s!!, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), listOf("Mobile data", "Mobile network", "SIM"), a, "Mobile data", announce)
            "rotate" -> rotate(a.optString("mode", "auto"))
            "email" -> { announce(Step.DRAFTING, "email"); email(a, said, announce) }
            "sms" -> { announce(Step.DRAFTING, "message"); sms(a, said, announce) }
            else -> fail("unknown action \"$name\"")
        }
    }

    // ---------------- media ----------------

    private suspend fun mediaCommand(a: JSONObject, announce: Announcer): ActionResult {
        val secs = a.optInt("seconds", 10).coerceIn(1, 600) * 1000L
        val cmd = a.optString("cmd").lowercase()
        val (step, ok) = when (cmd) {
            "play", "resume" -> { announce(Step.MEDIA_PLAY, ""); Step.MEDIA_PLAY to media.play() }
            "pause", "stop" -> { announce(Step.MEDIA_PAUSE, ""); Step.MEDIA_PAUSE to media.pause() }
            "toggle" -> { announce(Step.MEDIA_PLAY, ""); Step.MEDIA_PLAY to media.toggle() }
            "rewind", "back" -> { announce(Step.MEDIA_REWIND, ""); Step.MEDIA_REWIND to media.seekBy(-secs) }
            "forward", "skip" -> { announce(Step.MEDIA_FORWARD, ""); Step.MEDIA_FORWARD to media.seekBy(secs) }
            "next" -> { announce(Step.MEDIA_NEXT, ""); Step.MEDIA_NEXT to media.next() }
            "previous" -> { announce(Step.MEDIA_PREVIOUS, ""); Step.MEDIA_PREVIOUS to media.previous() }
            else -> return fail("I don't know the media command \"$cmd\"")
        }
        return if (ok) good("Done ($step)") else fail("nothing is playing that I can control")
    }

    // ---------------- apps ----------------

    private suspend fun toggle(s: MarvisAccessibilityService, i: Intent, labels: List<String>, a: JSONObject, what: String, announce: Announcer): ActionResult {
        announce(Step.TOGGLING, what)
        val o = s.toggleSwitch(i, labels, desired(a))
        return if (o.ok) good("$what ${o.message}") else fail(o.message)
    }

    private data class AppMatch(val intent: Intent, val pkg: String, val label: String)

    private fun findApp(query: String): AppMatch? {
        if (query.isBlank()) return null
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = query.trim().lowercase()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return null
        val pkg = hit.activityInfo.packageName
        val i = pm.getLaunchIntentForPackage(pkg) ?: return null
        return AppMatch(i, pkg, hit.loadLabel(pm).toString())
    }

    private suspend fun waitForApp(pkg: String, timeoutMs: Int = 7000) {
        var waited = 0
        while (MarvisAccessibilityService.currentPackage != pkg && waited < timeoutMs) { delay(250); waited += 250 }
    }

    private suspend fun openApp(query: String, announce: Announcer): ActionResult {
        if (query.isBlank()) return fail("no app name given")
        val app = findApp(query) ?: return fail("I couldn't find an app called $query")
        announce(Step.OPENING_APP, app.label)
        launch(app.intent)
        return good("Opened ${app.label}")
    }

    private suspend fun announceOpening(announce: Announcer, n: Int) {
        val pkg = MarvisAccessibilityService.currentPackage.orEmpty()
        if (pkg.contains("youtube", true)) announce(Step.OPENING_VIDEO, "") else announce(Step.OPENING_RESULT, n.toString())
    }

    /** Opens the app (if named), searches, and with [openFirst] also opens the first result ("play ..."). */
    private suspend fun searchInApp(s: MarvisAccessibilityService, appName: String, query: String, openFirst: Boolean, announce: Announcer): ActionResult {
        if (query.isBlank()) return fail("I didn't hear what to search for")
        var label = ""
        if (appName.isNotBlank()) {
            val app = findApp(appName) ?: return fail("I couldn't find an app called $appName")
            label = app.label
            announce(Step.FINDING_APP, app.label)
            launch(app.intent)
            waitForApp(app.pkg)
            delay(1200)
        }
        announce(Step.SEARCHING, query)
        val ok = s.searchInCurrentApp(query)
        val where = if (label.isNotBlank()) " in $label" else ""
        if (!ok) return fail("I couldn't find a search box$where")
        if (!openFirst) return good("Searched for \"$query\"$where. The results are on screen; ask the user which one to open")

        delay(1800)
        announceOpening(announce, 1)
        return if (s.openResult(1)) good("Searched for \"$query\"$where and opened the first result")
        else fail("I searched$where but couldn't open the first result")
    }

    // ---------------- WhatsApp ----------------

    /** Opens WhatsApp, finds the chat (by number link, or by searching the contact), types the message, optionally sends. */
    private suspend fun whatsapp(s: MarvisAccessibilityService, a: JSONObject, said: Boolean, announce: Announcer): ActionResult {
        val app = findApp("WhatsApp") ?: return fail("WhatsApp isn't installed")
        val message = a.optString("message")
        val contact = a.optString("contact").trim()
        val number = a.optString("number").filter { it.isDigit() }

        announce(Step.OPENING_APP, app.label)
        if (number.isNotEmpty()) {
            val link = "https://wa.me/$number" + if (message.isNotBlank()) "?text=" + Uri.encode(message) else ""
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app.pkg))
            waitForApp(app.pkg)
            delay(1600)
        } else {
            launch(app.intent)
            waitForApp(app.pkg)
            delay(1500)
            if (contact.isBlank()) return good("WhatsApp is open")
            announce(Step.FINDING_CONTACT, contact)
            if (!s.searchInCurrentApp(contact)) return fail("I couldn't find WhatsApp's search")
            delay(1400)
            if (!s.clickRowWithText(contact)) return fail("I couldn't find $contact in WhatsApp")
            delay(1200)
            if (message.isNotBlank()) {
                announce(Step.TYPING_MESSAGE, "")
                if (!s.typeInMessageBox(message)) return fail("I couldn't find the message box")
            }
        }

        if (message.isBlank()) return good("The chat is open")
        if (!(a.optBoolean("send") && said)) return good("The message is typed in WhatsApp and not sent")
        announce(Step.SENDING, "")
        delay(500)
        return s.clickByLabel(listOf("Send")).res("Sent", "the message is typed but I couldn't find Send")
    }

    // ---------------- other actions ----------------

    private fun rotate(mode: String): ActionResult {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return fail("I need the 'Modify system settings' permission; I opened that page")
        }
        val cr = ctx.contentResolver
        if (mode == "auto") Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
        else {
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION,
                if (mode == "landscape") Surface.ROTATION_90 else Surface.ROTATION_0)
        }
        return good("Orientation set to $mode")
    }

    private suspend fun email(a: JSONObject, said: Boolean, announce: Announcer): ActionResult {
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(a.optString("to")))
            .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, a.optString("body")))
        return maybeSend(a.optBoolean("send") && said, "email draft", announce)
    }

    private suspend fun sms(a: JSONObject, said: Boolean, announce: Announcer): ActionResult {
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${a.optString("number")}"))
            .putExtra("sms_body", a.optString("message")))
        return maybeSend(a.optBoolean("send") && said, "message draft", announce)
    }

    private suspend fun maybeSend(send: Boolean, what: String, announce: Announcer): ActionResult {
        if (!send) return good("The $what is open and not sent")
        val s = MarvisAccessibilityService.instance ?: return good("The $what is open; enable accessibility so I can press Send")
        announce(Step.SENDING, "")
        delay(1800)
        return s.clickByLabel(listOf("Send", "Send message", "Send SMS")).res("Sent", "the $what is open but I couldn't find Send")
    }

    private suspend fun setReminder(a: JSONObject): ActionResult {
        val at = try { LocalDateTime.parse(a.optString("when")) } catch (e: Exception) { return fail("I couldn't understand that time") }
        if (at.isBefore(LocalDateTime.now())) return fail("that time is already in the past")
        val r = MarvisApp.instance.reminders.create(
            a.optString("title", "Reminder"), at, Repeat.from(a.optString("repeat")), a.optString("kind", "reminder"))
        return good("Saved #${r.id} for ${at.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))}")
    }

    private suspend fun listReminders(): ActionResult {
        val items = MarvisApp.instance.reminders.list().joinToString("; ") {
            "#${it.id} ${it.title} at ${Instant.ofEpochMilli(it.triggerAt).atZone(ZoneId.systemDefault()).toLocalDateTime()} (${it.repeat})"
        }
        return good("Listed", items.ifEmpty { "No pending reminders" })
    }
}
