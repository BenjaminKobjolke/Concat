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

/** The frame under the playhead as the cover. */
fun EditorViewModel.setCoverFrame() {
        val at = _ui.value.position
        change("Cover") { apply(Commands.setCover(time = at)) }
    }

/** A picture from the phone as the cover, copied in so the project keeps it. */
fun EditorViewModel.setCoverPicture(uri: Uri?) {
        if (uri == null) return
        change("Cover") {
            val file = withContext(Dispatchers.IO) { copyIn(uri) }
            apply(Commands.setCover(picture = file.absolutePath))
        }
    }

/** Back to a frame early in the edit. */
fun EditorViewModel.resetCover() {
        if (_ui.value.view?.cover == null) return
        change("Cover") { apply(Commands.setCover()) }
    }

/** A sticker from the Stickers page at the playhead, over the picture, and selected. */
fun EditorViewModel.addSticker(card: ShapeCard, name: String) {
        change("Sticker") {
            val view = apply(Commands.addShapeClip(_ui.value.position, card.style, name))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }

/**
     * Picked files copied into the app's storage (the engine reads paths),
     * imported one by one in the order picked, then laid down as one run:
     * pictures after the last picture, sound at the playhead.
     */
fun EditorViewModel.import(uris: List<Uri>, audio: Boolean) {
        if (uris.isEmpty()) return
        change("Import") {
            var view: ProjectView? = null
            val ids = mutableListOf<String>()
            for (uri in uris) {
                val file = withContext(Dispatchers.IO) { copyIn(uri) }
                val after = api.import(path, file.absolutePath)
                view = after
                val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
                if (id != null) ids += id
            }
            if (ids.isEmpty()) return@change view
            val start = if (audio) _ui.value.position else view?.pictureEnd ?: 0.0
            apply(Commands.addClipsInSequence(ids, start))
        }
    }

/**
     * Picked pictures over the main track: each onto a new lane on top, at
     * the playhead, as CapCut's Overlay does.
     */
fun EditorViewModel.importOverlay(uris: List<Uri>) {
        if (uris.isEmpty()) return
        change("Overlay") {
            var view: ProjectView? = null
            for (uri in uris) {
                val file = withContext(Dispatchers.IO) { copyIn(uri) }
                val after = api.import(path, file.absolutePath)
                val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
                    ?: continue
                val lane = apply(Commands.addTrack(bottom = false))
                val trackId = lane.createdId ?: lane.tracks.last().id
                view = apply(Commands.addClip(id, trackId, _ui.value.position, ripple = false))
                view.createdId?.let { created -> _ui.update { it.copy(selected = created) } }
            }
            view
        }
    }
