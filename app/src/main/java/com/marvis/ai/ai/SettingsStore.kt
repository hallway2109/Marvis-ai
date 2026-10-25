package com.marvis.ai.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** API key in EncryptedSharedPreferences; everything else in plain prefs. All exposed as StateFlows. */
class SettingsStore(ctx: Context) {
    companion object {
        const val DEFAULT_WAKE = "Marvis"
        val WAKE_PRESETS = listOf("Marvis", "Jarvis", "Friday")
        /** label to recognizer language tag ("auto" = device language + auto detection where supported). */
        val SPEECH_LANGS = listOf("Auto" to "auto", "English" to "en-IN", "हिन्दी" to "hi-IN", "日本語" to "ja-JP")
        private const val K_KEY = "openrouter_key"
        private const val K_MODEL = "model"
        private const val K_ALWAYS = "always_on"
        private const val K_WAKE = "wake_name"
        private const val K_OVERLAY = "overlay_on"
        private const val K_LANG = "speech_lang"
        private const val K_THINK = "think_cue"
        private const val K_WEB = "web_search"
        private const val K_REPEAT = "ask_repeat"
    }

    private val secure: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "marvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) {
        ctx.getSharedPreferences("marvis_secure_fallback", Context.MODE_PRIVATE)
    }
    private val plain = ctx.getSharedPreferences("marvis_prefs", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(secure.getString(K_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(
        plain.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: OpenRouterRepository.DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _alwaysOn = MutableStateFlow(plain.getBoolean(K_ALWAYS, false))
    val alwaysOn: StateFlow<Boolean> = _alwaysOn.asStateFlow()

    private val _wakeName = MutableStateFlow(
        plain.getString(K_WAKE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WAKE)
    val wakeName: StateFlow<String> = _wakeName.asStateFlow()

    private val _overlayOn = MutableStateFlow(plain.getBoolean(K_OVERLAY, false))
    val overlayOn: StateFlow<Boolean> = _overlayOn.asStateFlow()

    private val _speechLang = MutableStateFlow(plain.getString(K_LANG, "auto") ?: "auto")
    val speechLang: StateFlow<String> = _speechLang.asStateFlow()

    private val _thinkCue = MutableStateFlow(plain.getBoolean(K_THINK, true))
    val thinkCue: StateFlow<Boolean> = _thinkCue.asStateFlow()

    private val _webSearch = MutableStateFlow(plain.getBoolean(K_WEB, true))
    val webSearch: StateFlow<Boolean> = _webSearch.asStateFlow()

    private val _askRepeat = MutableStateFlow(plain.getBoolean(K_REPEAT, true))
    val askRepeat: StateFlow<Boolean> = _askRepeat.asStateFlow()
    fun setAskRepeat(on: Boolean) { plain.edit().putBoolean(K_REPEAT, on).apply(); _askRepeat.value = on }

    fun saveApiKey(k: String) {
        val v = k.trim()
        secure.edit().putString(K_KEY, v).apply()
        _apiKey.value = v
    }

    fun saveModel(m: String) {
        val v = m.trim().ifEmpty { OpenRouterRepository.DEFAULT_MODEL }
        plain.edit().putString(K_MODEL, v).apply()
        _model.value = v
    }

    fun setAlwaysOn(on: Boolean) { plain.edit().putBoolean(K_ALWAYS, on).apply(); _alwaysOn.value = on }
    fun setOverlayOn(on: Boolean) { plain.edit().putBoolean(K_OVERLAY, on).apply(); _overlayOn.value = on }
    fun setSpeechLang(v: String) { plain.edit().putString(K_LANG, v).apply(); _speechLang.value = v }
    fun setThinkCue(on: Boolean) { plain.edit().putBoolean(K_THINK, on).apply(); _thinkCue.value = on }
    fun setWebSearch(on: Boolean) { plain.edit().putBoolean(K_WEB, on).apply(); _webSearch.value = on }

    fun setWakeName(name: String) {
        val v = name.trim().replace(Regex("[^\\p{L} ]"), "").replace(Regex("\\s+"), " ").take(20).trim()
            .ifEmpty { DEFAULT_WAKE }
        plain.edit().putString(K_WAKE, v).apply()
        _wakeName.value = v
    }
}
