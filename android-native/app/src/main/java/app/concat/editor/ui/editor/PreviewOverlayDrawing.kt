// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.unit.dp
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.theme.ConcatColors

internal data class OverlayDrawState(
    val view: ProjectView,
    val position: Double,
    val selected: String?,
    val bounds: Map<String, ClipBounds>,
    val live: Pair<String, Placement>?,
    val moving: Boolean,
    val guides: Pair<Boolean, Boolean>,
    val alpha: Float,
    val rotateGlyph: VectorPainter,
)

internal fun DrawScope.drawPreviewOverlay(state: OverlayDrawState) {
    val frame = size
    drawSafeArea(frame, state.moving, state.guides)
    val clip = state.view.clip(state.selected)?.takeIf {
        it.placeable && state.position >= it.start && state.position < it.end
    } ?: return
    val placement = state.live?.takeIf { it.first == clip.id }?.second ?: Placement.of(clip)
    val box = boxOf(OverlayBoxRequest(state.view, clip, placement, frame, state.bounds))
    val alpha = state.alpha
    val outline = Path().apply {
        val corners = box.corners
        moveTo(corners[0].x, corners[0].y)
        for (index in 1..3) lineTo(corners[index].x, corners[index].y)
        close()
    }
    drawPath(outline, Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(1.5.dp.toPx() + 2.dp.toPx()))
    drawPath(outline, Color.White.copy(alpha = alpha), style = Stroke(1.5.dp.toPx()))
    drawCornerHandles(box.corners, alpha)
    drawRotateHandle(box, alpha, state.rotateGlyph)
}

private fun DrawScope.drawSafeArea(frame: Size, moving: Boolean, guides: Pair<Boolean, Boolean>) {
    if (!moving) return
    val insetX = frame.width * 0.06f
    val insetY = frame.height * 0.08f
    drawRect(
        Color.White.copy(alpha = 0.35f),
        Offset(insetX, insetY),
        Size(frame.width - insetX * 2, frame.height - insetY * 2),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
    if (guides.first) drawLine(ConcatColors.accent, Offset(frame.width / 2, 0f), Offset(frame.width / 2, frame.height), 1.5.dp.toPx())
    if (guides.second) drawLine(ConcatColors.accent, Offset(0f, frame.height / 2), Offset(frame.width, frame.height / 2), 1.5.dp.toPx())
}

private fun DrawScope.drawCornerHandles(corners: List<Offset>, alpha: Float) {
    for ((index, corner) in corners.withIndex()) {
        if (index == 2) continue
        drawCircle(Color.Black.copy(alpha = 0.3f * alpha), 7.dp.toPx(), corner)
        drawCircle(Color.White.copy(alpha = alpha), 6.dp.toPx(), corner)
    }
}

private fun DrawScope.drawRotateHandle(box: OverlayBox, alpha: Float, glyph: VectorPainter) {
    val handle = box.corners[2]
    val radius = 12.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.3f * alpha), radius + 1.dp.toPx(), handle)
    drawCircle(ConcatColors.accent.copy(alpha = alpha), radius, handle)
    val glyphSize = 14.dp.toPx()
    translate(handle.x - glyphSize / 2, handle.y - glyphSize / 2) {
        rotate(box.degrees, Offset(glyphSize / 2, glyphSize / 2)) {
            with(glyph) { draw(Size(glyphSize, glyphSize), alpha = alpha, colorFilter = ColorFilter.tint(ConcatColors.onAccent)) }
        }
    }
}
