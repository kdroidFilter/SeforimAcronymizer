package io.github.kdroidfilter.seforimacronymizer.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kdroidfilter.seforim.acronymizer.db.Acronyms
import io.github.kdroidfilter.seforim.acronymizer.db.Books
import io.github.kdroidfilter.seforimacronymizer.editor.data.EditLog
import io.github.kdroidfilter.seforimacronymizer.editor.data.EditOp
import io.github.kdroidfilter.seforimacronymizer.editor.data.WebDb
import io.github.kdroidfilter.seforimacronymizer.editor.github.GitHubClient
import io.github.kdroidfilter.seforimacronymizer.editor.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Loading : Phase
    data object Ready : Phase
    data class Error(val message: String) : Phase
}

/** Localizable toast events; the UI maps them to string resources. */
sealed interface Toast {
    data object TokenSaved : Toast
    data object TokenCleared : Toast
    data object Orphans : Toast
    data class DraftRestored(val count: Int) : Toast
    data object Reset : Toast
    data object PrCreated : Toast
    data class PrFailed(val message: String) : Toast
    data object NeedToken : Toast
}

/** Holds all UI state and orchestrates DB loading, editing, persistence and PR creation. */
class EditorModel(private val scope: CoroutineScope) {

    var phase by mutableStateOf<Phase>(Phase.Loading)
        private set
    var baseTag by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set

    var token by mutableStateOf(TokenStore.load().orEmpty())
        private set
    val hasToken: Boolean get() = token.isNotBlank()

    var search by mutableStateOf("")
        private set
    val books = mutableStateListOf<Books>()
    var selected by mutableStateOf<Books?>(null)
        private set
    val acronyms = mutableStateListOf<Acronyms>()

    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<Toast?>(null)
    var prUrl by mutableStateOf<String?>(null)
        private set

    private lateinit var db: WebDb
    private val github = GitHubClient(tokenProvider = { token })
    private var baseDump: String = ""

    fun start() = scope.launch {
        try {
            db = WebDb.open()
            baseTag = github.latestReleaseTag()
            // Base comes from the app's own bundled resource (no network fetch).
            baseDump = Res.readBytes("files/acronymizer.sql").decodeToString()
            db.loadDump(baseDump)
            EditLog.load()
            if (!EditLog.isEmpty()) {
                for (op in EditLog.ops.toList()) db.replay(op)
                dirty = true
                toast = Toast.DraftRestored(EditLog.ops.size)
            }
            refresh()
            phase = Phase.Ready
        } catch (t: Throwable) {
            phase = Phase.Error(t.message ?: "Loading error")
        }
    }

    fun onSearch(value: String) {
        search = value
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        books.clear()
        books.addAll(db.searchBooks(search))
    }

    fun selectBook(book: Books) = scope.launch {
        selected = book
        reloadAcronyms()
    }

    private suspend fun reloadAcronyms() {
        val b = selected ?: return
        acronyms.clear()
        acronyms.addAll(db.acronymsForBook(b.id))
    }

    fun addAcronym(text: String) {
        val b = selected ?: return
        val value = text.trim()
        if (value.isEmpty()) return
        scope.launch {
            if (db.addLink(b.id, value)) {
                EditLog.append(EditOp.AddLink(b.title, value))
                markDirty()
                reloadAcronyms()
            }
        }
    }

    fun removeAcronym(acronym: Acronyms) {
        val b = selected ?: return
        scope.launch {
            db.removeLink(b.id, acronym.id)
            EditLog.append(EditOp.RemoveLink(b.title, acronym.acronym))
            markDirty()
            reloadAcronyms()
        }
    }

    fun editAcronym(old: Acronyms, newText: String) {
        val b = selected ?: return
        val value = newText.trim()
        if (value.isEmpty() || value == old.acronym) return
        scope.launch {
            db.removeLink(b.id, old.id)
            EditLog.append(EditOp.RemoveLink(b.title, old.acronym))
            if (db.addLink(b.id, value)) EditLog.append(EditOp.AddLink(b.title, value))
            markDirty()
            reloadAcronyms()
        }
    }

    fun createBook(title: String) {
        val value = title.trim()
        if (value.isEmpty()) return
        scope.launch {
            val id = db.createBook(value)
            EditLog.append(EditOp.CreateBook(value))
            markDirty()
            refresh()
            if (id != null) {
                selected = Books(id, value)
                reloadAcronyms()
            }
        }
    }

    fun deleteBook(book: Books) {
        scope.launch {
            db.deleteBook(book.id)
            EditLog.append(EditOp.DeleteBook(book.title))
            markDirty()
            if (selected?.id == book.id) {
                selected = null
                acronyms.clear()
            }
            refresh()
        }
    }

    fun cleanOrphans() {
        scope.launch {
            db.cleanOrphans()
            EditLog.append(EditOp.CleanOrphans)
            markDirty()
            toast = Toast.Orphans
        }
    }

    fun saveToken(value: String) {
        val t = value.trim()
        if (t.isEmpty()) return
        TokenStore.save(t)
        token = t
        toast = Toast.TokenSaved
    }

    fun clearToken() {
        TokenStore.clear()
        token = ""
        toast = Toast.TokenCleared
    }

    /** Discard all local edits and reload the clean bundled base. */
    fun resetAll() {
        scope.launch {
            EditLog.clear()
            db = WebDb.open()
            db.loadDump(baseDump)
            dirty = false
            selected = null
            acronyms.clear()
            prUrl = null
            refresh()
            toast = Toast.Reset
        }
    }

    fun proposePr() {
        if (!hasToken) {
            toast = Toast.NeedToken
            return
        }
        scope.launch {
            busy = true
            prUrl = null
            try {
                val dump = db.exportCanonical()
                val suffix = nextBranchSuffix()
                val url = github.proposePr(
                    dump = dump,
                    title = "Edit Acronymizer database (#$suffix)",
                    body = "Changes proposed from the web editor.\n\n${editSummary()}",
                    branchName = "edit/$suffix",
                )
                prUrl = url
                toast = Toast.PrCreated
            } catch (t: Throwable) {
                toast = Toast.PrFailed(t.message ?: "unknown error")
            } finally {
                busy = false
            }
        }
    }

    fun pendingChanges(): Int = EditLog.ops.size

    private fun editSummary(): String {
        val ops = EditLog.ops
        val adds = ops.count { it is EditOp.AddLink }
        val removes = ops.count { it is EditOp.RemoveLink }
        val newBooks = ops.count { it is EditOp.CreateBook }
        val delBooks = ops.count { it is EditOp.DeleteBook }
        return buildString {
            append("- Acronyms added: ").append(adds).append('\n')
            append("- Acronyms removed: ").append(removes).append('\n')
            append("- Books created: ").append(newBooks).append('\n')
            append("- Books deleted: ").append(delBooks)
        }
    }

    private fun markDirty() {
        dirty = true
        prUrl = null
    }
}
