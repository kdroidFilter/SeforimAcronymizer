package io.github.kdroidfilter.seforimacronymizer.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Button
import com.composeunstyled.Text
import com.composeunstyled.TextField
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.UnstyledDialogPanel
import com.composeunstyled.rememberDialogState
import io.github.kdroidfilter.seforim.acronymizer.db.Acronyms
import io.github.kdroidfilter.seforim.acronymizer.db.Books
import io.github.kdroidfilter.seforimacronymizer.editor.EditorModel
import io.github.kdroidfilter.seforimacronymizer.editor.Phase
import io.github.kdroidfilter.seforimacronymizer.editor.Toast
import io.github.kdroidfilter.seforimacronymizer.editor.openUrl
import io.github.kdroidfilter.seforimacronymizer.editor.resources.Res
import io.github.kdroidfilter.seforimacronymizer.editor.resources.app_subtitle
import io.github.kdroidfilter.seforimacronymizer.editor.resources.app_title
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_add
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_cancel
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_clean_orphans
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_clear_token
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_close
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_confirm
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_create
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_creating
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_delete_book
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_edit
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_ok
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_open_pr
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_propose_pr
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_reset_all
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_save
import io.github.kdroidfilter.seforimacronymizer.editor.resources.btn_sending
import io.github.kdroidfilter.seforimacronymizer.editor.resources.chip_modified
import io.github.kdroidfilter.seforimacronymizer.editor.resources.chip_release
import io.github.kdroidfilter.seforimacronymizer.editor.resources.chip_token_ok
import io.github.kdroidfilter.seforimacronymizer.editor.resources.dialog_pr_desc
import io.github.kdroidfilter.seforimacronymizer.editor.resources.dialog_pr_title
import io.github.kdroidfilter.seforimacronymizer.editor.resources.empty_selection
import io.github.kdroidfilter.seforimacronymizer.editor.resources.error_prefix
import io.github.kdroidfilter.seforimacronymizer.editor.resources.field_add_acronym_placeholder
import io.github.kdroidfilter.seforimacronymizer.editor.resources.field_new_book_placeholder
import io.github.kdroidfilter.seforimacronymizer.editor.resources.field_search_placeholder
import io.github.kdroidfilter.seforimacronymizer.editor.resources.field_token_placeholder
import io.github.kdroidfilter.seforimacronymizer.editor.resources.loading
import io.github.kdroidfilter.seforimacronymizer.editor.resources.pr_created_check
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_draft_restored
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_need_token
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_orphans
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_pr_created
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_pr_failed
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_reset
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_token_cleared
import io.github.kdroidfilter.seforimacronymizer.editor.resources.toast_token_saved
import org.jetbrains.compose.resources.stringResource

private val Bg = Color(0xFF0F172A)
private val Surface = Color(0xFF1E293B)
private val SurfaceAlt = Color(0xFF273449)
private val Accent = Color(0xFF6366F1)
private val TextMain = Color(0xFFE2E8F0)
private val TextDim = Color(0xFF94A3B8)
private val BorderC = Color(0xFF334155)
private val Danger = Color(0xFFF87171)
private val Good = Color(0xFF34D399)

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val model = remember { EditorModel(scope) }
    LaunchedEffect(Unit) { model.start() }

    // Auto RTL: follow the active (browser) locale.
    val lang = Locale.current.language.lowercase()
    val direction = if (lang == "he" || lang == "iw") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize()) {
                Header(model)
                when (val p = model.phase) {
                    Phase.Loading -> Centered(stringResource(Res.string.loading))
                    is Phase.Error -> Centered(stringResource(Res.string.error_prefix, p.message), Danger)
                    Phase.Ready -> Body(model)
                }
            }
            model.toast?.let { ToastBar(it) { model.toast = null } }
        }
    }
}

@Composable
private fun Header(model: EditorModel) {
    var tokenField by remember { mutableStateOf("") }
    val prDialog = rememberDialogState()

    Row(
        Modifier.fillMaxWidth().background(Surface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.app_title), color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.app_subtitle), color = TextDim, fontSize = 13.sp)
        model.baseTag?.let { Chip(stringResource(Res.string.chip_release, it), SurfaceAlt, TextDim) }
        if (model.dirty) Chip(stringResource(Res.string.chip_modified), Accent, Color.White)

        Spacer(Modifier.weight(1f))

        if (model.hasToken) {
            Chip(stringResource(Res.string.chip_token_ok), SurfaceAlt, Good)
            GhostButton(stringResource(Res.string.btn_clear_token)) { model.clearToken() }
        } else {
            Box(Modifier.width(220.dp)) {
                Field(tokenField, { tokenField = it }, stringResource(Res.string.field_token_placeholder))
            }
            PrimaryButton(stringResource(Res.string.btn_save)) { model.saveToken(tokenField); tokenField = "" }
        }

        GhostButton(stringResource(Res.string.btn_reset_all)) { model.resetAll() }
        PrimaryButton(if (model.busy) stringResource(Res.string.btn_creating) else stringResource(Res.string.btn_propose_pr)) {
            if (!model.busy) prDialog.visible = true
        }
    }

    UnstyledDialog(state = prDialog, onDismiss = { prDialog.visible = false }) {
        UnstyledDialogPanel(
            modifier = Modifier
                .width(440.dp)
                .background(Surface, RoundedCornerShape(14.dp))
                .border(1.dp, BorderC, RoundedCornerShape(14.dp))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.dialog_pr_title), color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.dialog_pr_desc, model.pendingChanges()), color = TextDim, fontSize = 13.sp)
                val url = model.prUrl
                if (url != null) {
                    Text(stringResource(Res.string.pr_created_check), color = Good, fontSize = 14.sp)
                    PrimaryButton(stringResource(Res.string.btn_open_pr)) { openUrl(url) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(Res.string.btn_close)) { prDialog.visible = false }
                    if (url == null) {
                        PrimaryButton(if (model.busy) stringResource(Res.string.btn_sending) else stringResource(Res.string.btn_confirm)) {
                            if (!model.busy) model.proposePr()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(model: EditorModel) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(0.38f).fillMaxHeight().background(Bg).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(model.search, { model.onSearch(it) }, stringResource(Res.string.field_search_placeholder))
            CreateBookRow(model)
            Box(Modifier.weight(1f)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(model.books, key = { it.id }) { book ->
                        BookRow(book, model.selected?.id == book.id) { model.selectBook(book) }
                    }
                }
            }
            GhostButton(stringResource(Res.string.btn_clean_orphans)) { model.cleanOrphans() }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(BorderC))

        Box(Modifier.weight(0.62f).fillMaxHeight().background(Bg).padding(16.dp)) {
            val book = model.selected
            if (book == null) {
                Centered(stringResource(Res.string.empty_selection), TextDim)
            } else {
                BookEditor(model, book)
            }
        }
    }
}

@Composable
private fun CreateBookRow(model: EditorModel) {
    var title by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Field(title, { title = it }, stringResource(Res.string.field_new_book_placeholder)) }
        PrimaryButton(stringResource(Res.string.btn_create)) { model.createBook(title); title = "" }
    }
}

@Composable
private fun BookRow(book: Books, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            book.title,
            color = if (selected) Color.White else TextMain,
            fontSize = 14.sp,
            textAlign = TextAlign.Right,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BookEditor(model: EditorModel, book: Books) {
    var newAcronym by remember { mutableStateOf("") }
    var editingId by remember(book.id) { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(book.title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Right, modifier = Modifier.weight(1f))
            DangerButton(stringResource(Res.string.btn_delete_book)) { model.deleteBook(book) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(newAcronym, { newAcronym = it }, stringResource(Res.string.field_add_acronym_placeholder)) }
            PrimaryButton(stringResource(Res.string.btn_add)) { model.addAcronym(newAcronym); newAcronym = "" }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(model.acronyms, key = { it.id }) { a ->
                    AcronymRow(
                        acronym = a,
                        editing = editingId == a.id,
                        editText = editText,
                        onEditTextChange = { editText = it },
                        onStartEdit = { editingId = a.id; editText = a.acronym },
                        onSaveEdit = { model.editAcronym(a, editText); editingId = null },
                        onCancelEdit = { editingId = null },
                        onRemove = { model.removeAcronym(a) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AcronymRow(
    acronym: Acronyms,
    editing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editing) {
            Box(Modifier.weight(1f)) { Field(editText, onEditTextChange, "") }
            GhostButton(stringResource(Res.string.btn_ok)) { onSaveEdit() }
            GhostButton(stringResource(Res.string.btn_cancel)) { onCancelEdit() }
        } else {
            Text(acronym.acronym, color = TextMain, fontSize = 14.sp, textAlign = TextAlign.Right,
                modifier = Modifier.weight(1f))
            GhostButton(stringResource(Res.string.btn_edit)) { onStartEdit() }
            DangerButton("✕") { onRemove() }
        }
    }
}

// ---------------- Reusable styled primitives ----------------

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        singleLine = true,
        contentColor = TextMain,
        backgroundColor = SurfaceAlt,
        borderColor = BorderC,
        borderWidth = 1.dp,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        backgroundColor = Accent,
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        backgroundColor = SurfaceAlt,
        contentColor = TextMain,
        shape = RoundedCornerShape(8.dp),
        borderColor = BorderC,
        borderWidth = 1.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = TextMain, fontSize = 13.sp) }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        backgroundColor = Color(0x33F87171),
        contentColor = Danger,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Chip(label: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = fg, fontSize = 12.sp)
    }
}

@Composable
private fun Centered(text: String, color: Color = TextDim) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 15.sp)
    }
}

@Composable
private fun ToastBar(toast: Toast, onDismiss: () -> Unit) {
    val message = when (toast) {
        Toast.TokenSaved -> stringResource(Res.string.toast_token_saved)
        Toast.TokenCleared -> stringResource(Res.string.toast_token_cleared)
        Toast.Orphans -> stringResource(Res.string.toast_orphans)
        is Toast.DraftRestored -> stringResource(Res.string.toast_draft_restored, toast.count)
        Toast.Reset -> stringResource(Res.string.toast_reset)
        Toast.PrCreated -> stringResource(Res.string.toast_pr_created)
        is Toast.PrFailed -> stringResource(Res.string.toast_pr_failed, toast.message)
        Toast.NeedToken -> stringResource(Res.string.toast_need_token)
    }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceAlt)
                .border(1.dp, BorderC, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = TextMain, fontSize = 13.sp)
            Text("✕", color = TextDim, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDismiss))
        }
    }
}
