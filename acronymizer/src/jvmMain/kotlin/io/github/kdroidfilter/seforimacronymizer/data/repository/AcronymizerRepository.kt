package io.github.kdroidfilter.seforimacronymizer.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.kdroidfilter.seforim.acronymizer.db.SeforimAcronymizerDb
import java.io.File
import java.time.Instant

/**
 * Repository responsible for interacting with the Acronymizer SQLDelight database.
 * Encapsulates DB initialization and write operations.
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
}
