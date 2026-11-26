// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.concat.editor.ui.components.IconActionOptions

/** One project's card: its recents entry, its frame and its length when the editor left them. */
/**
 * Home: a big New project card, quick actions, and the projects as a grid
 * of cards with their frames, lengths and dates. A long press on a card
 * renames, duplicates or deletes it.
 */
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
    val pickProjectFolder = rememberProjectFolderPicker(
        onPicked = { folder -> model.openFolder(folder.absolutePath) { onOpen(it, null) } },
        onError = model::showMessage,
    )
    LaunchedEffect(Unit) { model.reload() }
    val tileActions = ProjectTileActions(
        onOpen = { onOpen(it.path, null) },
        onRename = { renaming = it },
        onDuplicate = model::duplicate,
        onDelete = { deleting = it },
    )

    ProjectsGridContent(
        ui = ui,
        query = query,
        searching = searching,
        actions = ProjectGridActions(
            onCreate = { creating = it },
            onOpenFolder = pickProjectFolder,
            onSettings = onSettings,
            onQueryChanged = { query = it },
            onSearchToggle = {
                searching = !searching
                if (!searching) query = ""
            },
            tileActions = tileActions,
        ),
    )

    ProjectDialogHost(
        state = ProjectDialogState(creating, renaming, deleting, ui.message),
        model = model,
        actions = ProjectDialogActions(
            onOpen = onOpen,
            onCreateDismissed = { creating = null },
            onRenameDismissed = { renaming = null },
            onDeleteDismissed = { deleting = null },
        ),
    )
}

private data class ProjectGridActions(
    val onCreate: (String) -> Unit,
    val onOpenFolder: () -> Unit,
    val onSettings: () -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onSearchToggle: () -> Unit,
    val tileActions: ProjectTileActions,
)

@Composable
private fun ProjectsGridContent(
    ui: ProjectsUi,
    query: String,
    searching: Boolean,
    actions: ProjectGridActions,
) {
    val shown = ui.projects.filter { query.isBlank() || it.info.name.contains(query.trim(), ignoreCase = true) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(ConcatColors.page).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { ProjectsBrandHeader(ui.fake, actions.onSettings) }
        item(span = { GridItemSpan(maxLineSpan) }) { NewProjectCard { actions.onCreate("") } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ProjectQuickActions(onCreate = actions.onCreate, onOpenFolder = actions.onOpenFolder)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ProjectsGridHeading(ui.projects.size, searching, actions.onSearchToggle)
        }
        if (searching) item(span = { GridItemSpan(maxLineSpan) }) {
            SearchField(query, actions.onQueryChanged)
        }
        if (!ui.loading && shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { ProjectsEmptyMessage(ui.projects.isEmpty(), query) }
        }
        projectTiles(shown, actions.tileActions)
    }
}

@Composable
private fun ProjectsBrandHeader(fakeEngine: Boolean, onSettings: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Concat", style = ConcatType.display, color = ConcatColors.fg, modifier = Modifier.weight(1f))
            IconAction(ConcatIcons.Settings, "Settings", onSettings)
        }
        if (fakeEngine) {
            Text("In-memory engine: built without the native library", style = ConcatType.caption, color = ConcatColors.fgDim)
        }
    }
}

@Composable
private fun ProjectQuickActions(onCreate: (String) -> Unit, onOpenFolder: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        QuickAction(QuickActionContent(ConcatIcons.Rows, "Auto captions"), Modifier.weight(1f)) { onCreate("captions") }
        QuickAction(QuickActionContent(ConcatIcons.Waveform, "Text to speech"), Modifier.weight(1f)) { onCreate("speech") }
        QuickAction(QuickActionContent(ConcatIcons.Slot, "Templates", note = "Soon", enabled = false), Modifier.weight(1f)) {}
        QuickAction(QuickActionContent(ConcatIcons.Folder, "Open folder"), Modifier.weight(1f), onClick = onOpenFolder)
    }
}

@Composable
private fun ProjectsGridHeading(projectCount: Int, searching: Boolean, onToggleSearch: () -> Unit) {
    Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text("Projects", style = ConcatType.title, color = ConcatColors.fg)
        if (projectCount > 0) {
            Text("  $projectCount", style = ConcatType.label, color = ConcatColors.fgDim)
        }
        Spacer(Modifier.weight(1f))
        IconAction(if (searching) ConcatIcons.Close else ConcatIcons.Search,
            if (searching) "Stop searching" else "Search", onToggleSearch, options = IconActionOptions(size = 20.dp))
    }
}

@Composable
private fun ProjectsEmptyMessage(isFirstRun: Boolean, query: String) {
    Text(
        if (isFirstRun) "No projects yet. Start one above." else "Nothing matches \"$query\".",
        style = ConcatType.body,
        color = ConcatColors.fgMuted,
        modifier = Modifier.padding(vertical = Spacing.xl),
    )
}

private fun LazyGridScope.projectTiles(projects: List<ProjectCard>, actions: ProjectTileActions) {
    items(projects, key = { it.info.path }) { card -> ProjectTile(card, actions) }
}
