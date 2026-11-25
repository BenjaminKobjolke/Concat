package app.concat.editor.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ClipKind
import app.concat.editor.api.Commands
import app.concat.editor.api.JobTracker
import app.concat.editor.api.ProjectView
import app.concat.editor.api.ShapeCard
import app.concat.editor.api.errorMessage
import app.concat.editor.api.wasCancelled
import app.concat.editor.concat
import app.concat.editor.engine.PreviewBridge
import app.concat.editor.export.ExportPreset
import app.concat.editor.ui.projects.ProjectArtifacts
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun EditorViewModel.export(preset: ExportPreset) {
        val view = _ui.value.view ?: return
        pause()
        actionScope.launch {
            // The document on disk first: what renders is what was saved.
            try {
                api.save(path)
            } catch (error: Exception) {
                Log.w(EditorViewModel.TAG, "save before export failed", error)
            }
            exports.start(path, view.name.ifBlank { "Concat" }, view.width, view.height, preset)
                .onFailure { say(it.message ?: "The export did not start.") }
        }
    }

    // -- lifecycle --

fun EditorViewModel.save() {
        if (closed) return
        app.scope.launch {
            try {
                api.save(path)
            } catch (error: Exception) {
                Log.w(EditorViewModel.TAG, "save failed", error)
            }
        }
    }

/** Back: save, close the project, let the preview go, then [done]. */
fun EditorViewModel.close(done: () -> Unit) {
        if (closed) return done()
        closed = true
        val shown = preview
        preview = null
        actionScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    shown?.pause()
                    shown?.release()
                    writeCard()
                    api.close(path, save = true)
                } catch (error: Exception) {
                    Log.w(EditorViewModel.TAG, "close failed", error)
                }
            }
            done()
        }
    }

/**
     * The projects screen's card for this project: a frame from the edit
     * (`preview.frame` to a PNG in the folder) and its length, written as
     * the editor closes.
     */
internal suspend fun EditorViewModel.writeCard() {
        val view = _ui.value.view ?: return
        val folder = File(path)
        try {
            File(folder, ProjectArtifacts.CARD).writeText(
                JSONObject().put("duration", view.duration).put("clips", view.clips.size)
                    .put("savedAt", System.currentTimeMillis()).toString(),
            )
        } catch (error: Exception) {
            Log.w(EditorViewModel.TAG, "no card for $path", error)
        }
        if (view.clips.isEmpty()) {
            File(folder, ProjectArtifacts.THUMBNAIL).delete()
            return
        }
        val tall = view.height >= view.width
        val width = if (tall) 270 else 480
        val height = (width * view.height.toDouble() / view.width.coerceAtLeast(1)).toInt()
        try {
            api.projectCover(path, File(folder, ProjectArtifacts.THUMBNAIL).absolutePath, width, height)
        } catch (error: Exception) {
            Log.w(EditorViewModel.TAG, "no thumbnail for $path: ${error.message}")
        }
    }
