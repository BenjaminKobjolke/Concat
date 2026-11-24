// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import app.concat.editor.spike.api.Commands
import app.concat.editor.spike.api.TextStyleRequest
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal fun EditorViewModel.saveEditorText(
    clipId: String?,
    content: String,
    fontSize: Double,
    color: String,
    fontWeight: Double?,
) {
    if (content.isBlank()) return
    val view = ui.value.view
    val existing = view?.clip(clipId)
    val lane = existing?.let { clip -> view?.tracks?.firstOrNull { it.id == clip.trackId } }
    val sharedLook = lane?.captions
    change("Text") {
        if (existing != null) {
            val words = Commands.updateClip(existing.id, JSONObject().put("text", Commands.textStyle(TextStyleRequest(content, fontSize, color, existing.text, fontWeight))))
            if (lane != null && sharedLook != null) {
                val style = Commands.textStyle(TextStyleRequest("", fontSize, color, sharedLook.optJSONObject("style"), fontWeight))
                val look = JSONObject(sharedLook.toString()).put("style", style)
                apply(Commands.batch(listOf(words, Commands.setCaptionLook(lane.id, look))))
            } else {
                apply(words)
            }
        } else {
            val view = apply(Commands.addTextClip(ui.value.position, Commands.textStyle(TextStyleRequest(content, fontSize, color, fontWeight = fontWeight))))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }
}
