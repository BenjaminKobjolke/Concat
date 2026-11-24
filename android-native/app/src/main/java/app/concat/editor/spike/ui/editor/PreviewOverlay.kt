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

private enum class Grip { None, Body, Corner }

private data class PreviewGestureContext(
    val view: State<ProjectView>,
    val position: State<Double>,
    val selected: State<String?>,
    val bounds: State<Map<String, ClipBounds>>,
    val mainTrackId: String?,
    val live: MutableState<Pair<String, Placement>?>,
    val guides: MutableState<Pair<Boolean, Boolean>>,
    val moving: MutableState<Boolean>,
    val lastTap: MutableState<Pair<String, Long>>,
    val onSelect: State<(String?) -> Unit>,
    val onCommit: State<(String, Placement) -> Unit>,
    val onEditText: State<(String) -> Unit>,
    val haptics: HapticFeedback,
)

private data class PreviewGestureTarget(val clip: Clip?, val grip: Grip)

private data class PreviewDragResult(val placement: Placement, val travelled: Float)

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
    view: ProjectView,
    position: Double,
    selected: String?,
    mainTrackId: String?,
    bounds: Map<String, ClipBounds>,
    actions: PreviewOverlayActions,
    modifier: Modifier = Modifier,
) {
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

private fun Modifier.previewGestures(context: PreviewGestureContext): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        handlePreviewGesture(context, down, Size(size.width.toFloat(), size.height.toFloat()))
    }
}

private suspend fun AwaitPointerEventScope.handlePreviewGesture(
    context: PreviewGestureContext,
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    frame: Size,
) {
    val target = resolvePreviewGestureTarget(context, down.position, frame, 24.dp.toPx())
    val clip = target.clip
    if (clip == null) {
        if (awaitEmptyTap(down, viewConfiguration.touchSlop)) context.onSelect.value(null)
        return
    }

    down.consume()
    if (clip.id != context.selected.value) context.onSelect.value(clip.id)
    val start = context.live.value?.takeIf { it.first == clip.id }?.second ?: Placement.of(clip)
    context.moving.value = true
    val result = awaitPreviewDrag(context, down, frame, clip, target.grip, start, 8.dp.toPx())
    context.moving.value = false
    context.guides.value = Pair(false, false)
    finishPreviewGesture(context, clip, result, down.uptimeMillis, viewConfiguration.touchSlop, viewConfiguration.doubleTapTimeoutMillis)
}

private fun resolvePreviewGestureTarget(
    context: PreviewGestureContext,
    point: Offset,
    frame: Size,
    handleReach: Float,
): PreviewGestureTarget {
    val view = context.view.value
    val position = context.position.value
    val chosen = view.clip(context.selected.value)?.takeIf { it.placeable && position >= it.start && position < it.end }
    val hit = previewHitTest(view, position, context.mainTrackId, point, frame, context.bounds.value)
    val over = hit?.takeIf { it.id != chosen?.id && it.trackId != context.mainTrackId }
    var grip = Grip.None
    if (chosen != null) {
        val placement = context.live.value?.takeIf { it.first == chosen.id }?.second ?: Placement.of(chosen)
        val box = boxOf(view, chosen, placement, frame, context.bounds.value)
        grip = when {
            box.corners.any { (it - point).getDistance() < handleReach } -> Grip.Corner
            over == null && box.contains(point, 6f) -> Grip.Body
            else -> Grip.None
        }
        if (grip != Grip.None) return PreviewGestureTarget(chosen, grip)
    }
    return PreviewGestureTarget(hit, if (hit == null) Grip.None else Grip.Body)
}

private suspend fun AwaitPointerEventScope.awaitEmptyTap(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    touchSlop: Float,
): Boolean {
    var tapped = true
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if ((change.position - down.position).getDistance() > touchSlop) tapped = false
        if (!change.pressed) break
    }
    return tapped
}

private class PreviewDragState(
    val clip: Clip,
    val grip: Grip,
    val down: androidx.compose.ui.input.pointer.PointerInputChange,
    val frame: Size,
    val start: Placement,
    val startBox: Box,
    val grabbed: Offset,
    var now: Placement = start,
    var travelled: Float = 0f,
    var twoFingers: Boolean = false,
    var snapped: Pair<Boolean, Boolean> = Pair(false, false),
)

private suspend fun AwaitPointerEventScope.awaitPreviewDrag(
    context: PreviewGestureContext,
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    frame: Size,
    clip: Clip,
    grip: Grip,
    start: Placement,
    snapPx: Float,
): PreviewDragResult {
    val view = context.view.value
    val startBox = boxOf(view, clip, start, frame, context.bounds.value)
    val state = PreviewDragState(clip, grip, down, frame, start, startBox, down.position - startBox.anchor)
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        if (event.changes.count { it.pressed } > 1) state.twoFingers = true
        state.now = nextPreviewPlacement(context, event, state, snapPx) ?: break
        if (state.travelled > viewConfiguration.touchSlop) {
            context.live.value = clip.id to state.now
            context.guides.value = state.snapped
        }
        event.changes.forEach { it.consume() }
    }
    return PreviewDragResult(state.now, state.travelled)
}

private fun nextPreviewPlacement(
    context: PreviewGestureContext,
    event: PointerEvent,
    state: PreviewDragState,
    snapPx: Float,
): Placement? {
    val pressed = event.changes.filter { it.pressed }
    var scale = state.now.scale
    var rotation = state.now.rotation
    val centre: Offset
    if (state.grip == Grip.Corner && !state.twoFingers) {
        val change = event.changes.firstOrNull { it.id == state.down.id } ?: return null
        val from = state.down.position - state.startBox.anchor
        val to = change.position - state.startBox.anchor
        scale = (state.start.scale * to.getDistance() / max(1f, from.getDistance())).coerceIn(0.05, 8.0)
        rotation = state.start.rotation + (atan2(to.y, to.x) - atan2(from.y, from.x)) * 180.0 / PI
        centre = state.startBox.anchor
        state.travelled = max(state.travelled, (change.position - state.down.position).getDistance())
    } else if (pressed.size > 1) {
        scale = (state.now.scale * event.calculateZoom()).coerceIn(0.05, 8.0)
        rotation = state.now.rotation + event.calculateRotation()
        val pan = event.calculatePan()
        centre = boxOf(context.view.value, state.clip, state.now, state.frame, context.bounds.value).anchor + pan
        state.travelled += pan.getDistance() + 20f
    } else {
        val change = event.changes.firstOrNull { it.id == state.down.id } ?: pressed.first()
        centre = change.position - state.grabbed
        state.travelled = max(state.travelled, (change.position - state.down.position).getDistance())
    }
    return snapPreviewPlacement(context, state, centre, scale, rotation, snapPx)
}

private fun snapPreviewPlacement(
    context: PreviewGestureContext,
    state: PreviewDragState,
    centre: Offset,
    scale: Double,
    rotation: Double,
    snapPx: Float,
): Placement {
    val snapX = abs(centre.x - state.frame.width / 2) < snapPx
    val snapY = abs(centre.y - state.frame.height / 2) < snapPx
    val snappedCentre = Offset(
        if (snapX) state.frame.width / 2 else centre.x,
        if (snapY) state.frame.height / 2 else centre.y,
    )
    val square = (rotation / 90.0).roundToInt() * 90.0
    val snappedRotation = if (abs(rotation - square) < 4.0 && (state.grip == Grip.Corner || state.twoFingers)) square else rotation
    if ((snapX && !state.snapped.first) || (snapY && !state.snapped.second)) {
        context.haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    state.snapped = Pair(snapX, snapY)
    return Placement(
        scale,
        ((snappedCentre.x - state.frame.width / 2) / state.frame.width).toDouble(),
        ((snappedCentre.y - state.frame.height / 2) / state.frame.height).toDouble(),
        snappedRotation,
    )
}

private fun finishPreviewGesture(
    context: PreviewGestureContext,
    clip: Clip,
    result: PreviewDragResult,
    time: Long,
    touchSlop: Float,
    doubleTapTimeout: Long,
) {
    if (result.travelled > touchSlop) {
        context.live.value = clip.id to result.placement
        context.onCommit.value(clip.id, result.placement)
        return
    }
    val previousTap = context.lastTap.value
    if (clip.kind == ClipKind.Text && previousTap.first == clip.id && time - previousTap.second < doubleTapTimeout + 100) {
        context.onEditText.value(clip.id)
        context.lastTap.value = Pair("", 0L)
    } else {
        context.lastTap.value = Pair(clip.id, time)
    }
}

private fun previewHitTest(view: ProjectView, position: Double, mainTrackId: String?, point: Offset, frame: Size, bounds: Map<String, ClipBounds>): Clip? {
    val order = view.tracks.map { it.id }
    return view.clips.filter { it.placeable && position >= it.start && position < it.end }
        .sortedWith(
            compareByDescending<Clip> { it.trackId != mainTrackId }
                .thenByDescending { it.kind == ClipKind.Text }
                .thenByDescending { order.indexOf(it.trackId) },
        )
        .firstOrNull { boxOf(view, it, Placement.of(it), frame, bounds).contains(point, 8f) }
}

private fun DrawScope.drawPreviewOverlay(drawing: PreviewDrawing) {
    val frame = size
    drawPreviewGuides(frame, drawing.moving, drawing.guides)
    val clip = drawing.view.clip(drawing.selected)?.takeIf {
        it.placeable && drawing.position >= it.start && drawing.position < it.end
    } ?: return
    val placement = drawing.live?.takeIf { it.first == clip.id }?.second ?: Placement.of(clip)
    val box = boxOf(drawing.view, clip, placement, frame, drawing.bounds)
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
