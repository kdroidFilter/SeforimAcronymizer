package io.github.kdroidfilter.seforimacronymizer.editor.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.createDefaultWebWorkerDriver
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
            val driver = createDefaultWebWorkerDriver()
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

    // ---------------- Reads ----------------

    /** Search both book titles and their acronyms, ranking title-prefix matches first. */
    suspend fun searchBooks(term: String, limit: Long = 200): List<Books> =
        q.searchBooks(term.trim(), limit) { id, title -> Books(id, title) }.awaitAsList()

    suspend fun acronymsForBook(bookId: Long): List<Acronyms> =
        q.getAcronymsByBookId(bookId) { id, acronym -> Acronyms(id, acronym) }.awaitAsList()

    private suspend fun bookIdByTitle(title: String): Long? =
        q.getBookByTitle(title).awaitAsOneOrNull()

    private suspend fun acronymIdByText(text: String): Long? =
        q.getAcronymByText(text).awaitAsOneOrNull()

    // ---------------- Mutations (return value indicates whether anything changed) ----------------

    suspend fun createBook(title: String): Long? {
        q.insertBook(title)
        return bookIdByTitle(title)
    }

    suspend fun deleteBook(bookId: Long) {
        // sql.js has foreign keys OFF by default, so remove links explicitly before the book.
        q.deleteBookAcronymsByBookId(bookId)
        q.deleteBook(bookId)
    }

    suspend fun addLink(bookId: Long, acronym: String): Boolean {
        val text = acronym.trim()
        if (text.isEmpty()) return false
        q.insertAcronym(text)
        val acronymId = acronymIdByText(text) ?: return false
        q.insertBookAcronym(bookId, acronymId)
        return true
    }

    suspend fun removeLink(bookId: Long, acronymId: Long) {
        q.deleteBookAcronymLink(bookId, acronymId)
    }

    suspend fun cleanOrphans() {
        q.deleteOrphanAcronyms()
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
