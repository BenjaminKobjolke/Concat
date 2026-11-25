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

fun EditorViewModel.setSpeed(speed: Double) {
        val clip = selectedClip() ?: return
        change("Speed") { apply(Commands.setClipSpeed(clip.id, speed)) }
    }

fun EditorViewModel.setVolume(volume: Double) {
        val clip = selectedClip() ?: return
        change("Volume") { apply(Commands.updateClip(clip.id, JSONObject().put("volume", volume))) }
    }

fun EditorViewModel.setOpacity(opacity: Double) {
        val clip = selectedClip() ?: return
        change("Opacity") { apply(Commands.updateClip(clip.id, JSONObject().put("opacity", opacity.coerceIn(0.0, 1.0)))) }
    }

fun EditorViewModel.setFades(fadeIn: Double, fadeOut: Double) {
        val clip = selectedClip() ?: return
        change("Fade") { apply(Commands.updateClip(clip.id, JSONObject().put("fadeIn", fadeIn).put("fadeOut", fadeOut))) }
    }

/** A crop, mask or animation patch ([ClipLook.kt]) onto the selected clip: one undo step. */
fun EditorViewModel.setLook(label: String, patch: JSONObject) {
        val clip = selectedClip() ?: return
        change(label) { apply(Commands.updateClip(clip.id, patch)) }
    }

fun EditorViewModel.toggleMute() {
        val clip = selectedClip() ?: return
        change(if (clip.muted) "Unmute" else "Mute") { apply(Commands.updateClip(clip.id, JSONObject().put("muted", !clip.muted))) }
    }

/** The main track's own sound off or on, every clip on it in one undo step. */
fun EditorViewModel.setTrackSoundMuted(trackId: String, muted: Boolean) {
        val clips = _ui.value.view?.clips?.filter { it.trackId == trackId && it.kind == ClipKind.Video }.orEmpty()
        if (clips.isEmpty()) return
        change(if (muted) "Mute" else "Unmute") {
            apply(Commands.batch(clips.map { Commands.updateClip(it.id, JSONObject().put("muted", muted)) }))
        }
    }

fun EditorViewModel.flip() {
        val clip = selectedClip() ?: return
        change("Flip") { apply(Commands.updateClip(clip.id, JSONObject().put("flipH", !clip.flipH))) }
    }

/**
     * A picture placed on the preview: `setClipTransform`, one undo step
     * at the end of the gesture. Scale over the fitted size, offsets as
     * fractions of the frame, clockwise degrees.
     */
fun EditorViewModel.setTransform(clipId: String, scale: Double, offsetX: Double, offsetY: Double, rotation: Double) {
        change("Move") { apply(Commands.setClipTransform(clipId, scale, offsetX, offsetY, rotation)) }
    }

fun EditorViewModel.resetTransform() {
        val clip = selectedClip() ?: return
        setTransform(clip.id, 1.0, 0.0, 0.0, 0.0)
    }

/**
     * The keyframe diamond: the selected clip's placement and opacity keyed
     * where the playhead is, or those keys taken off when it already sits
     * on one.
     */
fun EditorViewModel.toggleKeyframe() {
        val clip = selectedClip() ?: return
        if (clip.duration <= 0) return
        val at = ((_ui.value.position - clip.start) / clip.duration).coerceIn(0.0, 1.0)
        val tolerance = 0.5 / ((_ui.value.view?.fps ?: 30.0) * clip.duration)
        val properties = listOf(
            "scale" to clip.scale, "offsetX" to clip.offsetX, "offsetY" to clip.offsetY,
            "rotation" to clip.rotation, "opacity" to clip.opacity,
        )
        if (clip.keyedAt(at, tolerance)) {
            val commands = clip.keys.flatMap { (property, ats) ->
                ats.filter { kotlin.math.abs(it - at) <= tolerance }.map { Commands.clearClipKey(clip.id, property, it) }
            }
            change("Keyframe") { apply(Commands.batch(commands)) }
        } else {
            change("Keyframe") { apply(Commands.batch(properties.map { (name, value) -> Commands.setClipKey(clip.id, name, at, value) })) }
        }
    }

/** The frame's shape: `project.setVideo`, the rate kept. */
fun EditorViewModel.setAspect(width: Int, height: Int) {
        val view = _ui.value.view ?: return
        if (view.width == width && view.height == height) return
        change("Aspect") { api.setVideo(path, width, height, view.rateNum, view.rateDen) }
    }

fun EditorViewModel.setBackground(background: JSONObject) {
        val view = _ui.value.view ?: return
        change("Background") { apply(Commands.setTimelineBackground(view.timelineId, background)) }
    }

fun EditorViewModel.rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        change("Rename") {
            api.rename(path, clean)
            api.get(path)
        }
    }

/** The clip under the playhead on [trackId], selected: the Edit tool. */
fun EditorViewModel.selectAtPlayhead(trackId: String?) {
        val view = _ui.value.view ?: return
        val at = _ui.value.position
        val clip = view.clips.firstOrNull { (trackId == null || it.trackId == trackId) && at >= it.start && at < it.end }
            ?: view.clips.filter { trackId == null || it.trackId == trackId }.minByOrNull { kotlin.math.abs(it.start - at) }
        select(clip?.id)
    }

/** A new title at the playhead, or the selected one restyled when [clipId] names it. */
fun EditorViewModel.saveText(clipId: String?, content: String, fontSize: Double, color: String, fontWeight: Double? = null) {
        if (content.isBlank()) return
        val existing = _ui.value.view?.clip(clipId)
        val lane = existing?.let { clip -> _ui.value.view?.tracks?.firstOrNull { it.id == clip.trackId } }
        val captions = lane?.captions
        change("Text") {
            if (existing != null && lane != null && captions != null) {
                // A caption: its words are its own, its look is the lane's,
                // and the lane's look restyles every caption on it - one edit.
                val words = Commands.updateClip(existing.id, JSONObject().put("text", Commands.textStyle(content, fontSize, color, existing.text, fontWeight)))
                val style = Commands.textStyle("", fontSize, color, captions.optJSONObject("style"), fontWeight)
                val look = JSONObject(captions.toString()).put("style", style)
                apply(Commands.batch(listOf(words, Commands.setCaptionLook(lane.id, look))))
            } else if (existing != null) {
                apply(Commands.updateClip(existing.id, JSONObject().put("text", Commands.textStyle(content, fontSize, color, existing.text, fontWeight))))
            } else {
                val view = apply(Commands.addTextClip(_ui.value.position, Commands.textStyle(content, fontSize, color, fontWeight = fontWeight)))
                view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
                view
            }
        }
    }

    // -- cover --
