// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.api

// Converts project replies into the editor's typed project model.

import org.json.JSONArray
import org.json.JSONObject

internal fun parseProjectView(json: JSONObject): ProjectView {
    val project = json.optJSONObject("project") ?: JSONObject()
    val settings = json.optJSONObject("settings") ?: JSONObject()
    val timeline = activeTimeline(project)
    val video = timeline.optJSONObject("video")

    return ProjectView(
        name = settings.optString("name"),
        width = video?.optInt("width") ?: settings.optInt("width", 1080),
        height = video?.optInt("height") ?: settings.optInt("height", 1920),
        rateNum = video?.optLong("rateNum") ?: settings.optLong("rateNum", 30),
        rateDen = (video?.optLong("rateDen") ?: settings.optLong("rateDen", 1)).coerceAtLeast(1),
        cover = project.optJSONObject("cover"),
        canUndo = json.optBoolean("canUndo"),
        canRedo = json.optBoolean("canRedo"),
        media = parseMedia(project.optJSONArray("media")),
        tracks = parseTracks(timeline.optJSONArray("tracks")),
        clips = parseClips(timeline.optJSONArray("clips")),
        createdId = json.optString("createdId").ifEmpty { null },
        timelineId = timeline.optString("id"),
    )
}

private fun activeTimeline(project: JSONObject): JSONObject {
    val timelines = project.optJSONArray("timelines") ?: JSONArray()
    val activeId = project.optString("activeTimelineId")
    var selected: JSONObject? = null
    for (index in 0 until timelines.length()) {
        val candidate = timelines.getJSONObject(index)
        if (selected == null || candidate.optString("id") == activeId) selected = candidate
    }
    return selected ?: JSONObject()
}

private fun parseMedia(array: JSONArray?) = array?.objects()?.map { item ->
    MediaItem(
        id = item.optString("id"),
        path = item.optString("path"),
        name = item.optString("name"),
        kind = item.optString("kind"),
        duration = if (item.isNull("duration")) null else item.optDouble("duration"),
        width = item.optInt("width"),
        height = item.optInt("height"),
    )
}.orEmpty()

private fun parseTracks(array: JSONArray?) = array?.objects()?.map { item ->
    Track(
        id = item.optString("id"),
        visible = item.optBoolean("visible", true),
        muted = item.optBoolean("muted"),
        captions = item.optJSONObject("captions"),
    )
}.orEmpty()

private fun parseClips(array: JSONArray?) = array?.objects()?.map(::parseClip)?.sortedBy { it.start }.orEmpty()

private fun parseClip(item: JSONObject) = Clip(
    id = item.optString("id"),
    trackId = item.optString("trackId"),
    mediaId = item.optString("mediaId"),
    name = item.optString("name"),
    kind = parseClipKind(item.optString("kind")),
    start = item.optDouble("start", 0.0),
    duration = item.optDouble("duration", 0.0),
    sourceStart = item.optDouble("sourceStart", 0.0),
    volume = item.optDouble("volume", 1.0),
    speed = item.optDouble("speed", 1.0),
    text = item.optJSONObject("text"),
    videoEffects = AppliedEffect.list(item.optJSONArray("videoEffects")),
    filters = AppliedEffect.list(item.optJSONArray("filters")),
    scale = item.finiteDouble("scale", 1.0),
    offsetX = item.finiteDouble("offsetX", 0.0),
    offsetY = item.finiteDouble("offsetY", 0.0),
    rotation = item.finiteDouble("rotation", 0.0),
    opacity = item.finiteDouble("opacity", 1.0),
    muted = item.optBoolean("muted"),
    flipH = item.optBoolean("flipH"),
    fadeIn = item.finiteDouble("fadeIn", 0.0),
    fadeOut = item.finiteDouble("fadeOut", 0.0),
    keys = keysOf(item.optJSONArray("keys")),
    crop = item.optJSONObject("crop"),
    masks = item.optJSONArray("masks")?.objects().orEmpty(),
    motion = item.optJSONObject("motion"),
)

private fun parseClipKind(kind: String) = when (kind) {
    "video" -> ClipKind.Video
    "audio" -> ClipKind.Audio
    "image" -> ClipKind.Image
    "text" -> ClipKind.Text
    "shape" -> ClipKind.Shape
    "layer" -> ClipKind.Layer
    "soundLayer" -> ClipKind.SoundLayer
    "transition" -> ClipKind.Transition
    else -> ClipKind.Other
}

private fun JSONObject.finiteDouble(name: String, default: Double): Double =
    optDouble(name, default).takeIf { !it.isNaN() } ?: default

/** `[ {"property": "scale", "at": 0.2, "value": 1.1, "ease": ...} ]`: keyed instants grouped by property. */
private fun keysOf(array: JSONArray?): Map<String, List<Double>> = array?.objects()
    ?.filter { !it.optDouble("at").isNaN() }
    ?.groupBy({ it.optString("property") }, { it.optDouble("at") })
    .orEmpty()
