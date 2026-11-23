// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import org.json.JSONArray
import org.json.JSONObject

/** A handful of packages shaped as `catalogue.list` gives them, for the sheets to show without the engine. */
internal object FakeCatalogue {
    private fun param(key: String, label: String, range: ClosedFloatingPointRange<Double>, default: Double, step: Double = 0.0, unit: String = "", type: String = "float") =
        JSONObject().put("key", key).put("label", label).put("type", type).put("min", range.start).put("max", range.endInclusive).put("default", default)
            .put("step", step).put("unit", unit).put("animate", true).put("values", JSONArray()).put("labels", JSONArray())

    private fun pkg(id: String, name: String, kind: String, category: String, description: String, vararg params: JSONObject) =
        JSONObject().put("id", id).put("name", name).put("kind", kind).put("category", category).put("description", description)
            .put("intensity", if (params.isNotEmpty()) params[0].getString("key") else JSONObject.NULL)
            .put("params", JSONArray().apply { params.forEach { put(it) } })

    private val all: List<JSONObject> by lazy {
        listOf(
            pkg("concat.blur", "Blur", "effect", "Blur", "Softens the picture.", param("radius", "Radius", 0.0..50.0, 8.0, 1.0, "px")),
            pkg("concat.zoom-blur", "Zoom blur", "effect", "Blur", "Streaks out from the centre.", param("amount", "Amount", 0.0..1.0, 0.4)),
            pkg("concat.glow", "Glow", "effect", "Light", "A bloom around bright parts.", param("strength", "Strength", 0.0..2.0, 0.8), param("threshold", "Threshold", 0.0..1.0, 0.6)),
            pkg("concat.vignette", "Vignette", "effect", "Light", "Darkens the corners.", param("amount", "Amount", 0.0..1.0, 0.5), param("softness", "Softness", 0.0..1.0, 0.5)),
            pkg("concat.shake", "Shake", "effect", "Motion", "A hand-held wobble.", param("strength", "Strength", 0.0..1.0, 0.3), param("speed", "Speed", 0.1..10.0, 2.0, 0.1, "Hz")),
            pkg("concat.pixelate", "Pixelate", "effect", "Stylize", "Big square pixels.", param("size", "Size", 1.0..100.0, 12.0, 1.0, "px", "int")),
            pkg("concat.warm", "Warm", "filter", "Colour", "A golden cast.", param("intensity", "Intensity", 0.0..1.0, 0.7)),
            pkg("concat.cool", "Cool", "filter", "Colour", "A blue cast.", param("intensity", "Intensity", 0.0..1.0, 0.7)),
            pkg("concat.mono", "Mono", "filter", "Black and white", "No colour.", param("intensity", "Intensity", 0.0..1.0, 1.0)),
            pkg("concat.noir", "Noir", "filter", "Black and white", "Hard contrast, no colour.", param("intensity", "Intensity", 0.0..1.0, 1.0)),
            pkg("concat.vintage", "Vintage", "filter", "Film", "Faded, warm film.", param("intensity", "Intensity", 0.0..1.0, 0.8)),
            pkg("concat.echo", "Echo", "audio", "Space", "Repeats the sound.", param("delay", "Delay", 0.05..1.0, 0.3, 0.01, "s"), param("feedback", "Feedback", 0.0..0.9, 0.4)),
            pkg("concat.reverb", "Reverb", "audio", "Space", "A room around the sound.", param("mix", "Mix", 0.0..1.0, 0.3)),
            pkg("concat.cross-fade", "Cross fade", "transition", "Basic", "One picture dissolves into the next."),
            pkg("concat.dip-black", "Dip to black", "transition", "Basic", "Down to black and back up."),
            pkg("concat.push", "Push", "transition", "Slide", "The next shot pushes this one out.", param("angle", "Direction", 0.0..360.0, 0.0, 90.0, "°")),
            pkg("concat.wipe", "Wipe", "transition", "Slide", "A line sweeps across.", param("softness", "Softness", 0.0..1.0, 0.1)),
            pkg("concat.zoom", "Zoom", "transition", "Zoom", "Punches into the next shot.", param("amount", "Amount", 0.0..1.0, 0.5)),
        )
    }

    fun list(kind: String?): JSONArray = JSONArray().apply {
        all.filter { kind == null || it.getString("kind") == kind }.sortedBy { it.getString("id") }.forEach { put(JSONObject(it.toString())) }
    }
}

private fun JSONArray.objectsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

