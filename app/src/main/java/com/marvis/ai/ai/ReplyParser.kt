package com.marvis.ai.ai

import org.json.JSONObject

data class Segment(val emotion: Emotion, val text: String)
data class ParsedReply(
    val display: String,
    val segments: List<Segment>,
    val actions: List<JSONObject>,
    val lang: String? = null,
)

/** Raw model text -> emotion-tagged speech segments + device actions + reply language. */
object ReplyParser {
    private val actionRe = Regex("\\[\\[do:(\\{.*?\\})\\]\\]", RegexOption.DOT_MATCHES_ALL)
    private val langRe = Regex("\\[lang\\s*[:=]\\s*([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)\\]", RegexOption.IGNORE_CASE)
    private val tagRe = Regex("\\[([A-Za-z]{3,12})\\]")

    fun parse(raw: String): ParsedReply {
        val actions = actionRe.findAll(raw).mapNotNull { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }.toList()
        var clean = raw.replace(actionRe, "")
        val lang = langRe.find(clean)?.groupValues?.get(1)
        clean = clean.replace(langRe, "").trim()

        val segs = mutableListOf<Segment>()
        var current = Emotion.NEUTRAL
        var last = 0
        for (m in tagRe.findAll(clean)) {
            val chunk = clean.substring(last, m.range.first).trim()
            if (chunk.isNotEmpty()) segs += Segment(current, chunk)
            current = Emotion.fromTag(m.groupValues[1]) ?: current
            last = m.range.last + 1
        }
        val tail = clean.substring(last).trim()
        if (tail.isNotEmpty()) segs += Segment(current, tail)

        return ParsedReply(segs.joinToString(" ") { it.text }, segs, actions, lang)
    }
}
