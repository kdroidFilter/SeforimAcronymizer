package io.github.kdroidfilter.seforimacronymizer.dataset

import io.github.kdroidfilter.seforimacronymizer.data.repository.AcronymizerRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Builds acronymizer.db from the versioned text dataset under `data/`. */
fun main() {
    val dbPath = System.getenv("acronymizer_db") ?: "acronymizer.db"
    val dataDir = File(System.getenv("acronymizer_data") ?: DatasetFormat.DEFAULT_DATA_DIR)
    val acronymsFile = File(dataDir, DatasetFormat.ACRONYMS_FILE)
    require(acronymsFile.isFile) { "Dataset file not found: ${acronymsFile.path}" }

    val entries = DatasetFormat.parseFile(acronymsFile)
        .sortedWith(DatasetFormat.entryComparator)

    val duplicates = entries.groupingBy { it.title }.eachCount().filterValues { it > 1 }
    require(duplicates.isEmpty()) { "Duplicate book titles in dataset: ${duplicates.keys.take(5)}" }

    // Build into a temp file, then atomically replace the target
    val tmpFile = File("$dbPath.build")
    tmpFile.delete()
    val repo = AcronymizerRepository(tmpFile.path)
    val queries = repo.db.acronymizerDbQueries

    queries.transaction {
        entries.forEach { entry ->
            queries.insertBook(entry.title)
            val bookId = queries.getBookByTitle(entry.title).executeAsOne()
            entry.acronyms.forEach { acronym ->
                if (acronym.isBlank()) return@forEach
                queries.insertAcronym(acronym)
                val acronymId = queries.getAcronymByText(acronym).executeAsOne()
                queries.insertBookAcronym(bookId, acronymId)
            }
        }

        val enrichedFile = File(dataDir, DatasetFormat.ENRICHED_FILE)
        if (enrichedFile.exists()) {
            val now = Instant.now().toString()
            enrichedFile.readLines().filter { it.isNotBlank() }.forEach { title ->
                queries.getBookByTitle(title).executeAsOneOrNull()?.let { bookId ->
                    queries.markBookEnriched(bookId, now, 0L)
                }
            }
        }
    }
    repo.close()

    Files.move(tmpFile.toPath(), File(dbPath).toPath(), StandardCopyOption.REPLACE_EXISTING)
    val links = entries.sumOf { it.acronyms.size }
    println("Built '$dbPath' from '${dataDir.path}': ${entries.size} books, $links book-acronym links")
}
