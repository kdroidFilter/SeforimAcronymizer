package io.github.kdroidfilter.seforimacronymizer.editor.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import io.github.kdroidfilter.seforim.acronymizer.db.Acronyms
import io.github.kdroidfilter.seforim.acronymizer.db.Books
import io.github.kdroidfilter.seforim.acronymizer.webdb.AcronymizerWebDb

/** In-browser SQLite (sql.js via SQLDelight web-worker-driver) holding the editable database. */
class WebDb private constructor(
    private val driver: SqlDriver,
    private val db: AcronymizerWebDb,
) {
    private val q get() = db.acronymizerDbQueries

    companion object {
        // Number of dump lines (one statement per line) executed per worker round-trip.
        // Sending the whole ~9 MB dump in one db.exec overflows the sql.js wasm memory,
        // so we stream it in batches inside a single transaction.
        private const val BATCH_LINES = 1000

        suspend fun open(): WebDb {
            // Our own worker (resolves sql-wasm.* relative to itself) instead of the default one,
            // whose hardcoded '/sql-wasm.wasm' breaks on GitHub Pages project subpaths.
            val driver = createSqlWorkerDriver()
            // No Schema.create: the canonical dump's own DDL (CREATE TABLE IF NOT EXISTS …)
            // is the single source of the schema.
            return WebDb(driver, AcronymizerWebDb(driver))
        }
    }

    /** Load a full canonical dump into the empty DB in batches (avoids sql.js OOM on the 9 MB string). */
    suspend fun loadDump(sql: String) {
        val lines = sql.split('\n')
        val firstInsert = lines.indexOfFirst { it.startsWith("INSERT ") }
        // Header = PRAGMA + BEGIN TRANSACTION + multi-line CREATE statements + view (opens the transaction).
        val header = if (firstInsert >= 0) lines.subList(0, firstInsert) else lines
        driver.execute(null, header.joinToString("\n"), 0).await()
        if (firstInsert < 0) return
        // Remaining one-statement-per-line INSERTs, ending with COMMIT, sent in batches.
        var i = firstInsert
        while (i < lines.size) {
            val end = minOf(i + BATCH_LINES, lines.size)
            val chunk = lines.subList(i, end).joinToString("\n")
            if (chunk.isNotBlank()) driver.execute(null, chunk, 0).await()
            i = end
        }
    }

    // ---------------- Search (in-memory, punctuation-insensitive) ----------------

    private class BookEntry(val book: Books, val titleNorm: String, val acronymsNorm: List<String>)

    private var searchIndex: List<BookEntry>? = null

    private suspend fun ensureIndex(): List<BookEntry> {
        searchIndex?.let { return it }
        val books = q.getAllBooks { id, title -> Books(id, title) }.awaitAsList()
        val acronymText = q.getAllAcronyms { id, acronym -> id to acronym }.awaitAsList().toMap()
        val byBook = HashMap<Long, MutableList<String>>()
        q.selectAllBookAcronyms { _, bookId, acronymId -> bookId to acronymId }.awaitAsList()
            .forEach { (bookId, acronymId) ->
                acronymText[acronymId]?.let { byBook.getOrPut(bookId) { mutableListOf() }.add(it) }
            }
        val built = books
            .map { BookEntry(it, normalizeSearch(it.title), (byBook[it.id] ?: emptyList()).map(::normalizeSearch)) }
            .sortedBy { it.book.title }
        searchIndex = built
        return built
    }

    /** Search titles and acronyms, ignoring punctuation; title-prefix matches rank first. No cap. */
    suspend fun searchBooks(term: String): List<Books> {
        val query = normalizeSearch(term)
        val entries = ensureIndex()
        if (query.isEmpty()) return entries.map { it.book }
        return entries
            .filter { it.titleNorm.contains(query) || it.acronymsNorm.any { a -> a.contains(query) } }
            .sortedWith(compareBy({ if (it.titleNorm.startsWith(query)) 0 else 1 }, { it.book.title }))
            .map { it.book }
    }

    // Drop everything that is punctuation/whitespace so matching is on letters only:
    // space, comma, period, straight quotes, Hebrew gershayim (״) / geresh (׳), maqaf (־), hyphen.
    private fun normalizeSearch(s: String): String = s.filterNot {
        it == ' ' || it == ',' || it == '.' || it == '"' || it == '\'' ||
            it == '״' || it == '׳' || it == '־' || it == '-'
    }

    // ---------------- Reads ----------------

    suspend fun acronymsForBook(bookId: Long): List<Acronyms> =
        q.getAcronymsByBookId(bookId) { id, acronym -> Acronyms(id, acronym) }.awaitAsList()

    private suspend fun bookIdByTitle(title: String): Long? =
        q.getBookByTitle(title).awaitAsOneOrNull()

    private suspend fun acronymIdByText(text: String): Long? =
        q.getAcronymByText(text).awaitAsOneOrNull()

    // ---------------- Mutations (return value indicates whether anything changed) ----------------

    suspend fun createBook(title: String): Long? {
        q.insertBook(title)
        searchIndex = null
        return bookIdByTitle(title)
    }

    suspend fun deleteBook(bookId: Long) {
        // sql.js has foreign keys OFF by default, so remove links explicitly before the book.
        q.deleteBookAcronymsByBookId(bookId)
        q.deleteBook(bookId)
        searchIndex = null
    }

    suspend fun addLink(bookId: Long, acronym: String): Boolean {
        val text = acronym.trim()
        if (text.isEmpty()) return false
        q.insertAcronym(text)
        val acronymId = acronymIdByText(text) ?: return false
        q.insertBookAcronym(bookId, acronymId)
        searchIndex = null
        return true
    }

    suspend fun removeLink(bookId: Long, acronymId: Long) {
        q.deleteBookAcronymLink(bookId, acronymId)
        searchIndex = null
    }

    suspend fun cleanOrphans() {
        q.deleteOrphanAcronyms()
        searchIndex = null
    }

    /** Replay a persisted edit on top of the freshly loaded base. */
    suspend fun replay(op: EditOp) {
        when (op) {
            is EditOp.CreateBook -> createBook(op.title)
            is EditOp.DeleteBook -> bookIdByTitle(op.title)?.let { deleteBook(it) }
            is EditOp.AddLink -> {
                val bookId = bookIdByTitle(op.bookTitle) ?: createBook(op.bookTitle) ?: return
                addLink(bookId, op.acronym)
            }
            is EditOp.RemoveLink -> {
                val bookId = bookIdByTitle(op.bookTitle) ?: return
                val acronymId = acronymIdByText(op.acronym) ?: return
                removeLink(bookId, acronymId)
            }
            EditOp.CleanOrphans -> cleanOrphans()
        }
    }

    // ---------------- Canonical export (must match scripts/export-canonical.sh) ----------------

    suspend fun exportCanonical(): String {
        val books = q.getAllBooks { id, title -> id to title }.awaitAsList()
        val acronyms = q.getAllAcronyms { id, acronym -> id to acronym }.awaitAsList()
        val links = q.selectAllBookAcronyms { id, bookId, acronymId -> Triple(id, bookId, acronymId) }.awaitAsList()

        val sb = StringBuilder(CANONICAL_HEADER)
        for ((id, title) in books) {
            sb.append("INSERT INTO Books(id,title) VALUES(").append(id).append(",'").append(esc(title)).append("');\n")
        }
        for ((id, acronym) in acronyms) {
            sb.append("INSERT INTO Acronyms(id,acronym) VALUES(").append(id).append(",'").append(esc(acronym)).append("');\n")
        }
        for ((id, bookId, acronymId) in links) {
            sb.append("INSERT INTO BookAcronyms(id,book_id,acronym_id) VALUES(")
                .append(id).append(",").append(bookId).append(",").append(acronymId).append(");\n")
        }
        sb.append("COMMIT;\n")
        return sb.toString()
    }

    private fun esc(s: String): String = s.replace("'", "''")
}

/**
 * Builds the SQLDelight web-worker driver backed by our own sqljs.worker.js (resolved same-origin).
 * Platform-specific because the driver's Worker type and the js() interop differ between
 * Kotlin/JS and Kotlin/Wasm.
 */
internal expect fun createSqlWorkerDriver(): SqlDriver
