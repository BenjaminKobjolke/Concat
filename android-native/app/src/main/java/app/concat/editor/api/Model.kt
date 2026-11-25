// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.api

import org.json.JSONArray
import org.json.JSONObject

/*
 * What the app reads out of the API's replies (concat-api's message.rs,
 * concat-host's EditorView and ProjectInfo, concat-project's model). Only
 * the fields the phone shows; the rest of each object is ignored, and kept
 * where a command needs it back whole (a title's style).
 */

/** One recents entry: `project.list`. */
data class ProjectInfo(
    val path: String,
    val name: String,
    val width: Int,
    val height: Int,
    val rateNum: Long,
    val rateDen: Long,
    val openedAt: Long,
) {
    companion object {
        fun from(json: JSONObject) = ProjectInfo(
            path = json.optString("path"),
            name = json.optString("name"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            rateNum = json.optLong("rateNum", 30),
            rateDen = json.optLong("rateDen", 1).coerceAtLeast(1),
            openedAt = json.optLong("openedAt"),
        )
    }
}

enum class ClipKind {
    Video, Audio, Image, Text, Shape, Layer, SoundLayer, Transition, Other;

    /** Whether the clip plays sound of its own: what captions can hear. */
    val hasSound get() = this == Video || this == Audio
}

/**
 * One link of a clip's effect chain (`AppliedFilter`): the package, its
 * knobs as set, and the whole object, so a chain sent back keeps what the
 * phone does not edit (keys).
 */
data class AppliedEffect(val id: String, val params: Map<String, Double>, val enabled: Boolean, val raw: JSONObject) {
    /** The same link with one knob set. */
    fun withParam(key: String, value: Double): JSONObject {
        val out = JSONObject(raw.toString())
        val params = out.optJSONObject("params") ?: JSONObject()
        out.put("params", params.put(key, value))
        return out
    }

    companion object {
        fun from(json: JSONObject): AppliedEffect {
            val params = json.optJSONObject("params")
            val map = LinkedHashMap<String, Double>()
            params?.keys()?.forEach { key -> map[key] = params.optDouble(key) }
            return AppliedEffect(json.optString("id"), map, json.optBoolean("enabled", true), json)
        }

        /** A new link at the package's defaults. */
        fun fresh(id: String): JSONObject = JSONObject().put("id", id).put("params", JSONObject()).put("enabled", true)

        fun list(array: JSONArray?): List<AppliedEffect> = array?.objects()?.map(::from) ?: emptyList()
    }
}

data class MediaItem(
    val id: String,
    val path: String,
    val name: String,
    val kind: String,
    val duration: Double?,
    /** The picture's size, when the probe found one. */
    val width: Int = 0,
    val height: Int = 0,
)

/** One lane; [captions] is a caption lane's shared look (`{style, offsetY}`), none for any other. */
data class Track(val id: String, val visible: Boolean, val muted: Boolean, val captions: JSONObject? = null)

data class Clip(
    val id: String,
    val trackId: String,
    val mediaId: String,
    val name: String,
    val kind: ClipKind,
    val start: Double,
    val duration: Double,
    val sourceStart: Double,
    val volume: Double,
    val speed: Double,
    /** A title's whole style, as the document holds it. */
    val text: JSONObject?,
    /** The picture's chain, in order; a transition clip's package is its first link. */
    val videoEffects: List<AppliedEffect> = emptyList(),
    /** The sound's chain, in order. */
    val filters: List<AppliedEffect> = emptyList(),
    /** Placement (concat-project's `Clip`): a multiplier over the fitted size. */
    val scale: Double = 1.0,
    /** Offset from centred, as a fraction of the frame's width; positive is right. */
    val offsetX: Double = 0.0,
    /** The same down the frame's height; positive is down. */
    val offsetY: Double = 0.0,
    /** Clockwise degrees about the picture's centre. */
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val muted: Boolean = false,
    val flipH: Boolean = false,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    /** The instants keyed, `0..=1` into the clip, of each property that has keys. */
    val keys: Map<String, List<Double>> = emptyMap(),
    /** The crop (`left`, `top`, `right`, `bottom`, each `0..1` cut off), or none. */
    val crop: JSONObject? = null,
    /** The masks, top first, as the document holds them. */
    val masks: List<JSONObject> = emptyList(),
    /** The entrance (`in`), exit (`out`) and loop (`loop`), or none. */
    val motion: JSONObject? = null,
) {
    val end get() = start + duration

    /** Whether the preview can move it about: a title, a picture, a layer over the main track. */
    val placeable get() = kind == ClipKind.Text || kind == ClipKind.Shape || kind == ClipKind.Image || kind == ClipKind.Video || kind == ClipKind.Other

    /** Whether any property is keyed within half a frame of [at] (0..1 into the clip). */
    fun keyedAt(at: Double, tolerance: Double): Boolean = keys.values.any { list -> list.any { kotlin.math.abs(it - at) <= tolerance } }
}

/** `EditorView`: what every project and edit method replies with. */
data class ProjectView(
    val name: String,
    val width: Int,
    val height: Int,
    val rateNum: Long,
    val rateDen: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val media: List<MediaItem>,
    /**
     * The active timeline's lanes as the document stores them: bottom
     * first, the last one drawn on top (concat-project's `AddTrack`).
     */
    val tracks: List<Track>,
    val clips: List<Clip>,
    val createdId: String?,
    /** The active timeline's id: what the timeline commands name. */
    val timelineId: String = "",
    /** The cover as the document holds it (`{"kind":"frame","time":s}` or `{"kind":"picture","path":..}`), or none. */
    val cover: JSONObject? = null,
) {
    val fps: Double get() = rateNum.toDouble() / rateDen.coerceAtLeast(1)
    val duration: Double get() = clips.maxOfOrNull { it.end } ?: 0.0

    /** Where the picture lanes end: where an import lands. */
    val pictureEnd: Double
        get() = clips.filter { it.kind == ClipKind.Video || it.kind == ClipKind.Image }.maxOfOrNull { it.end } ?: 0.0

    fun clip(id: String?) = clips.firstOrNull { it.id == id }
    fun mediaOf(clip: Clip) = media.firstOrNull { it.id == clip.mediaId }

    companion object {
        fun from(json: JSONObject): ProjectView = parseProjectView(json)
    }
}
