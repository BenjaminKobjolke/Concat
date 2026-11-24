// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipBounds
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private data class PreviewDrawing(
    val view: ProjectView,
    val position: Double,
    val selected: String?,
    val bounds: Map<String, ClipBounds>,
    val live: Pair<String, Placement>?,
    val moving: Boolean,
    val guides: Pair<Boolean, Boolean>,
    val alpha: Float,
    val rotateGlyph: androidx.compose.ui.graphics.painter.Painter,
)

internal data class PreviewOverlayActions(
    val onSelect: (String?) -> Unit,
    val onCommit: (clipId: String, placement: Placement) -> Unit,
    val onEditText: (clipId: String) -> Unit,
)

internal data class PreviewOverlayState(
    val view: ProjectView,
    val position: Double,
    val selected: String?,
    val mainTrackId: String?,
    val bounds: Map<String, ClipBounds>,
    val actions: PreviewOverlayActions,
)

/**
 * Editing on the preview: tap a title, picture or overlay to select it;
 * one finger drags it, two pinch and turn it, a corner handle scales and
 * turns it about its centre. It snaps to the frame's centre lines (and to
 * square turns) with guides and a tick; the safe area shows while it moves.
 * The change is drawn here as it happens and written once, as the gesture
 * ends, through [onCommit]. A double tap on a title edits its words.
 */
@Composable
internal fun PreviewOverlay(
    state: PreviewOverlayState,
    modifier: Modifier = Modifier,
) {
    val view = state.view
    val position = state.position
    val selected = state.selected
    val mainTrackId = state.mainTrackId
    val bounds = state.bounds
    val actions = state.actions
    val haptics = LocalHapticFeedback.current
    val liveState = remember { mutableStateOf<Pair<String, Placement>?>(null) }
    var live by liveState
    val guidesState = remember { mutableStateOf(Pair(false, false)) }
    var guides by guidesState
    val movingState = remember { mutableStateOf(false) }
    var moving by movingState
    val currentView = rememberUpdatedState(view)
    val currentPosition = rememberUpdatedState(position)
    val currentSelected = rememberUpdatedState(selected)
    val currentBounds = rememberUpdatedState(bounds)
    val select = rememberUpdatedState(actions.onSelect)
    val commit = rememberUpdatedState(actions.onCommit)
    val editText = rememberUpdatedState(actions.onEditText)
    val rotateGlyph = rememberVectorPainter(ConcatIcons.Rotate)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        appear.snapTo(0f)
        if (selected != null) appear.animateTo(1f, Motion.tweenShort())
    }
    // The document caught up with what was drawn: let the live copy go.
    val committed = view.clip(live?.first)?.let { Placement.of(it) }
    LaunchedEffect(committed) { if (!moving) live = null }

    val lastTapState = remember { mutableStateOf(Pair("", 0L)) }
    val gestureContext = PreviewGestureContext(
        currentView, currentPosition, currentSelected, currentBounds, mainTrackId, liveState, guidesState,
        movingState, lastTapState, select, commit, editText, haptics,
    )

    Canvas(
        modifier.fillMaxSize().previewGestures(gestureContext),
    ) {
        drawPreviewOverlay(PreviewDrawing(view, position, selected, bounds, live, moving, guides, appear.value, rotateGlyph))
    }
}

private fun DrawScope.drawPreviewOverlay(drawing: PreviewDrawing) {
    val frame = size
    drawPreviewGuides(frame, drawing.moving, drawing.guides)
    val clip = drawing.view.clip(drawing.selected)?.takeIf {
        it.placeable && drawing.position >= it.start && drawing.position < it.end
    } ?: return
    val placement = drawing.live?.takeIf { it.first == clip.id }?.second ?: Placement.of(clip)
    val box = boxOf(ClipBoxRequest(drawing.view, clip, placement, frame, drawing.bounds))
    drawPreviewSelection(box, drawing.alpha, drawing.rotateGlyph)
}

private fun DrawScope.drawPreviewGuides(frame: Size, moving: Boolean, guides: Pair<Boolean, Boolean>) {
    if (!moving) return
    val insetX = frame.width * 0.06f
    val insetY = frame.height * 0.08f
    drawRect(
        Color.White.copy(alpha = 0.35f), Offset(insetX, insetY),
        Size(frame.width - insetX * 2, frame.height - insetY * 2),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
    if (guides.first) drawLine(ConcatColors.accent, Offset(frame.width / 2, 0f), Offset(frame.width / 2, frame.height), 1.5.dp.toPx())
    if (guides.second) drawLine(ConcatColors.accent, Offset(0f, frame.height / 2), Offset(frame.width, frame.height / 2), 1.5.dp.toPx())
}

private fun DrawScope.drawPreviewSelection(box: Box, alpha: Float, rotateGlyph: androidx.compose.ui.graphics.painter.Painter) {
    val stroke = 1.5.dp.toPx()
    val outline = Path().apply {
        val c = box.corners
        moveTo(c[0].x, c[0].y)
        for (i in 1..3) lineTo(c[i].x, c[i].y)
        close()
    }
    drawPath(outline, Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(stroke + 2.dp.toPx()))
    drawPath(outline, Color.White.copy(alpha = alpha), style = Stroke(stroke))
    val corners = box.corners
    for ((i, corner) in corners.withIndex()) {
        if (i == 2) continue
        drawCircle(Color.Black.copy(alpha = 0.3f * alpha), 7.dp.toPx(), corner)
        drawCircle(Color.White.copy(alpha = alpha), 6.dp.toPx(), corner)
    }
    val handle = corners[2]
    val radius = 12.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.3f * alpha), radius + 1.dp.toPx(), handle)
    drawCircle(ConcatColors.accent.copy(alpha = alpha), radius, handle)
    val glyphSize = 14.dp.toPx()
    translate(handle.x - glyphSize / 2, handle.y - glyphSize / 2) {
        rotate(box.degrees, Offset(glyphSize / 2, glyphSize / 2)) {
            with(rotateGlyph) { draw(Size(glyphSize, glyphSize), alpha = alpha, colorFilter = ColorFilter.tint(ConcatColors.onAccent)) }
        }
    }
}
