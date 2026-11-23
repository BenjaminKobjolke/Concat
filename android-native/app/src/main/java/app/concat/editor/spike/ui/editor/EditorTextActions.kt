// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import app.concat.editor.spike.api.Commands
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
    val existing = ui.value.view?.clip(clipId)
    change("Text") {
        if (existing != null) {
            apply(Commands.updateClip(existing.id, JSONObject().put("text", Commands.textStyle(content, fontSize, color, existing.text, fontWeight))))
        } else {
            val view = apply(Commands.addTextClip(ui.value.position, Commands.textStyle(content, fontSize, color, fontWeight = fontWeight)))
            view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
            view
        }
    }
}
