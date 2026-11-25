// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.runtime.Composable
import app.concat.editor.api.Clip
import app.concat.editor.api.ProjectView

internal data class EditorTransportContext(
    val ui: EditorUi,
    val view: ProjectView?,
    val selected: Clip?,
    val fullscreen: Boolean,
    val model: EditorViewModel,
    val onFullscreen: () -> Unit,
)

@Composable
internal fun EditorTransportRow(context: EditorTransportContext) {
    val ui = context.ui
    val clip = context.selected?.takeIf { it.placeable }
    val keyframe = clip?.let {
        val at = ((ui.position - it.start) / it.duration.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        it.keyedAt(at, 0.5 / ((context.view?.fps ?: 30.0) * it.duration.coerceAtLeast(1e-6)))
    }
    Transport(context, keyframe)
}
