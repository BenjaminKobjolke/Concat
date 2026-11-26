package app.concat.editor.ui.projects

import android.app.Application
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.concat.editor.api.ProjectInfo
import app.concat.editor.concat
import app.concat.editor.ui.components.ChoiceChip
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.IconAction
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.secondsLabel
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import app.concat.editor.ui.components.IconAppearance

internal data class ProjectDialogState(
    val creating: String?,
    val renaming: ProjectInfo?,
    val deleting: ProjectInfo?,
    val message: String?,
)

internal data class ProjectDialogActions(
    val onOpen: (path: String, tool: String?) -> Unit,
    val onCreateDismissed: () -> Unit,
    val onRenameDismissed: () -> Unit,
    val onDeleteDismissed: () -> Unit,
)

@Composable
internal fun ProjectDialogHost(
    state: ProjectDialogState,
    model: ProjectsViewModel,
    actions: ProjectDialogActions,
) {
    val onOpen = actions.onOpen
    val onCreateDismissed = actions.onCreateDismissed
    val onRenameDismissed = actions.onRenameDismissed
    val onDeleteDismissed = actions.onDeleteDismissed
    state.creating?.let { tool ->
        NewProjectDialog(onDismiss = onCreateDismissed) { name, aspect ->
            onCreateDismissed()
            model.create(name, aspect) { path -> onOpen(path, tool.ifEmpty { null }) }
        }
    }
    state.renaming?.let { project ->
        NameDialog(NameDialogLabels("Rename project", "Rename"), project.name, onRenameDismissed) { name ->
            model.rename(project, name)
            onRenameDismissed()
        }
    }
    state.deleting?.let { project ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            containerColor = ConcatColors.launchRaised,
            title = { Text("Delete \"${project.name}\"?", style = ConcatType.title) },
            text = { Text("The project and its folder go for good. Media picked from the phone stays on the phone.", style = ConcatType.body, color = ConcatColors.fgMuted) },
            confirmButton = { TextButton(onClick = { model.delete(project); onDeleteDismissed() }) { Text("Delete", color = ConcatColors.danger) } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text("Cancel") } },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            containerColor = ConcatColors.launchRaised,
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

@Composable
fun NewProjectCard(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(Radii.xl))
            .background(Brush.linearGradient(listOf(Color(0xFF2A3A10), ConcatColors.launchRaised, ConcatColors.launchPanel)))
            .border(1.dp, ConcatColors.line, RoundedCornerShape(Radii.xl))
            .clickable(onClickLabel = "New project", onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(horizontal = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(ConcatColors.accent), contentAlignment = Alignment.Center) {
                ConcatIcon(ConcatIcons.Plus, appearance = IconAppearance(tint = ConcatColors.onAccent, size = 28.dp))
            }
            Spacer(Modifier.width(Spacing.lg))
            Column {
                Text("New project", style = ConcatType.title.copy(fontSize = ConcatType.title.fontSize * 1.15f), color = ConcatColors.fg)
                Spacer(Modifier.height(2.dp))
                Text("Video, photos and sound, cut on the phone", style = ConcatType.label, color = ConcatColors.fgMuted)
            }
        }
    }
}

data class QuickActionContent(val icon: ImageVector, val label: String, val note: String? = null, val enabled: Boolean = true)

@Composable
fun QuickAction(content: QuickActionContent, modifier: Modifier, onClick: () -> Unit) {
    val icon = content.icon
    val label = content.label
    val note = content.note
    val enabled = content.enabled
    Column(
        modifier.height(88.dp).clip(RoundedCornerShape(Radii.lg)).background(ConcatColors.launchPanel)
            .clickable(enabled = enabled, onClick = onClick).padding(Spacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(ConcatColors.raised), contentAlignment = Alignment.Center) {
                ConcatIcon(icon, appearance = IconAppearance(tint = if (enabled) ConcatColors.accent else ConcatColors.fgDim, size = 18.dp))
            }
            Spacer(Modifier.weight(1f))
            if (note != null) {
                Text(note, style = ConcatType.badge, color = ConcatColors.fgDim,
                    modifier = Modifier.clip(RoundedCornerShape(Radii.xs)).background(ConcatColors.raised).padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
        Text(label, style = ConcatType.label, color = if (enabled) ConcatColors.fg else ConcatColors.fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = ConcatType.body.copy(color = ConcatColors.fg),
        cursorBrush = SolidColor(ConcatColors.accent),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(Radii.md)).background(ConcatColors.launchField).padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConcatIcon(ConcatIcons.Search, appearance = IconAppearance(tint = ConcatColors.fgDim, size = 18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search projects", style = ConcatType.body, color = ConcatColors.fgDim)
                    field()
                }
            }
        },
    )
}

@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Aspect) -> Unit) {
    // A name of its own by default: a second "Untitled" would be refused as a folder that exists.
    var name by remember { mutableStateOf("Project " + SimpleDateFormat("MMM d HH.mm", Locale.getDefault()).format(Date())) }
    var aspect by remember { mutableStateOf(Aspect.Portrait) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ConcatColors.launchRaised,
        title = { Text("New project", style = ConcatType.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for (option in Aspect.entries) {
                        ChoiceChip(option.label, selected = aspect == option, onClick = { aspect = option })
                    }
                }
                Text("30 fps", style = ConcatType.caption, color = ConcatColors.fgDim)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.ifBlank { "Untitled" }, aspect) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(labels: NameDialogLabels, current: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ConcatColors.launchRaised,
        title = { Text(labels.title, style = ConcatType.title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(labels.action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class NameDialogLabels(val title: String, val action: String)
