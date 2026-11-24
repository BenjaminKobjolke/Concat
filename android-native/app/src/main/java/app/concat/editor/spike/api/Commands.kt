// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.api

import org.json.JSONArray
import org.json.JSONObject

/** concat-project's `Command`s, as `edit.apply` carries them: `{"op": ..., camelCase fields}`. */
object Commands {
    private fun op(name: String) = JSONObject().put("op", name)
    private fun ids(ids: List<String>) = JSONArray().apply { ids.forEach { put(it) } }

    fun addClipsInSequence(mediaIds: List<String>, start: Double, trackId: String? = null) =
        op("addClipsInSequence").put("mediaIds", ids(mediaIds)).put("start", start)
            .put("trackId", trackId ?: JSONObject.NULL).put("ripple", trackId != null)

    fun addClip(mediaId: String, trackId: String, start: Double, ripple: Boolean) =
        op("addClip").put("mediaId", mediaId).put("trackId", trackId).put("start", start).put("ripple", ripple)

    fun addTextClip(start: Double, style: JSONObject, duration: Double? = null, trackId: String? = null) =
        op("addTextClip").put("trackId", trackId ?: JSONObject.NULL).put("above", true).put("start", start)
            .put("style", style).apply { if (duration != null) put("duration", duration) }

    /** A caption lane's look ([look] is `{style, offsetY}`), every caption on it restyled to match, words kept. */
    fun setCaptionLook(trackId: String, look: JSONObject) = op("setCaptionLook").put("trackId", trackId).put("look", look)

    /** The project's cover: a frame at [time], a picture at [picture], or with neither back to a frame early in. */
    fun setCover(time: Double? = null, picture: String? = null) = op("setCover").put(
        "cover",
        when {
            picture != null -> JSONObject().put("kind", "picture").put("path", picture)
            time != null -> JSONObject().put("kind", "frame").put("time", time)
            else -> JSONObject.NULL
        },
    )

    /** A sticker over the picture, on the first free lane above it: [style] is the card's, [name] what the lane calls it. */
    fun addShapeClip(start: Double, style: JSONObject, name: String, duration: Double? = null) =
        op("addShapeClip").put("trackId", JSONObject.NULL).put("above", true).put("start", start)
            .put("style", style).put("name", name).apply { if (duration != null) put("duration", duration) }

    fun splitClips(clipIds: List<String>, time: Double) = op("splitClips").put("clipIds", ids(clipIds)).put("time", time)

    fun duplicateClips(clipIds: List<String>) = op("duplicateClips").put("clipIds", ids(clipIds))

    fun removeClips(clipIds: List<String>, ripple: Boolean = true) =
        op("removeClips").put("clipIds", ids(clipIds)).put("ripple", ripple)

    fun setClipSpeed(clipId: String, speed: Double) =
        op("setClipSpeed").put("clipId", clipId).put("speed", speed).put("ripple", true)

    fun trimClip(clipId: String, edge: String, delta: Double, ripple: Boolean) =
        op("trimClip").put("clipId", clipId).put("edge", edge).put("delta", delta).put("ripple", ripple)

    fun updateClip(clipId: String, patch: JSONObject) = op("updateClip").put("clipId", clipId).put("patch", patch)

    /**
     * The picture's placement: concat-project's `SetClipTransform` (the
     * transform is not in `ClipPatch`). Scale over the fitted size,
     * offsets as fractions of the frame (positive right and down),
     * clockwise degrees.
     */
    fun setClipTransform(clipId: String, transform: ClipTransform) =
        op("setClipTransform").put("clipId", clipId).put("scale", transform.scale).put("offsetX", transform.offsetX)
            .put("offsetY", transform.offsetY).put("rotation", transform.rotation)

    /** One key on one property ("scale", "offsetX", "offsetY", "rotation", "opacity", "volume"), [at] 0..1 into the clip. */
    fun setClipKey(clipId: String, property: String, at: Double, value: Double) =
        op("setClipKey").put("clipId", clipId).put("property", property).put("at", at).put("value", value)

    fun clearClipKey(clipId: String, property: String, at: Double) =
        op("clearClipKey").put("clipId", clipId).put("property", property).put("at", at)

    /** What fills the frame behind the lowest picture: `{"kind":"none"}`, `{"kind":"blur","amount":..}`, `{"kind":"color","color":"#rrggbb"}`. */
    fun setTimelineBackground(timelineId: String, background: JSONObject) =
        op("setTimelineBackground").put("timelineId", timelineId).put("background", background)

    /** Clips to new starts and lanes in one undo step: `{"op":"moveClips","moves":[{clipId,start,trackId}]}`. */
    fun moveClips(moves: List<Triple<String, Double, String>>) = op("moveClips").put(
        "moves",
        JSONArray().apply {
            moves.forEach { (clip, start, track) -> put(JSONObject().put("clipId", clip).put("start", start).put("trackId", track)) }
        },
    )

    /** Clips onto a lane made for them, on top unless [bottom]: the lane and the move are one undo step. */
    fun moveClipsToNewTrack(moves: List<Pair<String, Double>>, bottom: Boolean) = op("moveClipsToNewTrack").put(
        "moves",
        JSONArray().apply { moves.forEach { (clip, start) -> put(JSONObject().put("clipId", clip).put("start", start)) } },
    ).put("bottom", bottom)

    /** A new lane, on top of the others unless [bottom]. */
    fun addTrack(bottom: Boolean) = op("addTrack").put("bottom", bottom)

    /** A transition as a clip of its own over the lanes beneath it. */
    fun addTransitionClip(transition: TransitionClipRequest) =
        op("addTransitionClip").put("transitionId", transition.transitionId).put("name", transition.name).put("start", transition.start)
            .put("trackId", transition.trackId ?: JSONObject.NULL).apply { if (transition.duration != null) put("duration", transition.duration) }

    /** A whole chain as `updateClip` replaces it: [field] is "videoEffects" or "filters". */
    fun setChain(clipId: String, field: String, chain: List<JSONObject>) =
        updateClip(clipId, JSONObject().put(field, JSONArray().apply { chain.forEach { put(it) } }))

    fun batch(commands: List<JSONObject>) = op("batch").put("commands", JSONArray().apply { commands.forEach { put(it) } })

    /** A title's style: the document's `TextStyle`; fields left out take its defaults. */
    fun textStyle(style: TextStyleRequest): JSONObject =
        JSONObject(style.base?.toString() ?: "{}").put("content", style.content).put("fontSize", style.fontSize).put("color", style.color)
            .apply { if (style.fontWeight != null) put("fontWeight", style.fontWeight) }
}
