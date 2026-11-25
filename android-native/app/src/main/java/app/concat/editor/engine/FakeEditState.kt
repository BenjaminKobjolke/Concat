// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal fun Session.clipList() = clips.objectsList()
internal fun Session.clip(id: String) = clipList().firstOrNull { it.getString("id") == id }
internal fun Session.setClips(list: List<JSONObject>) {
        timeline.put("clips", JSONArray().apply { list.forEach { put(it) } })
    }
internal fun JSONObject.end() = getDouble("start") + getDouble("duration")

    /** Every clip on [track] starting at or after [from] moves by [delta]. */
internal fun Session.shift(track: String, from: Double, delta: Double, except: String? = null) {
        for (clip in clipList()) {
            if (clip.getString("trackId") == track && clip.getString("id") != except && clip.getDouble("start") >= from - 1e-6) {
                clip.put("start", max(0.0, clip.getDouble("start") + delta))
            }
        }
    }

internal fun Session.free(track: String, start: Double, end: Double) =
        clipList().none { it.getString("trackId") == track && it.getDouble("start") < end - 1e-6 && it.end() > start + 1e-6 }

internal fun Session.setCaptionLook(command: JSONObject) {
    val trackId = command.getString("trackId")
    val look = command.optJSONObject("look")
    val lane = tracks.objectsList().firstOrNull { it.optString("id") == trackId } ?: return
    if (look == null) {
        lane.remove("captions")
        return
    }
    lane.put("captions", JSONObject(look.toString()))
    for (clip in clipList().filter { it.optString("trackId") == trackId && it.optString("kind") == "text" }) {
        val old = clip.optJSONObject("text") ?: JSONObject()
        val style = JSONObject(look.optJSONObject("style")?.toString() ?: "{}")
            .put("content", old.optString("content")).apply { old.optJSONArray("runs")?.let { put("runs", it) } }
        clip.put("text", style).put("offsetY", look.optDouble("offsetY", 0.0))
    }
}

internal fun updateClip(clip: JSONObject, patch: JSONObject, mint: (String) -> String) {
    updateClipValues(clip, patch)
    updateClipObjectFields(clip, patch)
    updateClipMasks(clip, patch, mint)
    updateClipText(clip, patch)
}

internal fun updateClipValues(clip: JSONObject, patch: JSONObject) {
    if (patch.has("volume")) clip.put("volume", max(0.0, patch.getDouble("volume")))
    if (patch.has("name")) clip.put("name", patch.getString("name"))
    if (patch.has("opacity")) clip.put("opacity", patch.getDouble("opacity").coerceIn(0.0, 1.0))
    updateClipFlags(clip, patch)
    updateClipFades(clip, patch)
    updateClipEffects(clip, patch)
}

private fun updateClipFlags(clip: JSONObject, patch: JSONObject) {
    for (flag in listOf("muted", "flipH", "flipV")) if (patch.has(flag)) clip.put(flag, patch.getBoolean(flag))
}

private fun updateClipFades(clip: JSONObject, patch: JSONObject) {
    for (fade in listOf("fadeIn", "fadeOut")) if (patch.has(fade)) clip.put(fade, max(0.0, patch.getDouble(fade)))
}

private fun updateClipEffects(clip: JSONObject, patch: JSONObject) {
    for (chain in listOf("videoEffects", "filters")) if (patch.has(chain)) clip.put(chain, patch.optJSONArray(chain) ?: JSONArray())
}

internal fun updateClipObjectFields(clip: JSONObject, patch: JSONObject) {
    for (field in listOf("transitionIn", "crop", "motion")) {
        if (patch.has(field)) {
            val value = patch.optJSONObject(field)
            if (value == null) clip.remove(field) else clip.put(field, value)
        }
    }
}

internal fun updateClipMasks(clip: JSONObject, patch: JSONObject, mint: (String) -> String) {
    if (!patch.has("masks")) return
        val masks = patch.optJSONArray("masks") ?: JSONArray()
        for (i in 0 until masks.length()) {
            val mask = masks.getJSONObject(i)
            if (mask.optString("id").isEmpty()) mask.put("id", mint("mask-"))
        }
        clip.put("masks", masks)
}

internal fun updateClipText(clip: JSONObject, patch: JSONObject) {
    if (!patch.has("text")) return
    val text = patch.optJSONObject("text")
    if (text == null) clip.remove("text") else {
        clip.put("text", text).put("name", text.optString("content").lineSequence().firstOrNull().orEmpty())
    }
}

