package io.github.kdroidfilter.seforimacronymizer.dataset

import io.github.kdroidfilter.seforimacronymizer.data.repository.AcronymizerRepository
import java.io.File

/** Exports acronymizer.db into the versioned text dataset under `data/`. */
fun main() {
    val dbPath = System.getenv("acronymizer_db") ?: "acronymizer.db"
    val dataDir = File(System.getenv("acronymizer_data") ?: DatasetFormat.DEFAULT_DATA_DIR)
    require(File(dbPath).exists()) { "Database not found: $dbPath" }

    val repo = AcronymizerRepository(dbPath)
    val queries = repo.db.acronymizerDbQueries

    val acronymsByTitle = LinkedHashMap<String, MutableList<String>>()
    queries.getAllBooks().executeAsList().forEach { acronymsByTitle[it.title] = mutableListOf() }
    queries.selectAllBookAcronymPairs().executeAsList().forEach {
        acronymsByTitle.getValue(it.title).add(it.acronym)
    }

    val entries = acronymsByTitle
        .map { (title, acronyms) -> DatasetFormat.BookEntry(title, acronyms) }
        .sortedWith(DatasetFormat.entryComparator)

    dataDir.mkdirs()
    DatasetFormat.writeFile(File(dataDir, DatasetFormat.ACRONYMS_FILE), entries)

    val enrichedTitles = queries.selectEnrichedBookTitles().executeAsList()
    val enrichedFile = File(dataDir, DatasetFormat.ENRICHED_FILE)
    if (enrichedTitles.isEmpty()) enrichedFile.delete()
    else enrichedFile.writeText(enrichedTitles.joinToString("\n", postfix = "\n"))

    repo.close()
    val links = entries.sumOf { it.acronyms.size }
    println("Exported ${entries.size} books, $links book-acronym links, ${enrichedTitles.size} enriched marks to '${dataDir.path}'")
}
