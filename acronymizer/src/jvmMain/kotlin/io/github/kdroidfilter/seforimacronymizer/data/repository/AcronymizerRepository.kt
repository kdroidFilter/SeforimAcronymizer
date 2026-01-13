package io.github.kdroidfilter.seforimacronymizer.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforim.acronymizer.db.SeforimAcronymizerDb
import java.io.File
import java.time.Instant

/**
 * Repository responsible for interacting with the Acronymizer SQLDelight database.
 * Encapsulates DB initialization and read/write operations.
 */
class AcronymizerRepository(
    val dbFilePath: String
) {
    private val dbFile: File = File(dbFilePath).absoluteFile
    private val driver: JdbcSqliteDriver by lazy {
        JdbcSqliteDriver(url = "jdbc:sqlite:${dbFile.path}")
    }

    val db: SeforimAcronymizerDb by lazy { ensureDb(); SeforimAcronymizerDb(driver) }

    /** Ensure DB file exists and schema is created/updated if needed. */
    private fun ensureDb() {
        // Always ensure parent directories exist
        dbFile.parentFile?.mkdirs()
        // Always run schema.create: statements use IF NOT EXISTS, so this is idempotent
        val isNew = !dbFile.exists()
        SeforimAcronymizerDb.Schema.create(driver)
        if (isNew) {
            println("[DB][INIT] Created new acronymizer DB at '${dbFile.path}'")
        } else {
            // Ensures newly added tables (e.g., TocAcronymResults) are present in existing DBs
            println("[DB][INIT] Verified/updated acronymizer DB schema at '${dbFile.path}'")
        }
    }

    // ---------------- Book title APIs ----------------
    /** Check if an acronym entry already exists for the given title. */
    fun hasAcronymFor(bookTitle: String): Boolean {
        val existing = db.acronymizerDbQueries.selectByTitle(bookTitle).executeAsList()
        return existing.isNotEmpty()
    }

    /** Returns the latest stored terms for a given title, or null if no row exists. */
    fun getLatestTermsFor(bookTitle: String): String? {
        val rows = db.acronymizerDbQueries.selectByTitle(bookTitle).executeAsList()
        return rows.lastOrNull()?.terms
    }

    /** Returns the latest row id for a given title, or null if none exists. */
    fun getLatestRowIdFor(bookTitle: String): Long? {
        return db.acronymizerDbQueries.selectLatestIdByTitle(bookTitle).executeAsOneOrNull()
    }

    /**
     * Insert acronym items for a given book title.
     * Uses the relational structure: Books, Acronyms, and BookAcronyms junction table.
     */
    fun insertAcronym(bookTitle: String, items: List<String>, @Suppress("UNUSED_PARAMETER") createdAt: Instant = Instant.now()) {
        // 1. Insert book (or ignore if exists)
        db.acronymizerDbQueries.insertBook(title = bookTitle)

        // 2. Get book ID
        val bookId = db.acronymizerDbQueries.getBookByTitle(bookTitle).executeAsOneOrNull()
            ?: error("Book not found after insert: $bookTitle")

        // 3. For each acronym item
        for (item in items) {
            if (item.isBlank()) continue

            // Insert acronym (or ignore if exists)
            db.acronymizerDbQueries.insertAcronym(acronym = item)

            // Get acronym ID
            val acronymId = db.acronymizerDbQueries.getAcronymByText(item).executeAsOneOrNull()
                ?: continue

            // Create link in junction table
            db.acronymizerDbQueries.insertBookAcronym(book_id = bookId, acronym_id = acronymId)
        }

        val preview = items.take(3).joinToString(" | ")
        println("[DB][INSERT] title='${bookTitle}' items=${items.size} preview=[${preview}]")
    }

    /** Update an existing acronym entry by replacing all acronyms for a book. */
    fun updateAcronym(rowId: Long, items: List<String>, @Suppress("UNUSED_PARAMETER") createdAt: Instant = Instant.now()) {
        // Get the book by id
        val book = db.acronymizerDbQueries.getBookById(rowId).executeAsOneOrNull()
            ?: error("Book not found with id: $rowId")

        // Delete existing links
        db.acronymizerDbQueries.deleteBookAcronymsByBookId(rowId)

        // Insert new acronyms and links (same logic as insertAcronym)
        for (item in items) {
            if (item.isBlank()) continue

            db.acronymizerDbQueries.insertAcronym(acronym = item)

            val acronymId = db.acronymizerDbQueries.getAcronymByText(item).executeAsOneOrNull()
                ?: continue

            db.acronymizerDbQueries.insertBookAcronym(book_id = rowId, acronym_id = acronymId)
        }

        val preview = items.take(3).joinToString(" | ")
        println("[DB][UPDATE] id=${rowId} title='${book.title}' items=${items.size} preview=[${preview}]")
    }

    // ---------------- tocText APIs ----------------
    /** Check if an acronym entry already exists for the given toc text. */
    fun hasTocAcronymFor(tocText: String): Boolean {
        val existing = db.acronymizerDbQueries.selectTocByText(tocText).executeAsList()
        return existing.isNotEmpty()
    }

    /** Returns the latest stored terms for a given toc text, or null if no row exists. */
    fun getLatestTocTermsFor(tocText: String): String? {
        val rows = db.acronymizerDbQueries.selectTocByText(tocText).executeAsList()
        return rows.lastOrNull()?.terms
    }

    /** Returns the latest row id for a given toc text, or null if none exists. */
    fun getLatestTocRowIdFor(tocText: String): Long? {
        return db.acronymizerDbQueries.selectLatestIdByTocText(tocText).executeAsOneOrNull()
    }

    /** Insert acronym items for a given toc text. */
    fun insertTocAcronym(tocText: String, items: List<String>, createdAt: Instant = Instant.now()) {
        val termsCsv = items.joinToString(",")
        db.acronymizerDbQueries.insertTocAcronym(
            toc_text = tocText,
            terms = termsCsv,
            created_at = createdAt.toString()
        )
        val preview = items.take(3).joinToString(" | ")
        println("[DB][INSERT][TOC] toc='${tocText}' items=${items.size} preview=[${preview}] at ${createdAt}")
    }

    /** Update an existing toc acronym row by id. */
    fun updateTocAcronym(rowId: Long, items: List<String>, createdAt: Instant = Instant.now()) {
        val termsCsv = items.joinToString(",")
        db.acronymizerDbQueries.updateTocTermsById(
            terms = termsCsv,
            created_at = createdAt.toString(),
            id = rowId
        )
        val preview = items.take(3).joinToString(" | ")
        println("[DB][UPDATE][TOC] id=${rowId} items=${items.size} preview=[${preview}] at ${createdAt}")
    }
}
