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

/**
     * A clip dragged to [start] on [target]: `moveClips`, or onto a new lane
     * `moveClipsToNewTrack`, which makes the lane and moves the clip in one
     * undo step.
     */
fun EditorViewModel.move(clipId: String, start: Double, target: LaneTarget) {
        val at = start.coerceAtLeast(0.0)
        change("Move") {
            when (target) {
                is LaneTarget.Existing -> apply(Commands.moveClips(listOf(Triple(clipId, at, target.trackId))))
                LaneTarget.NewTop, LaneTarget.NewBottom ->
                    apply(Commands.moveClipsToNewTrack(listOf(clipId to at), bottom = target == LaneTarget.NewBottom))
            }
        }
    }

/** One end of a clip dragged by [delta] seconds: `trimClip`, the lane left as it is. */
fun EditorViewModel.trim(clipId: String, edge: String, delta: Double) {
        change("Trim") { apply(Commands.trimClip(clipId, edge, delta, ripple = false)) }
    }

/** A sound file (read aloud, recorded) into the bin and onto the timeline at [at]. */
fun EditorViewModel.placeSound(file: String, at: Double, label: String) {
        change(label) {
            val after = api.import(path, file)
            val id = after.createdId ?: after.media.firstOrNull { it.path == file || File(it.path).name == File(file).name }?.id
                ?: return@change after
            val view = apply(Commands.addClipsInSequence(listOf(id), at))
            view.createdId?.let { created -> _ui.update { it.copy(selected = created) } }
            view
        }
    }

    // -- effects, filters, transitions --

/**
     * A package from the catalogue onto the selected clip: an effect or a
     * filter at the end of its picture chain (`updateClip` with the whole
     * `videoEffects`), a sound effect at the end of its sound chain
     * (`filters`). A transition is a clip of its own, centred on the cut
     * into the selected clip, or at the playhead with nothing selected.
     */
fun EditorViewModel.applyPackage(kind: String, packageId: String, name: String) {
        if (kind == "transition") {
            val clip = selectedClip()
            if (clip?.kind == ClipKind.Transition) {
                // A transition clip selected: it becomes this one, at its defaults.
                val patch = JSONObject().put("name", name)
                    .put("videoEffects", org.json.JSONArray().put(AppliedEffect.fresh(packageId)))
                change("Transition") { apply(Commands.updateClip(clip.id, patch)) }
                return
            }
            val at = clip?.start ?: _ui.value.position
            val start = (at - 0.5).coerceAtLeast(0.0)
            change("Transition") {
                val view = apply(Commands.addTransitionClip(packageId, name, start, duration = 1.0))
                view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
                view
            }
            return
        }
        val clip = selectedClip() ?: return say("Select a clip first.")
        val field = if (kind == "audio") "filters" else "videoEffects"
        val chain = (if (kind == "audio") clip.filters else clip.videoEffects).map { it.raw }
        change(name) { apply(Commands.setChain(clip.id, field, chain + AppliedEffect.fresh(packageId))) }
    }

/**
     * One link of a chain changed as a slider moves: the newest whole chain
     * wins, so a drag sends only what the engine can keep up with.
     */
fun EditorViewModel.setLink(clipId: String, field: String, index: Int, link: JSONObject) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }.toMutableList()
        if (index !in chain.indices) return
        chain[index] = link
        liveChains.trySend(Triple(clipId, field, chain))
    }

fun EditorViewModel.removeLink(clipId: String, field: String, index: Int) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }.toMutableList()
        if (index !in chain.indices) return
        chain.removeAt(index)
        change("Remove") { apply(Commands.setChain(clipId, field, chain)) }
    }

/** Several links off one clip at once, (field, index) each: one undo step. */
fun EditorViewModel.removeLinks(clipId: String, links: List<Pair<String, Int>>) {
        val clip = _ui.value.view?.clip(clipId) ?: return
        if (links.isEmpty()) return
        val commands = listOf("videoEffects", "filters").mapNotNull { field ->
            val gone = links.filter { it.first == field }.map { it.second }.toSet()
            if (gone.isEmpty()) return@mapNotNull null
            val chain = (if (field == "filters") clip.filters else clip.videoEffects).map { it.raw }
            Commands.setChain(clipId, field, chain.filterIndexed { i, _ -> i !in gone })
        }
        change("Remove") { apply(if (commands.size == 1) commands[0] else Commands.batch(commands)) }
    }

fun EditorViewModel.undo() = change("Undo") { api.undo(path) }

fun EditorViewModel.redo() = change("Redo") { api.redo(path) }

fun EditorViewModel.split() {
        val clip = selectedClip() ?: return
        change("Split") { apply(Commands.splitClips(listOf(clip.id), _ui.value.position)) }
    }

fun EditorViewModel.delete() {
        val clip = selectedClip() ?: return
        _ui.update { it.copy(selected = null) }
        change("Delete") { apply(Commands.removeClips(listOf(clip.id), ripple = true)) }
    }

/**
     * A copy of the whole clip right after it, on its lane, in one undo
     * step: the edit model's own `duplicateClips`.
     */
fun EditorViewModel.duplicate() {
        val clip = selectedClip() ?: return
        change("Duplicate") {
            val view = apply(Commands.duplicateClips(listOf(clip.id)))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }

/**
     * The picture under the playhead held for a second: `clips.freeze`, the
     * hold left selected. The playhead has to be inside the clip.
     */
fun EditorViewModel.freeze() {
        val clip = selectedClip() ?: return
        val at = _ui.value.position
        val edge = 1.0 / (_ui.value.view?.fps ?: 30.0)
        if (at <= clip.start + edge || at >= clip.end - edge) return say("Move the playhead inside the clip to freeze it.")
        change("Freeze") {
            val view = api.clipsFreeze(path, clip.id, at)
            view.clips.firstOrNull { it.trackId == clip.trackId && kotlin.math.abs(it.start - at) < 1e-6 }
                ?.let { hold -> _ui.update { it.copy(selected = hold.id) } }
            view
        }
    }

/** A picked file in place of the selected clip's, its place, length and look kept. */
fun EditorViewModel.replace(uri: Uri?) {
        val clip = selectedClip() ?: return
        if (uri == null) return
        change("Replace") {
            val file = withContext(Dispatchers.IO) { copyIn(uri) }
            api.clipsReplace(path, clip.id, file.absolutePath)
        }
    }

/**
     * The selected clip's span written backwards (`clips.reverse`, a job),
     * then shown from the copy (`clips.useReversed`) as one undo step.
     */
fun EditorViewModel.reverse() {
        val clip = selectedClip() ?: return
        if (_ui.value.reversing != null) return say("A reverse is already running.")
        _ui.update { it.copy(reversing = clip.id to 0f) }
        reverses.expect()
        actionScope.launch {
            try {
                reverses.start(api.clipsReverse(path, clip.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reverses.stop()
                _ui.update { it.copy(reversing = null) }
                say(error.message ?: "The reverse did not start.")
            }
        }
    }

fun EditorViewModel.cancelReverse() {
        val job = reverses.job ?: return
        actionScope.launch {
            try {
                api.reverseCancel(job)
            } catch (error: Exception) {
                Log.w(EditorViewModel.TAG, "reverse cancel failed", error)
            }
        }
    }
