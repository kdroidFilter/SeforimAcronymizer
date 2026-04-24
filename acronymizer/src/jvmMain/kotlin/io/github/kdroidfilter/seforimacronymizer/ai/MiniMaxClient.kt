package io.github.kdroidfilter.seforimacronymizer.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ResponseFormat(val type: String = "json_object")

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val max_completion_tokens: Int = 1024,
    val response_format: ResponseFormat = ResponseFormat(),
    val reasoning_split: Boolean = true,
)

@Serializable
private data class ChatResponseMessage(val content: String = "")

@Serializable
private data class ChatResponseChoice(val message: ChatResponseMessage)

@Serializable
private data class ChatResponse(val choices: List<ChatResponseChoice> = emptyList())

@Serializable
private data class EnrichmentOutput(val additions: List<String> = emptyList())

/**
 * Minimal HTTP client for MiniMax's OpenAI-compatible chat completions endpoint,
 * specialized for acronym enrichment.
 *
 * MiniMax-M2.7 is a reasoning model; `reasoning_split: true` keeps the `<think>` block
 * out of the assistant content so the JSON output can be parsed directly.
 */
class MiniMaxClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.minimax.io/v1",
    private val model: String = "MiniMax-M2.7",
    requestTimeoutMs: Long = 180_000,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http: HttpClient =
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = requestTimeoutMs
            }
        }

    fun close() = http.close()

    /**
     * Ask the model to produce bibliographic variants missing from [existing] for [title].
     * Returns a deduplicated list of candidate additions; empty on any failure.
     */
    suspend fun askForAcronymAdditions(
        title: String,
        existing: List<String>,
    ): List<String> {
        val userPayload =
            buildString {
                append("Title: ").append(title).append('\n')
                append("Existing variants:\n")
                existing.forEach { append("  - ").append(it).append('\n') }
            }
        val req =
            ChatRequest(
                model = model,
                messages =
                    listOf(
                        ChatMessage("system", SYSTEM_PROMPT),
                        ChatMessage("user", userPayload),
                    ),
            )
        val rawBody =
            http.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(json.encodeToString(ChatRequest.serializer(), req))
            }.bodyAsText()
        val response = json.decodeFromString(ChatResponse.serializer(), rawBody)
        val content =
            response.choices.firstOrNull()?.message?.content
                ?: error("MiniMax returned no choices. raw=${rawBody.take(400)}")
        val cleaned = extractJson(content)
        return json.decodeFromString(EnrichmentOutput.serializer(), cleaned)
            .additions
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    companion object {
        /**
         * Cleanup LLM output so it can be parsed as JSON. Handles reasoning models that emit
         * a `<think>...</think>` preamble and/or wrap the JSON in markdown code fences,
         * and tolerates stray text around the object.
         */
        internal fun extractJson(s: String): String {
            var work = s.trim()
            val thinkEnd = work.indexOf("</think>")
            if (thinkEnd >= 0) work = work.substring(thinkEnd + "</think>".length).trim()
            if (work.startsWith("```")) {
                work = work.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
            }
            val start = work.indexOf('{')
            val end = work.lastIndexOf('}')
            if (start >= 0 && end > start) work = work.substring(start, end + 1)
            return work
        }

        private val SYSTEM_PROMPT =
            """
            You are an expert in Hebrew rabbinic bibliography.

            INPUT: a Hebrew book title and a list of existing acronyms/variants for it.
            OUTPUT: strict JSON of shape {"additions": ["...", "..."]} listing ONLY missing variants that should also be indexed.

            Rules:
            1. PRIMARY GOAL: for every acronym or author-name that starts with a Hebrew grammatical prefix letter
               (ה, ו, ב, ל, מ, ש, כ) followed by 2+ Hebrew letters, generate the form WITHOUT that leading prefix letter.
               This INCLUDES producing the bare acronym ALONE (no surrounding words) when it is itself a well-known bibliographic reference.
               Examples:
                 "הריב״ש"      -> add "ריב״ש", "ריבש"           (the bare acronym)
                 "תשובות הריב״ש" -> add "שו״ת ריב״ש", "תשובות ריב״ש", "ריב״ש", "ריבש"
                 "הרמב״ן"      -> add "רמב״ן", "רמבן"
                 "השגות הרמב״ן על ספר המצוות" -> add "השגות רמב״ן על סמ״ג", "רמב״ן על סמ״ג", "רמב״ן"
                 "הגהות רבי עקיבא איגר על שלחן ערוך אבן העזר" -> add "הגהות רעק״א על אהע״ז", "רעק״א אהע״ז", "רעק״א"
                 "הגהות יעב״ץ על ביצה" -> add "יעב״ץ ביצה", "יעב״ץ על ביצה", "יעב״ץ"
            2. For each newly produced form, also generate typographic variants: with/without gershayim (״),
               with ASCII double quote (") , with/without periods between letters, with/without hyphen and maqaf (־).
            3. DO NOT repeat any variant already present in the input list (compare case/punctuation-insensitive).
            4. DO NOT hallucinate. Every addition must be a plausible contraction of the given title
               or a well-attested form in rabbinic literature. Do NOT invent authors, sections, or tractates.
            5. DO NOT produce truncated garbage words. Never strip prefix letters from common Hebrew words
               that are not themselves acronyms (e.g. do NOT strip ה from הגהות / הלכות / הון / הר — those are real words, not grammatical prefixes).
               Only strip a Hebrew grammatical prefix letter when the remaining stem is itself a recognizable acronym (contains a gershayim / is 2-5 letters) or a well-known author name.
            6. If nothing is missing, return {"additions": []}.
            7. Return ONLY JSON. No commentary, no explanation, no markdown fencing.
            """.trimIndent()
    }
}
