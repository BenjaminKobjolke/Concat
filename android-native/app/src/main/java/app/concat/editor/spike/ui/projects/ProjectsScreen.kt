// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.projects

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
import app.concat.editor.spike.api.ProjectInfo
import app.concat.editor.spike.concat
import app.concat.editor.spike.ui.components.ChoiceChip
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.IconAction
import app.concat.editor.spike.ui.components.IconActionDimensions
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.secondsLabel
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
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

/** One project's card: its recents entry, its frame and its length when the editor left them. */
data class ProjectCard(val info: ProjectInfo, val thumbnail: ImageBitmap?, val duration: Double?)

data class ProjectsUi(
    val projects: List<ProjectCard> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    val fake: Boolean = false,
)

/** The aspect choices for a new project, as frame sizes. */
enum class Aspect(val label: String, val width: Int, val height: Int) {
    Portrait("9:16", 1080, 1920),
    Landscape("16:9", 1920, 1080),
    Square("1:1", 1080, 1080),
}

@Composable
fun ProjectsScreen(
    onOpen: (path: String, tool: String?) -> Unit,
    onSettings: () -> Unit = {},
    model: ProjectsViewModel = viewModel(),
) {
    val ui by model.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    LaunchedEffect(Unit) { model.reload() }
    val shown = ui.projects.filter { query.isBlank() || it.info.name.contains(query.trim(), ignoreCase = true) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(ConcatColors.page).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Concat", style = ConcatType.display, color = ConcatColors.fg, modifier = Modifier.weight(1f))
                    IconAction(ConcatIcons.Settings, "Settings", onSettings)
                }
                if (ui.fake) {
                    Text("In-memory engine: built without the native library", style = ConcatType.caption, color = ConcatColors.fgDim)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { NewProjectCard { creating = "" } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                QuickAction(ConcatIcons.Rows, "Auto captions", Modifier.weight(1f)) { creating = "captions" }
                QuickAction(ConcatIcons.Waveform, "Text to speech", Modifier.weight(1f)) { creating = "speech" }
                QuickAction(ConcatIcons.Slot, "Templates", Modifier.weight(1f), note = "Soon", enabled = false) {}
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text("Projects", style = ConcatType.title, color = ConcatColors.fg)
                if (ui.projects.isNotEmpty()) {
                    Text("  ${ui.projects.size}", style = ConcatType.label, color = ConcatColors.fgDim)
                }
                Spacer(Modifier.weight(1f))
                IconAction(if (searching) ConcatIcons.Close else ConcatIcons.Search, if (searching) "Stop searching" else "Search", {
                    searching = !searching
                    if (!searching) query = ""
                }, dimensions = IconActionDimensions(icon = 20.dp))
            }
        }
        if (searching) {
            item(span = { GridItemSpan(maxLineSpan) }) { SearchField(query) { query = it } }
        }
        if (!ui.loading && shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (ui.projects.isEmpty()) "No projects yet. Start one above." else "Nothing matches \"$query\".",
                    style = ConcatType.body,
                    color = ConcatColors.fgMuted,
                    modifier = Modifier.padding(vertical = Spacing.xl),
                )
            }
        }
        items(shown, key = { it.info.path }) { card ->
            ProjectTile(
                card,
                onOpen = { onOpen(card.info.path, null) },
                onRename = { renaming = card.info },
                onDuplicate = { model.duplicate(card.info) },
                onDelete = { deleting = card.info },
            )
        }
    }

    creating?.let { tool ->
        NewProjectDialog(
            onDismiss = { creating = null },
            onCreate = { name, aspect ->
                creating = null
                model.create(name, aspect) { path -> onOpen(path, tool.ifEmpty { null }) }
            },
        )
    }
    renaming?.let { project ->
        NameDialog("Rename project", project.name, "Rename", onDismiss = { renaming = null }) {
            model.rename(project, it)
            renaming = null
        }
    }
    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = ConcatColors.launchRaised,
            title = { Text("Delete \"${project.name}\"?", style = ConcatType.title) },
            text = { Text("The project and its folder go for good. Media picked from the phone stays on the phone.", style = ConcatType.body, color = ConcatColors.fgMuted) },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(project)
                    deleting = null
                }) { Text("Delete", color = ConcatColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    ui.message?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            containerColor = ConcatColors.launchRaised,
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

