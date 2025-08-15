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
     * Items are stored as a comma-separated list, matching existing schema usage.
     */
    fun insertAcronym(bookTitle: String, items: List<String>, createdAt: Instant = Instant.now()) {
        val termsCsv = items.joinToString(",")
        db.acronymizerDbQueries.insertAcronym(
            book_title = bookTitle,
            terms = termsCsv,
            created_at = createdAt.toString()
        )
        val preview = items.take(3).joinToString(" | ")
        println("[DB][INSERT] title='${bookTitle}' items=${items.size} preview=[${preview}] at ${createdAt}")
    }

    /** Update an existing acronym row by id. */
    fun updateAcronym(rowId: Long, items: List<String>, createdAt: Instant = Instant.now()) {
        val termsCsv = items.joinToString(",")
        db.acronymizerDbQueries.updateTermsById(
            terms = termsCsv,
            created_at = createdAt.toString(),
            id = rowId
        )
        val preview = items.take(3).joinToString(" | ")
        println("[DB][UPDATE] id=${rowId} items=${items.size} preview=[${preview}] at ${createdAt}")
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
