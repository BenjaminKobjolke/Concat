// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import org.json.JSONArray
import org.json.JSONObject

/** A handful of packages shaped as `catalogue.list` gives them, for the sheets to show without the engine. */
internal object FakeCatalogue {
    private data class ParameterLabel(val key: String, val label: String)

    private data class ParameterOptions(val step: Double = 0.0, val unit: String = "", val type: String = "float")

    private data class ParameterSpec(
        val identity: ParameterLabel,
        val range: ClosedFloatingPointRange<Double>,
        val default: Double,
        val options: ParameterOptions = ParameterOptions(),
    )

    private data class PackageIdentity(val id: String, val name: String)

    private data class PackageSpec(val identity: PackageIdentity, val kind: String, val category: String, val description: String)

    private fun param(spec: ParameterSpec) =
        JSONObject().put("key", spec.identity.key).put("label", spec.identity.label).put("type", spec.options.type)
            .put("min", spec.range.start).put("max", spec.range.endInclusive).put("default", spec.default)
            .put("step", spec.options.step).put("unit", spec.options.unit).put("animate", true).put("values", JSONArray()).put("labels", JSONArray())

    private fun pkg(spec: PackageSpec, vararg params: JSONObject) =
        JSONObject().put("id", spec.identity.id).put("name", spec.identity.name).put("kind", spec.kind).put("category", spec.category).put("description", spec.description)
            .put("intensity", if (params.isNotEmpty()) params[0].getString("key") else JSONObject.NULL)
            .put("params", JSONArray().apply { params.forEach { put(it) } })

    private val all: List<JSONObject> by lazy {
        listOf(
            pkg(PackageSpec(PackageIdentity("concat.blur", "Blur"), "effect", "Blur", "Softens the picture."), param(ParameterSpec(ParameterLabel("radius", "Radius"), 0.0..50.0, 8.0, ParameterOptions(1.0, "px")))),
            pkg(PackageSpec(PackageIdentity("concat.zoom-blur", "Zoom blur"), "effect", "Blur", "Streaks out from the centre."), param(ParameterSpec(ParameterLabel("amount", "Amount"), 0.0..1.0, 0.4))),
            pkg(PackageSpec(PackageIdentity("concat.glow", "Glow"), "effect", "Light", "A bloom around bright parts."), param(ParameterSpec(ParameterLabel("strength", "Strength"), 0.0..2.0, 0.8)), param(ParameterSpec(ParameterLabel("threshold", "Threshold"), 0.0..1.0, 0.6))),
            pkg(PackageSpec(PackageIdentity("concat.vignette", "Vignette"), "effect", "Light", "Darkens the corners."), param(ParameterSpec(ParameterLabel("amount", "Amount"), 0.0..1.0, 0.5)), param(ParameterSpec(ParameterLabel("softness", "Softness"), 0.0..1.0, 0.5))),
            pkg(PackageSpec(PackageIdentity("concat.shake", "Shake"), "effect", "Motion", "A hand-held wobble."), param(ParameterSpec(ParameterLabel("strength", "Strength"), 0.0..1.0, 0.3)), param(ParameterSpec(ParameterLabel("speed", "Speed"), 0.1..10.0, 2.0, ParameterOptions(0.1, "Hz")))),
            pkg(PackageSpec(PackageIdentity("concat.pixelate", "Pixelate"), "effect", "Stylize", "Big square pixels."), param(ParameterSpec(ParameterLabel("size", "Size"), 1.0..100.0, 12.0, ParameterOptions(1.0, "px", "int")))),
            pkg(PackageSpec(PackageIdentity("concat.warm", "Warm"), "filter", "Colour", "A golden cast."), param(ParameterSpec(ParameterLabel("intensity", "Intensity"), 0.0..1.0, 0.7))),
            pkg(PackageSpec(PackageIdentity("concat.cool", "Cool"), "filter", "Colour", "A blue cast."), param(ParameterSpec(ParameterLabel("intensity", "Intensity"), 0.0..1.0, 0.7))),
            pkg(PackageSpec(PackageIdentity("concat.mono", "Mono"), "filter", "Black and white", "No colour."), param(ParameterSpec(ParameterLabel("intensity", "Intensity"), 0.0..1.0, 1.0))),
            pkg(PackageSpec(PackageIdentity("concat.noir", "Noir"), "filter", "Black and white", "Hard contrast, no colour."), param(ParameterSpec(ParameterLabel("intensity", "Intensity"), 0.0..1.0, 1.0))),
            pkg(PackageSpec(PackageIdentity("concat.vintage", "Vintage"), "filter", "Film", "Faded, warm film."), param(ParameterSpec(ParameterLabel("intensity", "Intensity"), 0.0..1.0, 0.8))),
            pkg(PackageSpec(PackageIdentity("concat.echo", "Echo"), "audio", "Space", "Repeats the sound."), param(ParameterSpec(ParameterLabel("delay", "Delay"), 0.05..1.0, 0.3, ParameterOptions(0.01, "s"))), param(ParameterSpec(ParameterLabel("feedback", "Feedback"), 0.0..0.9, 0.4))),
            pkg(PackageSpec(PackageIdentity("concat.reverb", "Reverb"), "audio", "Space", "A room around the sound."), param(ParameterSpec(ParameterLabel("mix", "Mix"), 0.0..1.0, 0.3))),
            pkg(PackageSpec(PackageIdentity("concat.cross-fade", "Cross fade"), "transition", "Basic", "One picture dissolves into the next.")),
            pkg(PackageSpec(PackageIdentity("concat.dip-black", "Dip to black"), "transition", "Basic", "Down to black and back up.")),
            pkg(PackageSpec(PackageIdentity("concat.push", "Push"), "transition", "Slide", "The next shot pushes this one out."), param(ParameterSpec(ParameterLabel("angle", "Direction"), 0.0..360.0, 0.0, ParameterOptions(90.0, "°")))),
            pkg(PackageSpec(PackageIdentity("concat.wipe", "Wipe"), "transition", "Slide", "A line sweeps across."), param(ParameterSpec(ParameterLabel("softness", "Softness"), 0.0..1.0, 0.1))),
            pkg(PackageSpec(PackageIdentity("concat.zoom", "Zoom"), "transition", "Zoom", "Punches into the next shot."), param(ParameterSpec(ParameterLabel("amount", "Amount"), 0.0..1.0, 0.5))),
        )
    }

    fun list(kind: String?): JSONArray = JSONArray().apply {
        all.filter { kind == null || it.getString("kind") == kind }.sortedBy { it.getString("id") }.forEach { put(JSONObject(it.toString())) }
    }
}

private fun JSONArray.objectsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

