package com.marvis.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LockType { SWIPE, PIN, PASSWORD, PATTERN }

/**
 * Everything security-related, kept in Keystore-backed EncryptedSharedPreferences. There is NO plaintext fallback:
 * if secure storage is unavailable on the phone, [available] is false and voice unlock stays off.
 *
 *  - AI secret voice code: stored only as a salted PBKDF2 hash. It can be checked but never read back.
 *  - Device lock code / pattern: stored encrypted. It has to be readable by the app so it can be entered on
 *    the lock screen, so only enable voice unlock if you accept that trade-off.
 *  - Wrong codes are counted; 3 misses lock the check for 5 minutes, 6 or more for 15 minutes.
 */
class SecurityStore(ctx: Context) {
    companion object {
        const val MIN_SECRET_CHARS = 6
        private const val ITERATIONS = 120_000
        private val NUMBER_WORDS = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")

        /** "Open Sesame!" and "open sesame" match; spoken numbers become digits ("one two three" = "123"). */
        fun normalize(s: String): String =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
                .joinToString("") { NUMBER_WORDS[it] ?: it }
    }

    private val prefs: SharedPreferences? = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "marvis_security", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { null }

    val available: Boolean get() = prefs != null

    private val _lockType = MutableStateFlow(
        runCatching { LockType.valueOf(prefs?.getString("lock_type", null) ?: "SWIPE") }.getOrDefault(LockType.SWIPE))
    val lockType: StateFlow<LockType> = _lockType.asStateFlow()

    private val _voiceUnlock = MutableStateFlow(prefs?.getBoolean("voice_unlock", false) ?: false)
    val voiceUnlockEnabled: StateFlow<Boolean> = _voiceUnlock.asStateFlow()

    private val _requireForSend = MutableStateFlow(prefs?.getBoolean("require_for_send", true) ?: true)
    val requireCodeForSend: StateFlow<Boolean> = _requireForSend.asStateFlow()

    private val _hasSecret = MutableStateFlow(prefs?.contains("secret_hash") == true)
    val hasSecret: StateFlow<Boolean> = _hasSecret.asStateFlow()

    private val _hasCredential = MutableStateFlow(prefs?.contains("credential") == true)
    val hasCredential: StateFlow<Boolean> = _hasCredential.asStateFlow()

    // ---- device lock credential ----

    fun setLockType(t: LockType) {
        prefs?.edit()?.putString("lock_type", t.name)?.apply()
        _lockType.value = t
    }

    fun saveCredential(value: String): Boolean {
        val p = prefs ?: return false
        if (value.isBlank()) return false
        p.edit().putString("credential", value).apply()
        _hasCredential.value = true
        return true
    }

    fun clearCredential() {
        prefs?.edit()?.remove("credential")?.apply()
        _hasCredential.value = false
    }

    fun credential(): String? = prefs?.getString("credential", null)

    /** Is everything needed to unlock saved? Swipe / no lock needs no code. */
    fun unlockReady(): Boolean = available && (lockType.value == LockType.SWIPE || hasCredential.value)

    // ---- switches ----

    fun setVoiceUnlock(on: Boolean) {
        prefs?.edit()?.putBoolean("voice_unlock", on)?.apply()
        _voiceUnlock.value = on && available
    }

    fun setRequireCodeForSend(on: Boolean) {
        prefs?.edit()?.putBoolean("require_for_send", on)?.apply()
        _requireForSend.value = on
    }

    // ---- AI secret voice code ----

    /** @return false if the phrase is too short or storage is unavailable. */
    fun saveSecret(phrase: String): Boolean {
        val p = prefs ?: return false
        val n = normalize(phrase)
        if (n.length < MIN_SECRET_CHARS) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("secret_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("secret_hash", Base64.encodeToString(hash(n, salt), Base64.NO_WRAP))
            .remove("fail_count").remove("lock_until")
            .apply()
        _hasSecret.value = true
        return true
    }

    fun clearSecret() {
        prefs?.edit()?.remove("secret_salt")?.remove("secret_hash")?.apply()
        _hasSecret.value = false
        setVoiceUnlock(false)
    }

    /** Checks a spoken phrase. Callers must check [lockoutRemainingMs] first. */
    fun verifySecret(spoken: String): Boolean {
        val p = prefs ?: return false
        val salt = p.getString("secret_salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = p.getString("secret_hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val n = normalize(spoken)
        if (n.isEmpty()) return false
        return MessageDigest.isEqual(stored, hash(n, salt))
    }

    fun resetFailures() {
        prefs?.edit()?.remove("fail_count")?.remove("lock_until")?.apply()
    }

    fun recordFailure() {
        val p = prefs ?: return
        val n = p.getInt("fail_count", 0) + 1
        val e = p.edit().putInt("fail_count", n)
        if (n >= 3) {
            val minutes = if (n >= 6) 15L else 5L
            e.putLong("lock_until", System.currentTimeMillis() + minutes * 60_000L)
        }
        e.apply()
    }

    fun lockoutRemainingMs(): Long =
        ((prefs?.getLong("lock_until", 0L) ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun hash(normalized: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(normalized.toCharArray(), salt, ITERATIONS, 256)).encoded
}
