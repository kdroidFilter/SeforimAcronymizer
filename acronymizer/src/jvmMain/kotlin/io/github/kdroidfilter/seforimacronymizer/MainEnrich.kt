package io.github.kdroidfilter.seforimacronymizer

import io.github.kdroidfilter.seforimacronymizer.ai.MiniMaxClient
import io.github.kdroidfilter.seforimacronymizer.data.repository.AcronymizerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TS_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun ts() = LocalDateTime.now().format(TS_FORMATTER)
private fun log(msg: String) = println("[${ts()}] $msg")

private const val BATCH_SIZE = 200
private const val RETRY_DELAY_MS = 5_000L

/**
 * Second-pass acronymizer: iterates over books in `acronymizer.db` that have not been
 * enriched yet, asks MiniMax for missing bibliographic variants, and inserts them.
 *
 * Configuration (all via env):
 *   - MINIMAX_API_KEY (required)
 *   - acronymizer_db  (default: `acronymizer.db` in the working directory)
 *   - MINIMAX_MODEL   (default: `MiniMax-M2.7`)
 *   - MINIMAX_BASE_URL (default: `https://api.minimax.io/v1`)
 *   - ENRICH_CONCURRENCY (default: 2)
 *   - ENRICH_LIMIT       (optional; cap total books processed — handy for tests)
 */
fun main(): Unit =
    runBlocking {
        val apiKey =
            System.getenv("MINIMAX_API_KEY")
                ?: error("MINIMAX_API_KEY env var is required")
        val dbPath = System.getenv("acronymizer_db") ?: "acronymizer.db"
        val model = System.getenv("MINIMAX_MODEL") ?: "MiniMax-M2.7"
        val baseUrl = System.getenv("MINIMAX_BASE_URL") ?: "https://api.minimax.io/v1"
        val concurrency = System.getenv("ENRICH_CONCURRENCY")?.toIntOrNull()?.coerceIn(1, 64) ?: 2
        val globalLimit = System.getenv("ENRICH_LIMIT")?.toLongOrNull()

        log("== MiniMax enrichment pass ==")
        log("DB           : $dbPath")
        log("Base URL     : $baseUrl")
        log("Model        : $model")
        log("Concurrency  : $concurrency")
        log("Global limit : ${globalLimit ?: "none"}")

        val repo = AcronymizerRepository(dbPath)
        val (alreadyEnriched, totalBooks) = repo.enrichmentProgress()
        log("Progress     : $alreadyEnriched / $totalBooks already enriched")

        val client = MiniMaxClient(apiKey = apiKey, baseUrl = baseUrl, model = model)
        val semaphore = Semaphore(concurrency)

        var processed = 0L
        try {
            while (true) {
                val remainingQuota = globalLimit?.let { (it - processed).coerceAtLeast(0L) }
                if (remainingQuota == 0L) {
                    log("Global limit reached, stopping.")
                    break
                }
                val batchSize =
                    if (remainingQuota == null) BATCH_SIZE.toLong() else minOf(BATCH_SIZE.toLong(), remainingQuota)
                val batch = repo.getBooksToEnrich(batchSize)
                if (batch.isEmpty()) {
                    log("No more books to enrich. Done.")
                    break
                }
                log("Fetched batch of ${batch.size} books")
                val startIndex = processed
                coroutineScope {
                    batch
                        .mapIndexed { i, book ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    processBook(repo, client, book, startIndex + i + 1, totalBooks)
                                }
                            }
                        }.awaitAll()
                }
                processed += batch.size
            }
        } finally {
            client.close()
            val (finalEnriched, finalTotal) = repo.enrichmentProgress()
            log("Final progress: $finalEnriched / $finalTotal")
        }
    }

private suspend fun processBook(
    repo: AcronymizerRepository,
    client: MiniMaxClient,
    book: AcronymizerRepository.BookToEnrich,
    globalIndex: Long,
    total: Long,
) {
    val started = System.currentTimeMillis()
    val additions =
        runCatching { client.askForAcronymAdditions(book.title, book.existingAcronyms) }
            .getOrElse { first ->
                log("[$globalIndex/$total] retry after error: ${first.message}")
                delay(RETRY_DELAY_MS)
                runCatching { client.askForAcronymAdditions(book.title, book.existingAcronyms) }
                    .getOrElse { second ->
                        log("[$globalIndex/$total] gave up after second failure: ${second.message}")
                        null
                    }
            }

    if (additions == null) {
        // Persistent API / network failure — leave the book unmarked so a subsequent
        // run can retry it once the provider is reachable again. Do NOT pollute the
        // checkpoint with false "enriched" entries.
        return
    }

    val kept = sanitizeAdditions(additions, book.existingAcronyms)
    val elapsed = System.currentTimeMillis() - started
    if (kept.isNotEmpty()) {
        withContext(Dispatchers.IO) { repo.insertAcronym(book.title, kept) }
    }
    withContext(Dispatchers.IO) { repo.markBookEnriched(book.id, additionsCount = kept.size) }
    log("[$globalIndex/$total] '${book.title}' +${kept.size} (${elapsed}ms)")
}

/**
 * Conservative filter: keep additions that contain at least one Hebrew letter,
 * are at least 2 characters long, and are not already present in [existing].
 * Deduplication and persistence-level uniqueness are already enforced by SQLite,
 * but we dedupe here too to reduce redundant inserts.
 */
internal fun sanitizeAdditions(additions: List<String>, existing: List<String>): List<String> {
    val existingKeys = existing.map { normalizeKey(it) }.toHashSet()
    val seen = HashSet<String>()
    val out = ArrayList<String>(additions.size)
    for (raw in additions) {
        val t = raw.trim()
        if (t.length < 2) continue
        if (!t.any { it in '\u05D0'..'\u05EA' }) continue
        val key = normalizeKey(t)
        if (key in existingKeys) continue
        if (!seen.add(key)) continue
        out += t
    }
    return out
}

private fun normalizeKey(s: String): String =
    s
        .lowercase()
        .replace("\u05F4", "") // gershayim
        .replace("\u05F3", "") // geresh
        .replace("\"", "")
        .replace("'", "")
        .replace("-", "")
        .replace("\u05BE", "") // maqaf
        .replace("\\s+".toRegex(), "")
