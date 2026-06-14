package io.github.kdroidfilter.seforimacronymizer.editor.data

import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single, replayable edit. Operations use natural keys (titles / acronym text) rather than ids,
 * so they can be replayed on top of a freshly loaded base whose ids stay stable.
 */
@Serializable
sealed class EditOp {
    @Serializable
    data class AddLink(val bookTitle: String, val acronym: String) : EditOp()

    @Serializable
    data class RemoveLink(val bookTitle: String, val acronym: String) : EditOp()

    @Serializable
    data class CreateBook(val title: String) : EditOp()

    @Serializable
    data class DeleteBook(val title: String) : EditOp()

    @Serializable
    data object CleanOrphans : EditOp()
}

/**
 * Auto-saved edit journal kept in localStorage. Because every mutation is appended immediately,
 * closing the tab never loses work, and "reset" simply clears it.
 */
object EditLog {
    private const val KEY = "acronymizer_edit_log"
    private val json = Json { encodeDefaults = true }

    val ops: MutableList<EditOp> = mutableListOf()

    fun load() {
        ops.clear()
        val raw = localStorage.getItem(KEY) ?: return
        runCatching { ops.addAll(json.decodeFromString<List<EditOp>>(raw)) }
    }

    fun isEmpty(): Boolean = ops.isEmpty()

    fun append(op: EditOp) {
        ops.add(op)
        persist()
    }

    fun clear() {
        ops.clear()
        localStorage.removeItem(KEY)
    }

    private fun persist() {
        localStorage.setItem(KEY, json.encodeToString(ops))
    }
}
