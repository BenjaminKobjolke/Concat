package app.concat.editor.ui.projects

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.api.ProjectInfo
import app.concat.editor.api.ProjectCreation
import app.concat.editor.concat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

internal object ProjectArtifacts {
    const val THUMBNAIL = ".concat-thumb.png"
    const val CARD = ".concat-card.json"
}

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

    fun openFolder(path: String, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            try {
                app.api.open(path)
                onOpen(path)
            } catch (error: Exception) {
                _ui.update { it.copy(message = error.message ?: "That folder is not a Concat project.") }
            }
        }
    }

    fun showMessage(message: String) = _ui.update { it.copy(message = message) }

    /** The frame the editor left in the folder; the in-memory engine paints one on asking. */
    private suspend fun card(info: ProjectInfo): ProjectCard {
        val folder = File(info.path)
        val thumb = File(folder, ProjectArtifacts.THUMBNAIL)
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
            File(folder, ProjectArtifacts.CARD).takeIf { it.exists() }?.let { JSONObject(it.readText()).optDouble("duration").takeIf { d -> !d.isNaN() && d > 0 } }
        } catch (_: Exception) {
            null
        }
        return ProjectCard(info, image, duration)
    }

    fun create(name: String, aspect: Aspect, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val path = app.api.createProject(ProjectCreation(app.projectsDir.absolutePath, name.trim(), aspect.width, aspect.height, 30))
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
