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

internal enum class Grip { None, Body, Corner }

internal data class PreviewGestureContext(
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

internal data class PreviewGestureTarget(val clip: Clip?, val grip: Grip)

internal data class PreviewDragResult(val placement: Placement, val travelled: Float)

internal class PreviewDragState(
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

internal fun Modifier.previewGestures(context: PreviewGestureContext): Modifier = pointerInput(Unit) {
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
    finishPreviewGesture(
        PreviewGestureFinishRequest(context, clip, result, down.uptimeMillis, viewConfiguration.touchSlop, viewConfiguration.doubleTapTimeoutMillis),
    )
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
    val hit = previewHitTest(PreviewHitTestRequest(view, position, context.mainTrackId, point, frame, context.bounds.value))
    val over = hit?.takeIf { it.id != chosen?.id && it.trackId != context.mainTrackId }
    val chosenTarget = chosen?.let {
        chosenPreviewGestureTarget(ChosenPreviewGestureTargetRequest(context, it, over, point, frame, handleReach))
    }
    if (chosenTarget != null) return chosenTarget
    return PreviewGestureTarget(hit, if (hit == null) Grip.None else Grip.Body)
}

private fun chosenPreviewGestureTarget(request: ChosenPreviewGestureTargetRequest): PreviewGestureTarget? {
    val placement = request.context.live.value?.takeIf { it.first == request.chosen.id }?.second ?: Placement.of(request.chosen)
    val box = boxOf(ClipBoxRequest(request.context.view.value, request.chosen, placement, request.frame, request.context.bounds.value))
    val grip = when {
        box.corners.any { (it - request.point).getDistance() < request.handleReach } -> Grip.Corner
        request.over == null && box.contains(request.point, 6f) -> Grip.Body
        else -> Grip.None
    }
    return if (grip == Grip.None) null else PreviewGestureTarget(request.chosen, grip)
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
    val startBox = boxOf(ClipBoxRequest(view, clip, start, frame, context.bounds.value))
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
        centre = boxOf(ClipBoxRequest(context.view.value, state.clip, state.now, state.frame, context.bounds.value)).anchor + pan
        state.travelled += pan.getDistance() + 20f
    } else {
        val change = event.changes.firstOrNull { it.id == state.down.id } ?: pressed.first()
        centre = change.position - state.grabbed
        state.travelled = max(state.travelled, (change.position - state.down.position).getDistance())
    }
    return snapPreviewPlacement(PreviewSnapRequest(context, state, centre, scale, rotation, snapPx))
}

private fun snapPreviewPlacement(request: PreviewSnapRequest): Placement {
    val state = request.state
    val snapX = abs(request.centre.x - state.frame.width / 2) < request.snapPx
    val snapY = abs(request.centre.y - state.frame.height / 2) < request.snapPx
    val snappedCentre = Offset(
        if (snapX) state.frame.width / 2 else request.centre.x,
        if (snapY) state.frame.height / 2 else request.centre.y,
    )
    val snappedRotation = snapPreviewRotation(request.rotation, state)
    if ((snapX && !state.snapped.first) || (snapY && !state.snapped.second)) {
        request.context.haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    state.snapped = Pair(snapX, snapY)
    return Placement(
        request.scale,
        ((snappedCentre.x - state.frame.width / 2) / state.frame.width).toDouble(),
        ((snappedCentre.y - state.frame.height / 2) / state.frame.height).toDouble(),
        snappedRotation,
    )
}

private fun snapPreviewRotation(rotation: Double, state: PreviewDragState): Double {
    val square = (rotation / 90.0).roundToInt() * 90.0
    return if (abs(rotation - square) < 4.0 && (state.grip == Grip.Corner || state.twoFingers)) square else rotation
}

private fun finishPreviewGesture(request: PreviewGestureFinishRequest) {
    val context = request.context
    val clip = request.clip
    if (request.result.travelled > request.touchSlop) {
        context.live.value = clip.id to request.result.placement
        context.onCommit.value(clip.id, request.result.placement)
        return
    }
    val previousTap = context.lastTap.value
    if (clip.kind == ClipKind.Text && previousTap.first == clip.id && request.time - previousTap.second < request.doubleTapTimeout + 100) {
        context.onEditText.value(clip.id)
        context.lastTap.value = Pair("", 0L)
    } else {
        context.lastTap.value = Pair(clip.id, request.time)
    }
}

