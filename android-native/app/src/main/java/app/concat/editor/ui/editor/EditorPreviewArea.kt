// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.concat.editor.api.ProjectView

internal data class EditorPreviewContext(
    val view: ProjectView?,
    val height: Dp,
    val model: EditorViewModel,
    val ui: EditorUi,
    val mainTrackId: String?,
    val inlineEdit: String?,
    val onInlineEdit: (String?) -> Unit,
    val onSelect: (String?) -> Unit,
)

@Composable
internal fun EditorPreviewArea(context: EditorPreviewContext) {
    val view = context.view
    val model = context.model
    val ui = context.ui
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(context.height).padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (view != null) {
            val aspect = if (view.height > 0) view.width.toFloat() / view.height else 9f / 16f
            val roomW = maxWidth - 24.dp
            val roomH = maxHeight
            val (w, h) = if (roomW / roomH > aspect) Pair(roomH * aspect, roomH) else Pair(roomW, roomW / aspect)
            Box(Modifier.size(w, h).background(Color.Black)) {
                PreviewSurface(model, Modifier.fillMaxSize())
                if (ui.previewSlower) SlowerPreviewNote(Modifier.align(Alignment.BottomCenter))
                PreviewOverlay(
                    view = view,
                    position = ui.position,
                    selected = if (context.inlineEdit != null) null else ui.selected,
                    mainTrackId = context.mainTrackId,
                    bounds = ui.bounds,
                    onSelect = context.onSelect,
                    onCommit = { id, p -> model.setTransform(id, p.scale, p.offsetX, p.offsetY, p.rotation) },
                    onEditText = {
                        model.pause()
                        context.onInlineEdit(it)
                    },
                )
                context.inlineEdit?.let { id ->
                    view.clip(id)?.let { clip ->
                        InlineTextEditor(clip, w, h, onDone = { content ->
                            val style = clip.text
                            model.saveText(
                                clip.id, content,
                                style?.optDouble("fontSize", 0.07) ?: 0.07,
                                style?.optString("color", "#ffffff") ?: "#ffffff",
                            )
                            context.onInlineEdit(null)
                        })
                    }
                }
            }
        }
    }
}
