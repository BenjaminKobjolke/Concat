// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.spike.api.AppliedEffect
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.Commands
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.concat
import app.concat.editor.spike.engine.PreviewBridge
import app.concat.editor.spike.export.ExportPreset
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val busy: Boolean = false,
    val message: String? = null,
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
    private val lifecycleActions by lazy { EditorLifecycleActions(this, app.scope) }
    internal val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui
    val exports get() = app.exports
    val models get() = app.models

    @Volatile internal var preview: PreviewBridge? = null
    /** The surface waiting for the preview, when it came first. */
    private var pending: Triple<Surface, Int, Int>? = null
    private val edits = Mutex()
    private val seeks = Channel<Double>(Channel.CONFLATED)
    @Volatile private var scrubbedAt = 0L

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
        // Scrubs, the latest one only: a drag sends far more than a frame can show.
        viewModelScope.launch(Dispatchers.IO) {
            for (seconds in seeks) preview?.seek(seconds)
        }
        // The playhead, from the engine's clock while it plays.
        viewModelScope.launch {
            while (true) {
                val shown = preview
                if (shown != null && SystemClock.uptimeMillis() - scrubbedAt > 300) {
                    val (at, playing) = withContext(Dispatchers.IO) { shown.position() to shown.isPlaying() }
                    _ui.update { it.copy(position = at, playing = playing) }
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

    // -- transport --

    fun togglePlay() {
        val shown = preview ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (shown.isPlaying()) shown.pause() else shown.play()
            _ui.update { it.copy(playing = shown.isPlaying()) }
        }
    }

    fun pause() {
        val shown = preview ?: return
        viewModelScope.launch(Dispatchers.IO) {
            shown.pause()
            _ui.update { it.copy(playing = false) }
        }
    }

    /** Moves the playhead to [seconds] at once, the frame as soon as the engine can. */
    fun scrubTo(seconds: Double) {
        val duration = _ui.value.view?.duration ?: 0.0
        val at = seconds.coerceIn(0.0, duration)
        scrubbedAt = SystemClock.uptimeMillis()
        if (_ui.value.playing) pause()
        _ui.update { it.copy(position = at) }
        seeks.trySend(at)
    }

    fun select(clipId: String?) = _ui.update { it.copy(selected = clipId) }

    // -- edits --

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

    /**
     * A clip dragged to [start] on [target]: `moveClips`, one undo step.
     * A new lane is `addTrack` first, the move then naming the lane it
     * minted.
     */
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

    /**
     * One link of a chain changed as a slider moves: the newest whole chain
     * wins, so a drag sends only what the engine can keep up with.
     */
    fun saveText(clipId: String?, content: String, fontSize: Double, color: String, fontWeight: Double? = null) =
        saveEditorText(clipId, content, fontSize, color, fontWeight)

    fun import(uris: List<Uri>, audio: Boolean) = importEditorMedia(uris, audio)

    fun importOverlay(uris: List<Uri>) = importEditorOverlays(uris)

    // -- export --

    fun export(preset: ExportPreset) {
        val view = _ui.value.view ?: return
        pause()
        viewModelScope.launch {
            // The document on disk first: what renders is what was saved.
            try {
                api.save(path)
            } catch (error: Exception) {
                Log.w(TAG, "save before export failed", error)
            }
            exports.start(path, view.name.ifBlank { "Concat" }, view.width, view.height, preset)
                .onFailure { say(it.message ?: "The export did not start.") }
        }
    }

    // -- lifecycle --

    fun save() = lifecycleActions.save()

    /** Back: save, close the project, let the preview go, then [done]. */
    fun close(done: () -> Unit) = lifecycleActions.close(done)

    override fun onCleared() {
        lifecycleActions.onCleared()
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    internal fun say(message: String) = _ui.update { it.copy(message = message) }
    internal fun selectedClip() = _ui.value.view?.clip(_ui.value.selected)

    companion object {
        internal const val TAG = "concat-editor"
        /** Vulkan, falling back to OpenGL ES inside the engine. */
        private const val MODE = "vulkan"
        /** The projects screen's card for a project: its frame and its facts, in its folder. */
        const val THUMBNAIL = ".concat-thumb.png"
        const val CARD = ".concat-card.json"
    }
}
