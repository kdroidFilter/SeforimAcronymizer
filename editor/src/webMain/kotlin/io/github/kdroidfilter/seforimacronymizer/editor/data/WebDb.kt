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
        suspend fun open(): WebDb {
            val driver = createDefaultWebWorkerDriver()
            AcronymizerWebDb.Schema.create(driver).await()
            return WebDb(driver, AcronymizerWebDb(driver))
        }
    }

    /** Load a full canonical dump into the empty DB in a single worker round-trip (sql.js db.exec). */
    suspend fun loadDump(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    // ---------------- Reads ----------------

    suspend fun searchBooks(term: String, limit: Long = 200): List<Books> =
        q.searchBooks(term, limit) { id, title -> Books(id, title) }.awaitAsList()

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
