package com.marvis.ai.ai

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Turn(val role: String, val text: String, val imageB64: String? = null)

/** A finished model answer. */
data class Completion(
    val text: String,
    val truncated: Boolean,
    val sources: List<String> = emptyList(),
    val webDowngraded: Boolean = false,
)

class ApiException(val kind: Kind, message: String, val code: Int = 0) : Exception(message) {
    enum class Kind { AUTH, CREDITS, RATE_LIMIT, SERVER, MODEL, NETWORK, OTHER }
}

/**
 * OpenRouter chat-completions client.
 *  - Every network call is a cancellable coroutine: cancelling the job cancels the HTTP call immediately,
 *    which is how "stop" kills an in-flight search/generation.
 *  - 429/5xx: backoff retries, then falls back to "openrouter/free".
 *  - Optional web search through OpenRouter's web plugin (billed by OpenRouter per result, so it needs credits).
 */
class OpenRouterRepository(private val settings: SettingsStore) {
    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val KEY_ENDPOINT = "https://openrouter.ai/api/v1/auth/key"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash:free"
        const val FALLBACK_MODEL = "openrouter/free"
        private const val MAX_TOKENS = 1100
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    suspend fun checkKey() {
        val key = settings.apiKey.value
        if (key.isBlank()) throw ApiException(ApiException.Kind.AUTH, "API key missing")
        val req = Request.Builder().url(KEY_ENDPOINT).header("Authorization", "Bearer $key").build()
        try {
            http.newCall(req).await().use { r ->
                if (!r.isSuccessful) throw ApiException(kindFor(r.code), "Key check failed (${r.code})", r.code)
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "No connection: ${e.message}")
        }
    }

    suspend fun complete(turns: List<Turn>, system: String, webSearch: Boolean = false): Completion {
        val hasImage = turns.any { it.imageB64 != null }
        return try {
            withWebFallback(turns, system, webSearch)
        } catch (e: ApiException) {
            // Model may not accept images: retry once as text-only with a note.
            if (hasImage && (e.kind == ApiException.Kind.MODEL || e.kind == ApiException.Kind.OTHER)) {
                val stripped = turns.mapIndexed { i, t ->
                    if (i == turns.lastIndex) t.copy(imageB64 = null,
                        text = t.text + "\n[The screenshot could not be attached: this model has no image support.]")
                    else t.copy(imageB64 = null)
                }
                withWebFallback(stripped, system, webSearch)
            } else throw e
        }
    }

    /** If web search is refused for lack of credits, answer without it instead of failing. */
    private suspend fun withWebFallback(turns: List<Turn>, system: String, web: Boolean): Completion {
        if (!web) return completeInner(turns, system, false)
        return try {
            completeInner(turns, system, true)
        } catch (e: ApiException) {
            if (e.kind == ApiException.Kind.CREDITS) completeInner(turns, system, false).copy(webDowngraded = true)
            else throw e
        }
    }

    private suspend fun completeInner(turns: List<Turn>, system: String, web: Boolean): Completion =
        withContext(Dispatchers.IO) {
            val key = settings.apiKey.value
            if (key.isBlank()) throw ApiException(ApiException.Kind.AUTH, "API key missing")
            val primary = settings.model.value.ifBlank { DEFAULT_MODEL }
            val models = listOf(primary, FALLBACK_MODEL).distinct()

            var last: ApiException? = null
            for (m in models) {
                for (attempt in 0..2) {
                    currentCoroutineContext().ensureActive()
                    try {
                        return@withContext callOnce(key, m, turns, system, web)
                    } catch (e: ApiException) {
                        last = e
                        when (e.kind) {
                            ApiException.Kind.AUTH, ApiException.Kind.CREDITS -> throw e
                            ApiException.Kind.RATE_LIMIT, ApiException.Kind.SERVER, ApiException.Kind.NETWORK ->
                                if (attempt < 2) delay(1200L * (attempt + 1))
                            ApiException.Kind.MODEL, ApiException.Kind.OTHER -> break
                        }
                    }
                }
            }
            throw last ?: ApiException(ApiException.Kind.OTHER, "Unknown error")
        }

    private suspend fun callOnce(key: String, model: String, turns: List<Turn>, system: String, web: Boolean): Completion {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) {
            val content: Any = if (t.imageB64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", t.text))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${t.imageB64}")))
            } else t.text
            msgs.put(JSONObject().put("role", t.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("temperature", 0.8)
            .put("max_tokens", MAX_TOKENS)
        if (web) body.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 4)))

        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://marvis.local")
            .header("X-Title", "Marvis")
            .post(body.toString().toRequestBody(json))
            .build()

        try {
            http.newCall(req).await().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(kindFor(r.code), errorMessage(text, r.code), r.code)
                val j = JSONObject(text)
                if (j.has("error") && !j.has("choices")) {
                    val err = j.optJSONObject("error")
                    val code = err?.optInt("code", 500) ?: 500
                    throw ApiException(kindFor(code), err?.optString("message") ?: "Provider error", code)
                }
                val choice = j.optJSONArray("choices")?.optJSONObject(0)
                val msg = choice?.optJSONObject("message")
                val content = msg?.optString("content", "")?.trim().orEmpty()
                if (content.isEmpty()) throw ApiException(ApiException.Kind.SERVER, "Empty reply from model")
                return Completion(
                    text = content,
                    truncated = choice?.optString("finish_reason") == "length",
                    sources = citations(msg),
                )
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "No connection: ${e.message}")
        }
    }

    private fun citations(msg: JSONObject?): List<String> {
        val ann = msg?.optJSONArray("annotations") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until ann.length()) {
            val c = ann.optJSONObject(i)?.optJSONObject("url_citation") ?: continue
            val label = c.optString("title").ifBlank { c.optString("url") }.take(60)
            if (label.isNotBlank()) out += label
        }
        return out.toList()
    }

    private fun kindFor(code: Int) = when (code) {
        401, 403 -> ApiException.Kind.AUTH
        402 -> ApiException.Kind.CREDITS
        429 -> ApiException.Kind.RATE_LIMIT
        408, 500, 502, 503, 504 -> ApiException.Kind.SERVER
        404 -> ApiException.Kind.MODEL
        else -> ApiException.Kind.OTHER
    }

    private fun errorMessage(body: String, code: Int): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
}

/** Cancellable OkHttp call: cancelling the coroutine cancels the request. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
