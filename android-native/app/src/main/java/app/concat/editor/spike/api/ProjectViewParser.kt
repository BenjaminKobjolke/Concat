// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.api

import org.json.JSONArray
import org.json.JSONObject

internal object ProjectViewParser {
    fun from(json: JSONObject): ProjectView {
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
            media = (project.optJSONArray("media") ?: JSONArray()).objects().map(::mediaItem),
            tracks = (timeline.optJSONArray("tracks") ?: JSONArray()).objects().map {
                Track(it.optString("id"), it.optBoolean("visible", true), it.optBoolean("muted"), it.optJSONObject("captions"))
            },
            clips = (timeline.optJSONArray("clips") ?: JSONArray()).objects().map(::clip).sortedBy { it.start },
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

    private fun mediaItem(json: JSONObject) = MediaItem(
        id = json.optString("id"),
        path = json.optString("path"),
        name = json.optString("name"),
        kind = json.optString("kind"),
        duration = if (json.isNull("duration")) null else json.optDouble("duration"),
        width = json.optInt("width"),
        height = json.optInt("height"),
    )

    private fun clip(json: JSONObject) = Clip(
        id = json.optString("id"),
        trackId = json.optString("trackId"),
        mediaId = json.optString("mediaId"),
        name = json.optString("name"),
        kind = clipKind(json.optString("kind")),
        start = json.optDouble("start", 0.0),
        duration = json.optDouble("duration", 0.0),
        sourceStart = json.optDouble("sourceStart", 0.0),
        volume = json.optDouble("volume", 1.0),
        speed = json.optDouble("speed", 1.0),
        text = json.optJSONObject("text"),
        videoEffects = AppliedEffect.list(json.optJSONArray("videoEffects")),
        filters = AppliedEffect.list(json.optJSONArray("filters")),
        scale = json.optDouble("scale", 1.0).finiteOr(1.0),
        offsetX = json.optDouble("offsetX", 0.0).finiteOr(0.0),
        offsetY = json.optDouble("offsetY", 0.0).finiteOr(0.0),
        rotation = json.optDouble("rotation", 0.0).finiteOr(0.0),
        opacity = json.optDouble("opacity", 1.0).finiteOr(1.0),
        muted = json.optBoolean("muted"),
        flipH = json.optBoolean("flipH"),
        fadeIn = json.optDouble("fadeIn", 0.0).finiteOr(0.0),
        fadeOut = json.optDouble("fadeOut", 0.0).finiteOr(0.0),
        keys = keysOf(json.optJSONArray("keys")),
        crop = json.optJSONObject("crop"),
        masks = json.optJSONArray("masks")?.let { list -> (0 until list.length()).mapNotNull { index -> list.optJSONObject(index) } }.orEmpty(),
        motion = json.optJSONObject("motion"),
    )

    private fun clipKind(value: String) = when (value) {
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

    private fun Double.finiteOr(fallback: Double) = if (isNaN()) fallback else this

    private fun keysOf(json: JSONArray?): Map<String, List<Double>> {
        if (json == null) return emptyMap()
        return json.objects().filter { !it.optDouble("at").isNaN() }
            .groupBy({ it.optString("property") }, { it.optDouble("at") })
    }
}
