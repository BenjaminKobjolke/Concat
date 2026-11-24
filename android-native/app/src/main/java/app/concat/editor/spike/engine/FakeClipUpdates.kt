// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** Applies the fake engine's supported clip patch fields to one clip. */
internal fun updateClipPatch(clip: JSONObject, patch: JSONObject) {
    if (patch.has("volume")) clip.put("volume", max(0.0, patch.getDouble("volume")))
    if (patch.has("name")) clip.put("name", patch.getString("name"))
    if (patch.has("opacity")) clip.put("opacity", patch.getDouble("opacity").coerceIn(0.0, 1.0))
    updateClipFlags(clip, patch)
    updateClipEffects(clip, patch)
    updateClipTransition(clip, patch)
    updateClipText(clip, patch)
}

private fun updateClipFlags(clip: JSONObject, patch: JSONObject) {
    for (flag in listOf("muted", "flipH", "flipV")) if (patch.has(flag)) clip.put(flag, patch.getBoolean(flag))
    for (fade in listOf("fadeIn", "fadeOut")) if (patch.has(fade)) clip.put(fade, max(0.0, patch.getDouble(fade)))
}

private fun updateClipEffects(clip: JSONObject, patch: JSONObject) {
    for (chain in listOf("videoEffects", "filters")) {
        if (patch.has(chain)) clip.put(chain, patch.optJSONArray(chain) ?: JSONArray())
    }
}

private fun updateClipTransition(clip: JSONObject, patch: JSONObject) {
    if (!patch.has("transitionIn")) return
    val transition = patch.optJSONObject("transitionIn")
    if (transition == null) clip.remove("transitionIn") else clip.put("transitionIn", transition)
}

private fun updateClipText(clip: JSONObject, patch: JSONObject) {
    if (!patch.has("text")) return
    val text = patch.optJSONObject("text")
    if (text == null) clip.remove("text") else {
        clip.put("text", text).put("name", text.optString("content").lineSequence().firstOrNull().orEmpty())
    }
}
