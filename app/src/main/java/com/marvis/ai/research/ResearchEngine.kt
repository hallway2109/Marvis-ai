package com.marvis.ai.research

import com.marvis.ai.ai.OpenRouterRepository
import com.marvis.ai.ai.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous research pipeline:  PLAN -> GATHER (in parallel) -> SYNTHESISE.
 *
 *  1. PLAN: the model splits the topic into 3-4 search angles.
 *  2. GATHER: every angle is looked up in parallel in:
 *       - the web, through OpenRouter's web-search plugin (this is the Google-style source; it is billed by OpenRouter
 *         per result, so it needs credits and is skipped automatically without them)
 *       - Wikipedia (free public API)
 *       - DuckDuckGo instant answers (free public API)
 *       - YouTube, only if you saved a YouTube Data API key in Settings
 *     A source that fails or returns nothing is simply skipped.
 *  3. SYNTHESISE: the model writes a spoken summary plus a full structured report using ONLY what was found,
 *     and Marvis appends the list of sources actually used.
 * Google itself has no free search API, so it is not queried directly.
 */
class ResearchEngine(private val repo: OpenRouterRepository, private val settings: SettingsStore) {

    data class Finding(val source: String, val title: String, val text: String, val url: String?)
    data class Report(val topic: String, val summary: String, val body: String, val sourcesUsed: List<String>, val webWorked: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val userAgent = "Marvis/1.0 (Android voice assistant)"

    /** @param wikiLang Wikipedia language code (en, hi, ja...). @param languageName language the report is written in. */
    suspend fun run(topic: String, languageName: String, wikiLang: String, progress: (String) -> Unit): Report = coroutineScope {
        progress("Planning the research")
        val queries = plan(topic)
        progress("Searching ${queries.size} angles: " + queries.joinToString(" | "))

        val webWorked = AtomicBoolean(false)
        val gate = Semaphore(3)                                   // at most 3 lookups at once
        val jobs = queries.flatMap { q ->
            listOf(
                async { gate.withPermit { web(q, webWorked) } },
                async { gate.withPermit { wiki(q, wikiLang) } },
                async { gate.withPermit { duck(q) } },
                async { gate.withPermit { youtube(q) } },
            )
        }
        val findings = jobs.awaitAll().flatten()
        if (findings.isEmpty()) throw IllegalStateException("none of the sources returned anything. Check your internet and OpenRouter credits")

        val bySource = findings.groupingBy { it.source }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
        progress("Found ${findings.size} results ($bySource). Writing the report")
        synthesise(topic, findings, languageName, webWorked.get())
    }

    // ---------------- 1. plan ----------------

    private suspend fun plan(topic: String): List<String> {
        val fallback = listOf(topic, "$topic latest news", "$topic pros and cons")
        return try {
            val c = repo.ask(
                system = "Return ONLY a JSON array of 3 or 4 short web search queries that together cover the topic from different angles " +
                    "(basics, latest developments, controversy or pros and cons, practical advice). No prose, no code fences.",
                user = topic, maxTokens = 220, effort = "low")
            val raw = c.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1))
            val list = (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotBlank() }.take(4)
            if (list.isEmpty()) fallback else list
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }
    }

    // ---------------- 2. sources ----------------

    private suspend fun <T> safe(default: T, block: suspend () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { default }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    private suspend fun web(q: String, worked: AtomicBoolean): List<Finding> = safe(emptyList()) {
        val c = repo.ask(
            system = "You are a research assistant. Use the web search results. Reply with 5 to 8 concise factual bullet points " +
                "with figures, dates and names. No introduction.",
            user = q, webSearch = true, maxTokens = 650, effort = "low")
        if (c.webDowngraded) return@safe emptyList()           // no credits: this was not a web answer
        worked.set(true)
        val src = if (c.sources.isEmpty()) "" else "\nSources: " + c.sources.joinToString("; ")
        listOf(Finding("Web", q, c.text + src, null))
    }

    private suspend fun wiki(q: String, lang: String): List<Finding> = safe(emptyList()) {
        val enc = URLEncoder.encode(q, "UTF-8")
        val host = "https://$lang.wikipedia.org"
        val search = get("$host/w/api.php?action=query&list=search&srsearch=$enc&srlimit=2&format=json") ?: return@safe emptyList()
        val hits = JSONObject(search).optJSONObject("query")?.optJSONArray("search") ?: return@safe emptyList()
        val out = ArrayList<Finding>()
        for (i in 0 until minOf(2, hits.length())) {
            val title = hits.getJSONObject(i).optString("title")
            if (title.isBlank()) continue
            val page = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            val sum = get("$host/api/rest_v1/page/summary/$page") ?: continue
            val j = JSONObject(sum)
            val text = j.optString("extract")
            if (text.isNotBlank()) {
                out += Finding("Wikipedia", title, text.take(1000), j.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page"))
            }
        }
        out
    }

    private suspend fun duck(q: String): List<Finding> = safe(emptyList()) {
        val enc = URLEncoder.encode(q, "UTF-8")
        val body = get("https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1") ?: return@safe emptyList()
        val j = JSONObject(body)
        val out = ArrayList<Finding>()
        val abs = j.optString("AbstractText")
        if (abs.isNotBlank()) out += Finding("DuckDuckGo", j.optString("Heading").ifBlank { q }, abs.take(1000), j.optString("AbstractURL").ifBlank { null })
        val related = j.optJSONArray("RelatedTopics")
        if (related != null) {
            val texts = (0 until minOf(3, related.length())).mapNotNull { related.optJSONObject(it)?.optString("Text")?.takeIf { t -> t.isNotBlank() } }
            if (texts.isNotEmpty()) out += Finding("DuckDuckGo", "Related to $q", texts.joinToString("\n") { "- $it" }.take(900), null)
        }
        out
    }

    private suspend fun youtube(q: String): List<Finding> = safe(emptyList()) {
        val key = settings.youtubeKey.value
        if (key.isBlank()) return@safe emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val body = get("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&q=$enc&key=$key")
            ?: return@safe emptyList()
        val items = JSONObject(body).optJSONArray("items") ?: return@safe emptyList()
        val lines = ArrayList<String>()
        var firstUrl: String? = null
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val sn = it.optJSONObject("snippet") ?: continue
            val id = it.optJSONObject("id")?.optString("videoId").orEmpty()
            if (firstUrl == null && id.isNotBlank()) firstUrl = "https://www.youtube.com/watch?v=$id"
            lines += "- ${sn.optString("title")} (${sn.optString("channelTitle")})"
        }
        if (lines.isEmpty()) emptyList() else listOf(Finding("YouTube", "Top videos for $q", lines.joinToString("\n"), firstUrl))
    }

    // ---------------- 3. synthesise ----------------

    private suspend fun synthesise(topic: String, findings: List<Finding>, languageName: String, webWorked: Boolean): Report {
        val material = findings.mapIndexed { i, f ->
            "[${i + 1}] ${f.source} - ${f.title}\n${f.text.take(1200)}"
        }.joinToString("\n\n").take(14_000)

        val c = repo.ask(
            system = "You are a careful research analyst. Write in $languageName. Use ONLY the numbered findings; if sources disagree " +
                "or the data is thin, say so. Output exactly two parts.\n" +
                "SUMMARY: 4 to 6 plain sentences that can be read aloud (no markdown, no lists).\n" +
                "REPORT: a markdown report with the headings Overview, Key findings (bullet points with facts), " +
                "Different viewpoints and caveats, and What to do next. Do not write a sources section.",
            user = "Topic: $topic\n\nFindings:\n$material", maxTokens = 1700, effort = null)

        val text = c.text
        val s = text.indexOf("SUMMARY:", ignoreCase = true)
        val r = text.indexOf("REPORT:", ignoreCase = true)
        val summary = if (s >= 0 && r > s) text.substring(s + 8, r).trim()
                      else text.split(Regex("(?<=[.!?])\\s+")).take(4).joinToString(" ").trim()
        val body = (if (r >= 0) text.substring(r + 7) else text).trim()

        val used = findings.map { f -> "${f.source}: ${f.title}" + (f.url?.let { " ($it)" } ?: "") }.distinct()
        val full = body + "\n\n## Sources used\n" + used.joinToString("\n") { "- $it" } +
            (if (!webWorked) "\n\nNote: live web search was not available (it needs OpenRouter credits), so this report uses Wikipedia and DuckDuckGo only." else "")
        return Report(topic, summary, full, used, webWorked)
    }
}
