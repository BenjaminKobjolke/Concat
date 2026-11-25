// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

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

data class EditorUi(
    val view: ProjectView? = null,
    val selected: String? = null,
    val position: Double = 0.0,
    val playing: Boolean = false,
    /** The preview draws the slower way: the phone's graphics failed under it. */
    val previewSlower: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** The clip a reverse is being written for, and how far it is (0..1). */
    val reversing: Pair<String, Float>? = null,
    /** Each title's and shape's block as the engine painted it, for the transform box. */
    val bounds: Map<String, ClipBounds> = emptyMap(),
    /** The Stickers page's cards, once the engine has said them. */
    val stickers: List<ShapeCard> = emptyList(),
    /** The cover the project names, painted; none while it names none (the timeline shows its first frame). */
    val cover: Bitmap? = null,
)

/**
 * One open project: its state as the API last replied, the playhead, the
 * selection and the preview. Every edit is `edit.apply` (or undo/redo),
 * then `preview.refresh()`, and the reply is the new state, so the screen
 * never guesses. Engine calls run on the IO dispatcher, one edit at a time.
 */
class EditorViewModel(application: Application, val path: String) : AndroidViewModel(application) {
    internal val app = application.concat
    internal val api = app.api
    internal val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui
    internal val actionScope get() = viewModelScope
    val exports get() = app.exports
    val models get() = app.models

    @Volatile internal var preview: PreviewBridge? = null
    /** The surface waiting for the preview, when it came first. */
    private var pending: Triple<Surface, Int, Int>? = null
    internal val edits = Mutex()
    internal val seeks = Channel<Double>(Channel.CONFLATED)
    @Volatile internal var scrubbedAt = 0L
    internal var closed = false

    init {
        viewModelScope.launch {
            try {
                val view = api.open(path)
                _ui.update { it.copy(view = view) }
                val made = withContext(Dispatchers.IO) { api.engine.preview(path).also { it.refresh() } }
                preview = made
                pending?.let { (surface, width, height) -> made.attachSurface(surface, width, height, MODE) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "could not open $path", error)
                say(error.message ?: "The project would not open.")
            }
        }
        // The painted blocks of the titles and shapes, again whenever the
        // project changes, so the transform box sits on what is drawn.
        viewModelScope.launch {
            ui.map { it.view }.distinctUntilChanged().collectLatest { view ->
                if (view == null) return@collectLatest
                try {
                    val bounds = api.bounds(path)
                    _ui.update { it.copy(bounds = bounds) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "clips.bounds failed", error)
                }
            }
        }
        // The cover, painted again when the document names another.
        viewModelScope.launch {
            ui.map { it.view?.cover?.toString() }.distinctUntilChanged().collectLatest { named ->
                if (named == null) {
                    _ui.update { it.copy(cover = null) }
                    return@collectLatest
                }
                try {
                    val file = File(getApplication<Application>().cacheDir, "cover-${path.hashCode()}.png")
                    api.projectCover(path, file.absolutePath, COVER_SIDE, COVER_SIDE)
                    val painted = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                    _ui.update { it.copy(cover = painted) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "project.cover failed", error)
                }
            }
        }
        viewModelScope.launch {
            try {
                val cards = api.shapes()
                _ui.update { it.copy(stickers = cards) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "shapes.list failed", error)
            }
        }
        // Scrubs, the latest one only: a drag sends far more than a frame can show.
        viewModelScope.launch(Dispatchers.IO) {
            for (seconds in seeks) preview?.seek(seconds)
        }
        // The playhead, from the engine's clock while it plays.
        viewModelScope.launch {
            while (true) {
                val shown = preview
                if (shown != null && SystemClock.uptimeMillis() - scrubbedAt > 300) {
                    val (at, playing, slower) = withContext(Dispatchers.IO) {
                        Triple(shown.position(), shown.isPlaying(), shown.slower())
                    }
                    _ui.update { it.copy(position = at, playing = playing, previewSlower = slower) }
                }
                delay(if (_ui.value.playing) 33 else 250)
            }
        }
    }

    // -- the preview's surface, from the SurfaceHolder callbacks (main thread) --

    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        pending = Triple(surface, width, height)
        preview?.attachSurface(surface, width, height, MODE)
    }

    fun surfaceDestroyed() {
        pending = null
        // Must return only once nothing draws on it.
        preview?.detachSurface()
    }

    fun select(clipId: String?) = _ui.update { it.copy(selected = clipId) }

    internal fun change(label: String, body: suspend () -> ProjectView?) {
        viewModelScope.launch {
            edits.withLock {
                _ui.update { it.copy(busy = true) }
                try {
                    val view = body()
                    if (view != null) {
                        withContext(Dispatchers.IO) { preview?.refresh() }
                        _ui.update { ui ->
                            ui.copy(
                                view = view,
                                selected = ui.selected?.takeIf { view.clip(it) != null },
                                position = ui.position.coerceAtMost(view.duration),
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "$label failed", error)
                    say(error.message ?: "$label did not work.")
                } finally {
                    _ui.update { it.copy(busy = false) }
                }
            }
        }
    }

    internal suspend fun apply(command: JSONObject) = api.apply(path, command)

    /**
     * An edit another screen's model makes (captions placed, a file read
     * aloud): run in turn with every other edit, its reply shown and the
     * preview refreshed, as [change] does.
     */
    fun edit(label: String, body: suspend () -> ProjectView?) = change(label, body)

    internal val liveChains = Channel<Triple<String, String, List<JSONObject>>>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for ((clipId, field, chain) in liveChains) {
                edits.withLock {
                    try {
                        val view = apply(Commands.setChain(clipId, field, chain))
                        withContext(Dispatchers.IO) { preview?.refresh() }
                        _ui.update { it.copy(view = view) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "live parameter failed", error)
                    }
                }
            }
        }
    }

    internal val reverses = JobTracker("reverse", ::onReverse)

    init {
        viewModelScope.launch { api.rawEvents.collect { reverses.offer(it) } }
    }

    private fun onReverse(event: JSONObject) {
        when (event.optString("event")) {
            "reverse.progress" -> _ui.update { ui ->
                ui.copy(reversing = ui.reversing?.copy(second = event.optDouble("fraction", 0.0).toFloat()))
            }
            "reverse.done" -> {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                change("Reverse") { api.clipsUseReversed(path, event) }
            }
            "reverse.failed" -> {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                if (!event.wasCancelled()) say(event.errorMessage())
            }
        }
    }

    internal fun copyIn(uri: Uri): File {
        val resolver = getApplication<Application>().contentResolver
        var name = "picked-${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        // Each pick its own file: two picks of one name are two files.
        val target = File(app.mediaDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
        val input = resolver.openInputStream(uri) ?: throw IOException("The provider would not open $uri")
        input.use { stream -> target.outputStream().use { stream.copyTo(it, 1 shl 20) } }
        return target
    }


            override fun onCleared() {
        val shown = preview
        preview = null
        if (shown != null) app.scope.launch(Dispatchers.IO) { shown.release() }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    internal fun say(message: String) = _ui.update { it.copy(message = message) }
    internal fun selectedClip() = _ui.value.view?.clip(_ui.value.selected)

    companion object {
        internal const val TAG = "concat-editor"
        /** Vulkan, falling back to OpenGL ES inside the engine. */
        internal const val MODE = "vulkan"
        /** The projects screen's card for a project: its frame and its facts, in its folder. */
        /** The timeline's cover square, painted at this many pixels a side. */
        internal const val COVER_SIDE = 160
    }
}
