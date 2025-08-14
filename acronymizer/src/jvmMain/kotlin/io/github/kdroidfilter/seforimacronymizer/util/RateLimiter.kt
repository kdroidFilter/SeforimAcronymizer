package io.github.kdroidfilter.seforimacronymizer.util

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

// --- Rate-limit configuration (override via env if you like) ---
private val TPM_LIMIT: Int =
    System.getenv("OPENAI_TPM_LIMIT")?.toIntOrNull() ?: 30_000   // from error: 30k TPM
private val EST_TOKENS_PER_REQ: Int =
    System.getenv("OPENAI_EST_TOKENS_PER_REQ")?.toIntOrNull() ?: 1_400
private val BASE_DELAY_MS: Long =
    System.getenv("OPENAI_BASE_DELAY_MS")?.toLongOrNull() ?: 1_200L

// Derived minimal delay between calls so EST_TOKENS_PER_REQ * calls/min <= TPM_LIMIT
@Volatile private var lastCallAtMs = 0L
private val minDelayBetweenCallsMs: Long by lazy {
    val callsPerMin = (TPM_LIMIT.toDouble() / EST_TOKENS_PER_REQ.toDouble())
        .coerceAtLeast(1.0)
    (60_000.0 / callsPerMin).roundToLong()
}

suspend fun paceByTpm() {
    val now = System.currentTimeMillis()
    val wait = (lastCallAtMs + minDelayBetweenCallsMs) - now
    if (wait > 0) delay(wait)
    lastCallAtMs = System.currentTimeMillis()
}

// --- Retry utilities for 429 Too Many Requests ---
private fun isRateLimit(t: Throwable): Boolean {
    val m = t.message?.lowercase().orEmpty()
    return "rate limit" in m || "429" in m || "rate_limit_exceeded" in m
}

private fun retryAfterMsFromMessage(t: Throwable): Long? {
    val msg = t.message ?: return null
    val re = Regex("try again in\\s*([0-9.]+)s", RegexOption.IGNORE_CASE)
    val match = re.find(msg) ?: return null
    return (match.groupValues[1].toDouble() * 1000).roundToLong()
}

suspend fun <T> withOpenAiRateLimitRetries(
    maxRetries: Int = 8,
    baseDelayMs: Long = BASE_DELAY_MS,
    block: suspend () -> T
): T {
    var attempt = 0
    var last: Throwable? = null
    while (attempt <= maxRetries) {
        try {
            return block()
        } catch (t: Throwable) {
            if (!isRateLimit(t)) throw t
            last = t

            val retryAfter = retryAfterMsFromMessage(t)
            val backoff = retryAfter ?: (baseDelayMs * 2.0.pow(attempt.toDouble())).toLong()
            val jitter = Random.nextLong(0L, baseDelayMs)
            val sleep = backoff + jitter

            println("429 rate limit — backing off ${sleep}ms (attempt ${attempt + 1}/$maxRetries)")
            delay(sleep)
            attempt++
        }
    }
    throw last ?: IllegalStateException("Rate limit retries exhausted")
}
