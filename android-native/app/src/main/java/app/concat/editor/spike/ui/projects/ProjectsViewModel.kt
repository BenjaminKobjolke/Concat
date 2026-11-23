// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

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

private const val THUMBNAIL = ".concat-thumb.png"
private const val CARD = ".concat-card.json"

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.concat
    private val _ui = MutableStateFlow(ProjectsUi())
    val ui: StateFlow<ProjectsUi> = _ui

    fun reload() {
        viewModelScope.launch {
            try {
                val list = app.api.listProjects()
                val cards = withContext(Dispatchers.IO) {
                    list.filter { File(it.path).isDirectory }.map { info -> card(info) }
                }
                _ui.update { it.copy(projects = cards, loading = false, fake = app.api.engine.isFake) }
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, message = error.message) }
            }
        }
    }

    /** The frame the editor left in the folder; the in-memory engine paints one on asking. */
    private suspend fun card(info: ProjectInfo): ProjectCard {
        val folder = File(info.path)
        val thumb = File(folder, THUMBNAIL)
        if (!thumb.exists() && app.api.engine.isFake) {
            try {
                app.api.previewFrameTo(info.path, 0.5, thumb.absolutePath, 270, (270.0 * info.height / info.width.coerceAtLeast(1)).toInt())
            } catch (_: Exception) {
            }
        }
        val image = if (thumb.exists()) {
            try {
                BitmapFactory.decodeFile(thumb.absolutePath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val duration = try {
            File(folder, CARD).takeIf { it.exists() }?.let { JSONObject(it.readText()).optDouble("duration").takeIf { d -> !d.isNaN() && d > 0 } }
        } catch (_: Exception) {
            null
        }
        return ProjectCard(info, image, duration)
    }

    fun create(name: String, aspect: Aspect, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val path = app.api.createProject(app.projectsDir.absolutePath, name.trim(), aspect.width, aspect.height, 30)
                onOpen(path)
            } catch (error: Exception) {
                _ui.update { it.copy(message = error.message) }
            }
        }
    }

    /** A new name: open, `project.save` with the name, close. */
    fun rename(project: ProjectInfo, name: String) {
        viewModelScope.launch {
            try {
                app.api.open(project.path)
                app.api.rename(project.path, name.trim())
                app.api.close(project.path, save = false)
            } catch (error: Exception) {
                _ui.update { it.copy(message = error.message) }
            }
            reload()
        }
    }

    /** The folder copied beside itself, then renamed as a project of its own. */
    fun duplicate(project: ProjectInfo) {
        viewModelScope.launch {
            try {
                val source = File(project.path)
                val name = uniqueName("${project.name} copy")
                val target = File(source.parentFile, name.replace(Regex("[^A-Za-z0-9 _.-]"), "_"))
                withContext(Dispatchers.IO) { source.copyRecursively(target, overwrite = false) }
                app.api.open(target.absolutePath)
                app.api.rename(target.absolutePath, name)
                app.api.close(target.absolutePath, save = false)
            } catch (error: Exception) {
                _ui.update { it.copy(message = error.message ?: "The copy did not work.") }
            }
            reload()
        }
    }

    private fun uniqueName(base: String): String {
        val taken = _ui.value.projects.map { it.info.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    fun delete(project: ProjectInfo) {
        viewModelScope.launch {
            try {
                try {
                    app.api.close(project.path, save = false)
                } catch (_: Exception) {
                }
                withContext(Dispatchers.IO) { File(project.path).deleteRecursively() }
            } catch (error: Exception) {
                _ui.update { it.copy(message = error.message) }
            }
            reload()
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}

/**
 * Home: a big New project card, quick actions, and the projects as a grid
 * of cards with their frames, lengths and dates. A long press on a card
 * renames, duplicates or deletes it.
 */
