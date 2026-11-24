// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import app.concat.editor.spike.api.AppliedEffect
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.Commands
import app.concat.editor.spike.api.ClipTransform
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.api.ProjectVideo
import app.concat.editor.spike.api.TransitionClipRequest
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

fun EditorViewModel.move(clipId: String, start: Double, target: LaneTarget) {
        change("Move") {
            val trackId = when (target) {
                is LaneTarget.Existing -> target.trackId
                LaneTarget.NewTop, LaneTarget.NewBottom -> {
                    val made = apply(Commands.addTrack(bottom = target == LaneTarget.NewBottom))
                    made.createdId ?: made.tracks.let { if (target == LaneTarget.NewBottom) it.first() else it.last() }.id
                }
            }
            apply(Commands.moveClips(listOf(Triple(clipId, start.coerceAtLeast(0.0), trackId))))
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
                val view = apply(Commands.addTransitionClip(TransitionClipRequest(packageId, name, start, duration = 1.0)))
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

fun EditorViewModel.setLook(label: String, patch: JSONObject) {
    val clip = selectedClip() ?: return
    change(label) { apply(Commands.updateClip(clip.id, patch)) }
}

fun EditorViewModel.setFades(fadeIn: Double, fadeOut: Double) {
        val clip = selectedClip() ?: return
        change("Fade") { apply(Commands.updateClip(clip.id, JSONObject().put("fadeIn", fadeIn).put("fadeOut", fadeOut))) }
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
        change("Move") { apply(Commands.setClipTransform(clipId, ClipTransform(scale, offsetX, offsetY, rotation))) }
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
        change("Aspect") { api.setVideo(path, ProjectVideo(width, height, view.rateNum, view.rateDen)) }
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
