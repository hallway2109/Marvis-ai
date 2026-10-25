package com.marvis.ai.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import com.marvis.ai.MarvisApp
import com.marvis.ai.reminders.Repeat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ActionResult(val ok: Boolean, val message: String, val data: String? = null)

/** Executes the [[do:{...}]] actions the model emits. */
class ActionRouter(private val ctx: Context) {

    private fun good(msg: String = "done", data: String? = null) = ActionResult(true, msg, data)
    private fun fail(msg: String) = ActionResult(false, msg)
    private fun Boolean.res(yes: String, no: String) = if (this) good(yes) else fail(no)

    private val noA11y = "the Accessibility service is off; enable Marvis in Accessibility settings"

    private fun desired(a: JSONObject): Boolean? = when (a.optString("state")) {
        "on" -> true; "off" -> false; else -> null
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (MarvisAccessibilityService.instance ?: ctx).startActivity(i)
    }

    suspend fun execute(a: JSONObject, userText: String): ActionResult {
        val name = a.optString("a")
        if (!com.marvis.ai.MarvisRuntime.active) return fail("Marvis is stopped. Open the app to wake me up")
        // Reminder tools don't touch the screen
        when (name) {
            "reminder" -> return setReminder(a)
            "list_reminders" -> return listReminders()
            "cancel_reminder" -> { MarvisApp.instance.reminders.cancel(a.optLong("id")); return good("Reminder cancelled") }
        }

        AppForeground.ensureBackground()          // step aside so we act on the OTHER app
        val s = MarvisAccessibilityService.instance
        val needsA11y = setOf("scroll", "tap", "tap_text", "type", "system", "wifi", "bluetooth", "mobile_data", "read_screen")
        if (name in needsA11y && s == null) return fail(noA11y)

        // Safety net: only auto-send if the user actually said "send".
        val userSaidSend = Regex("\\bsend\\b|\\bbhej", RegexOption.IGNORE_CASE).containsMatchIn(userText)

        return when (name) {
            "open_app" -> openApp(a.optString("name"))
            "scroll" -> s!!.scroll(a.optString("dir") != "up").res("Scrolled", "couldn't scroll here")
            "tap" -> s!!.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat()).res("Tapped", "tap failed")
            "tap_text" -> s!!.clickByLabel(listOf(a.optString("text"))).res("Clicked", "I couldn't find that on screen")
            "type" -> s!!.typeText(a.optString("text")).res("Typed", "no text field is focused")
            "system" -> s!!.global(a.optString("action")).res("Done", "that system action failed")
            "read_screen" -> good("Screen read", s!!.dumpScreenText().ifBlank { "(no readable text on screen)" })
            "wifi" -> toggle(s!!, Intent(Settings.Panel.ACTION_WIFI), listOf("Wi-Fi", "Wi‑Fi", "Use Wi-Fi"), a, "Wi-Fi")
            "bluetooth" -> toggle(s!!, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), listOf("Use Bluetooth", "Bluetooth"), a, "Bluetooth")
            "mobile_data" -> toggle(s!!, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), listOf("Mobile data", "Mobile network", "SIM"), a, "Mobile data")
            "rotate" -> rotate(a.optString("mode", "auto"))
            "email" -> email(a, userSaidSend)
            "sms" -> sms(a, userSaidSend)
            else -> fail("unknown action \"$name\"")
        }
    }

    private suspend fun toggle(s: MarvisAccessibilityService, i: Intent, labels: List<String>, a: JSONObject, what: String): ActionResult {
        val o = s.toggleSwitch(i, labels, desired(a))
        return if (o.ok) good("$what ${o.message}") else fail(o.message)
    }

    private fun openApp(query: String): ActionResult {
        if (query.isBlank()) return fail("no app name given")
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = query.trim().lowercase()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return fail("I couldn't find an app called $query")
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return fail("that app can't be launched")
        launch(i)
        return good("Opened ${hit.loadLabel(pm)}")
    }

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

    private suspend fun email(a: JSONObject, userSaidSend: Boolean): ActionResult {
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(a.optString("to")))
            .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, a.optString("body")))
        return maybeSend(a.optBoolean("send") && userSaidSend, "email draft")
    }

    private suspend fun sms(a: JSONObject, userSaidSend: Boolean): ActionResult {
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${a.optString("number")}"))
            .putExtra("sms_body", a.optString("message")))
        return maybeSend(a.optBoolean("send") && userSaidSend, "message draft")
    }

    private suspend fun maybeSend(send: Boolean, what: String): ActionResult {
        if (!send) return good("The $what is open and not sent")
        val s = MarvisAccessibilityService.instance ?: return good("The $what is open; enable accessibility so I can press Send")
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
