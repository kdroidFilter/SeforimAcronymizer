package io.github.kdroidfilter.seforimacronymizer.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.kdroidfilter.seforimacronymizer.editor.data.EditLog
import io.github.kdroidfilter.seforimacronymizer.editor.openUrl

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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(model)
            when (val p = model.phase) {
                Phase.Loading -> Centered("Chargement de la base…")
                is Phase.Error -> Centered("Erreur : ${p.message}", Danger)
                Phase.Ready -> Body(model)
            }
        }
        model.toast?.let { ToastBar(it) { model.toast = null } }
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
        Text("Acronymizer", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Éditeur de base", color = TextDim, fontSize = 13.sp)
        model.baseTag?.let { Chip("release $it", SurfaceAlt, TextDim) }
        if (model.dirty) Chip("modifié", Accent, Color.White)

        Spacer(Modifier.weight(1f))

        if (model.hasToken) {
            Chip("token ✓", SurfaceAlt, Good)
            GhostButton("Effacer le token") { model.clearToken() }
        } else {
            Box(Modifier.width(220.dp)) {
                Field(tokenField, { tokenField = it }, "Token GitHub (scope repo)")
            }
            PrimaryButton("Enregistrer") { model.saveToken(tokenField); tokenField = "" }
        }

        GhostButton("Tout réinitialiser") { model.resetAll() }
        PrimaryButton(if (model.busy) "Création…" else "Proposer une PR") {
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
                Text("Proposer une Pull Request", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${EditLog.ops.size} modification(s) locales seront proposées sur une nouvelle branche.",
                    color = TextDim, fontSize = 13.sp,
                )
                val url = model.prUrl
                if (url != null) {
                    Text("Pull request créée ✓", color = Good, fontSize = 14.sp)
                    PrimaryButton("Ouvrir la PR") { openUrl(url) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Fermer") { prDialog.visible = false }
                    if (url == null) {
                        PrimaryButton(if (model.busy) "Envoi…" else "Confirmer") {
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
            Field(model.search, { model.onSearch(it) }, "Rechercher un livre…")
            CreateBookRow(model)
            Box(Modifier.weight(1f)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(model.books, key = { it.id }) { book ->
                        BookRow(book, model.selected?.id == book.id) { model.selectBook(book) }
                    }
                }
            }
            GhostButton("Nettoyer les acronymes orphelins") { model.cleanOrphans() }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(BorderC))

        Box(Modifier.weight(0.62f).fillMaxHeight().background(Bg).padding(16.dp)) {
            val book = model.selected
            if (book == null) {
                Centered("Sélectionnez un livre pour éditer ses acronymes.", TextDim)
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
        Box(Modifier.weight(1f)) { Field(title, { title = it }, "Nouveau livre…") }
        PrimaryButton("Créer") { model.createBook(title); title = "" }
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
            DangerButton("Supprimer le livre") { model.deleteBook(book) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(newAcronym, { newAcronym = it }, "Ajouter un acronyme…") }
            PrimaryButton("Ajouter") { model.addAcronym(newAcronym); newAcronym = "" }
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
            GhostButton("OK") { onSaveEdit() }
            GhostButton("Annuler") { onCancelEdit() }
        } else {
            Text(acronym.acronym, color = TextMain, fontSize = 14.sp, textAlign = TextAlign.Right,
                modifier = Modifier.weight(1f))
            GhostButton("Éditer") { onStartEdit() }
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 9.dp),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = TextMain, fontSize = 13.sp) }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        backgroundColor = Color(0x33F87171),
        contentColor = Danger,
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
private fun ToastBar(message: String, onDismiss: () -> Unit) {
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
