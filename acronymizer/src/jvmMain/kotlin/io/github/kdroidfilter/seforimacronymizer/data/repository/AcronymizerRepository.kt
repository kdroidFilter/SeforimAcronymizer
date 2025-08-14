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
    private val dbFilePath: String
) {
    private val dbFile: File = File(dbFilePath).absoluteFile
    private val driver: JdbcSqliteDriver by lazy {
        JdbcSqliteDriver(url = "jdbc:sqlite:${dbFile.path}")
    }

    val db: SeforimAcronymizerDb by lazy { ensureDb(); SeforimAcronymizerDb(driver) }

    /** Ensure DB file exists and schema is created if needed. */
    private fun ensureDb() {
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            SeforimAcronymizerDb.Schema.create(driver)
        }
    }

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
    }

    /** Update an existing acronym row by id. */
    fun updateAcronym(rowId: Long, items: List<String>, createdAt: Instant = Instant.now()) {
        val termsCsv = items.joinToString(",")
        db.acronymizerDbQueries.updateTermsById(
            terms = termsCsv,
            created_at = createdAt.toString(),
            id = rowId
        )
    }
}
