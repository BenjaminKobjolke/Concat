// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipBounds
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView

internal data class ChosenPreviewGestureTargetRequest(
    val context: PreviewGestureContext,
    val chosen: Clip,
    val over: Clip?,
    val point: Offset,
    val frame: Size,
    val handleReach: Float,
)

internal data class PreviewSnapRequest(
    val context: PreviewGestureContext,
    val state: PreviewDragState,
    val centre: Offset,
    val scale: Double,
    val rotation: Double,
    val snapPx: Float,
)

internal data class PreviewGestureFinishRequest(
    val context: PreviewGestureContext,
    val clip: Clip,
    val result: PreviewDragResult,
    val time: Long,
    val touchSlop: Float,
    val doubleTapTimeout: Long,
)

internal data class PreviewHitTestRequest(
    val view: ProjectView,
    val position: Double,
    val mainTrackId: String?,
    val point: Offset,
    val frame: Size,
    val bounds: Map<String, ClipBounds>,
)

internal fun previewHitTest(request: PreviewHitTestRequest): Clip? {
    val order = request.view.tracks.map { it.id }
    return request.view.clips.filter { it.placeable && request.position >= it.start && request.position < it.end }
        .sortedWith(
            compareByDescending<Clip> { it.trackId != request.mainTrackId }
                .thenByDescending { it.kind == ClipKind.Text }
                .thenByDescending { order.indexOf(it.trackId) },
        )
        .firstOrNull {
            boxOf(ClipBoxRequest(request.view, it, Placement.of(it), request.frame, request.bounds))
                .contains(request.point, 8f)
        }
}
