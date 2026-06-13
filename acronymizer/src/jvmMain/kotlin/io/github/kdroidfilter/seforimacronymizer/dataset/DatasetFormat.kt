package io.github.kdroidfilter.seforimacronymizer.dataset

import java.io.File

/** Versioned text dataset: a single `data/acronyms.txt` file of `# title` blocks. */
object DatasetFormat {
    const val DEFAULT_DATA_DIR = "data"
    const val ACRONYMS_FILE = "acronyms.txt"
    const val ENRICHED_FILE = "enriched_books.txt"
    const val TITLE_PREFIX = "# "

    data class BookEntry(val title: String, val acronyms: List<String>)

    /** Sort key ignoring leading non-Hebrew characters (e.g. RTL marks). */
    fun sortKey(title: String): String = title.dropWhile { it !in 'א'..'ת' }

    val entryComparator: Comparator<BookEntry> =
        compareBy({ sortKey(it.title) }, { it.title })

    fun parseFile(file: File): List<BookEntry> {
        val entries = mutableListOf<BookEntry>()
        var title: String? = null
        var acronyms = mutableListOf<String>()
        fun flush() {
            title?.let { entries.add(BookEntry(it, acronyms)) }
            acronyms = mutableListOf()
        }
        file.readLines().forEach { line ->
            when {
                line.startsWith(TITLE_PREFIX) -> {
                    flush()
                    title = line.removePrefix(TITLE_PREFIX)
                }
                line.isBlank() -> {}
                else -> {
                    checkNotNull(title) { "${file.name}: acronym line before any '$TITLE_PREFIX' header: $line" }
                    acronyms.add(line)
                }
            }
        }
        flush()
        return entries
    }

    fun writeFile(file: File, entries: List<BookEntry>) {
        file.writeText(buildString {
            entries.forEach { entry ->
                append(TITLE_PREFIX).append(entry.title).append('\n')
                entry.acronyms.forEach { append(it).append('\n') }
                append('\n')
            }
        })
    }
}
