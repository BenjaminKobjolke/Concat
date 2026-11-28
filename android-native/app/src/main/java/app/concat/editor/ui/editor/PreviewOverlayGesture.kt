// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class Grip { None, Body, Corner }

internal data class OverlayGestureContext(
    val view: State<ProjectView>,
    val bounds: State<Map<String, ClipBounds>>,
    val position: State<Double>,
    val selected: State<String?>,
    val mainTrackId: State<String?>,
    val live: MutableState<Pair<String, Placement>?>,
    val guides: MutableState<Pair<Boolean, Boolean>>,
    val moving: MutableState<Boolean>,
    val lastTap: MutableState<Pair<String, Long>>,
    val select: (String?) -> Unit,
    val commit: (String, Placement) -> Unit,
    val editText: (String) -> Unit,
    val haptics: HapticFeedback,
)

private data class Target(val clip: Clip, val grip: Grip)
private data class DragStart(
    val down: PointerInputChange,
    val clip: Clip,
    val grip: Grip,
    val frame: Size,
    val start: Placement,
    val box: OverlayBox,
    val grabbed: Offset,
    val view: ProjectView,
    val bounds: Map<String, ClipBounds>,
)
private data class DragProgress(val placement: Placement, val travelled: Float, val twoFingers: Boolean, val snapped: Pair<Boolean, Boolean>)
private data class TransformStep(val scale: Double, val rotation: Double, val centre: Offset, val travelled: Float, val twoFingers: Boolean)

internal fun Modifier.previewGestures(context: OverlayGestureContext) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val frame = Size(size.width.toFloat(), size.height.toFloat())
        handleGesture(down, frame, viewConfiguration.touchSlop, viewConfiguration.doubleTapTimeoutMillis, 24.dp.toPx(), 8.dp.toPx(), context)
    }
}

private suspend fun AwaitPointerEventScope.handleGesture(
    down: PointerInputChange,
    frame: Size,
    touchSlop: Float,
    doubleTapTimeout: Long,
    handleReach: Float,
    snapPx: Float,
    context: OverlayGestureContext,
) {
    val target = chooseTarget(context, down.position, frame, handleReach)
    if (target == null) {
        if (awaitTap(down, touchSlop)) context.select(null)
        return
    }
    down.consume()
    val clip = target.clip
    if (clip.id != context.selected.value) context.select(clip.id)
    val start = context.live.value?.takeIf { it.first == clip.id }?.second ?: Placement.of(clip)
    val startBox = boxOf(context.view.value, clip, start, frame, context.bounds.value)
    val drag = DragStart(down, clip, target.grip, frame, start, startBox, down.position - startBox.anchor, context.view.value, context.bounds.value)
    context.moving.value = true
    val progress = trackGesture(drag, touchSlop, snapPx, context)
    context.moving.value = false
    context.guides.value = false to false
    finishGesture(progress, drag, touchSlop, doubleTapTimeout, context)
}

private fun chooseTarget(context: OverlayGestureContext, point: Offset, frame: Size, handleReach: Float): Target? {
    val view = context.view.value
    val chosen = view.clip(context.selected.value)?.takeIf { it.placeable && context.position.value >= it.start && context.position.value < it.end }
    val hit = hitTest(view, context.position.value, context.mainTrackId.value, context.bounds.value, point, frame)
    val over = hit?.takeIf { it.id != chosen?.id && it.trackId != context.mainTrackId.value }
    val grip = selectedGrip(context, chosen, over, point, frame, handleReach)
    if (grip != Grip.None && chosen != null) return Target(chosen, grip)
    return hit?.let { Target(it, Grip.Body) }
}

private fun selectedGrip(
    context: OverlayGestureContext,
    chosen: Clip?,
    over: Clip?,
    point: Offset,
    frame: Size,
    handleReach: Float,
): Grip {
    if (chosen == null) return Grip.None
    val placement = context.live.value?.takeIf { it.first == chosen.id }?.second ?: Placement.of(chosen)
    val box = boxOf(context.view.value, chosen, placement, frame, context.bounds.value)
    return when {
        box.corners.any { (it - point).getDistance() < handleReach } -> Grip.Corner
        over == null && box.contains(point, 6f) -> Grip.Body
        else -> Grip.None
    }
}

private suspend fun AwaitPointerEventScope.awaitTap(down: PointerInputChange, touchSlop: Float): Boolean {
    var tapped = true
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if ((change.position - down.position).getDistance() > touchSlop) tapped = false
        if (!change.pressed) break
    }
    return tapped
}

private suspend fun AwaitPointerEventScope.trackGesture(
    drag: DragStart,
    touchSlop: Float,
    snapPx: Float,
    context: OverlayGestureContext,
): DragProgress {
    var progress = DragProgress(drag.start, 0f, false, false to false)
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        val step = transformStep(event, pressed, drag, progress)
        progress = snappedProgress(step, progress, drag, snapPx, context)
        if (progress.travelled > touchSlop) {
            context.live.value = drag.clip.id to progress.placement
            context.guides.value = progress.snapped
        }
        event.changes.forEach { it.consume() }
    }
    return progress
}

private fun transformStep(event: PointerEvent, pressed: List<PointerInputChange>, drag: DragStart, progress: DragProgress): TransformStep {
    val twoFingers = progress.twoFingers || pressed.size > 1
    if (drag.grip == Grip.Corner && !twoFingers) {
        val change = event.changes.firstOrNull { it.id == drag.down.id }
            ?: return TransformStep(progress.placement.scale, progress.placement.rotation, drag.box.anchor, progress.travelled, twoFingers)
        val from = drag.down.position - drag.box.anchor
        val to = change.position - drag.box.anchor
        val scale = (drag.start.scale * to.getDistance() / max(1f, from.getDistance())).coerceIn(0.05, 8.0)
        val rotation = drag.start.rotation + (atan2(to.y, to.x) - atan2(from.y, from.x)) * 180.0 / PI
        return TransformStep(scale, rotation, drag.box.anchor, max(progress.travelled, (change.position - drag.down.position).getDistance()), twoFingers)
    }
    if (pressed.size > 1) {
        val scale = (progress.placement.scale * event.calculateZoom()).coerceIn(0.05, 8.0)
        val rotation = progress.placement.rotation + event.calculateRotation()
        val pan = event.calculatePan()
        val box = boxOf(drag.view, drag.clip, progress.placement, drag.frame, drag.bounds)
        return TransformStep(scale, rotation, box.anchor + pan, progress.travelled + pan.getDistance() + 20f, twoFingers)
    }
    val change = event.changes.firstOrNull { it.id == drag.down.id } ?: pressed.first()
    return TransformStep(
        progress.placement.scale,
        progress.placement.rotation,
        change.position - drag.grabbed,
        max(progress.travelled, (change.position - drag.down.position).getDistance()),
        twoFingers,
    )
}

private fun snappedProgress(step: TransformStep, previous: DragProgress, drag: DragStart, snapPx: Float, context: OverlayGestureContext): DragProgress {
    var centre = step.centre
    val snapX = abs(centre.x - drag.frame.width / 2) < snapPx
    val snapY = abs(centre.y - drag.frame.height / 2) < snapPx
    if (snapX) centre = Offset(drag.frame.width / 2, centre.y)
    if (snapY) centre = Offset(centre.x, drag.frame.height / 2)
    var rotation = step.rotation
    val square = (rotation / 90.0).roundToInt() * 90.0
    if (abs(rotation - square) < 4.0 && (drag.grip == Grip.Corner || step.twoFingers)) rotation = square
    val snapped = snapX to snapY
    if ((snapX && !previous.snapped.first) || (snapY && !previous.snapped.second)) {
        context.haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    return DragProgress(
        Placement(step.scale, ((centre.x - drag.frame.width / 2) / drag.frame.width).toDouble(), ((centre.y - drag.frame.height / 2) / drag.frame.height).toDouble(), rotation),
        step.travelled,
        step.twoFingers,
        snapped,
    )
}

private fun finishGesture(progress: DragProgress, drag: DragStart, touchSlop: Float, doubleTapTimeout: Long, context: OverlayGestureContext) {
    if (progress.travelled > touchSlop) {
        context.live.value = drag.clip.id to progress.placement
        context.commit(drag.clip.id, progress.placement)
        return
    }
    val time = drag.down.uptimeMillis
    val previous = context.lastTap.value
    if (drag.clip.kind == ClipKind.Text && previous.first == drag.clip.id && time - previous.second < doubleTapTimeout + 100) {
        context.editText(drag.clip.id)
        context.lastTap.value = "" to 0L
    } else {
        context.lastTap.value = drag.clip.id to time
    }
}

private fun hitTest(view: ProjectView, position: Double, mainTrackId: String?, bounds: Map<String, ClipBounds>, point: Offset, frame: Size): Clip? {
    val order = view.tracks.map { it.id }
    return view.clips.filter { it.placeable && position >= it.start && position < it.end }
        .sortedWith(compareByDescending<Clip> { it.trackId != mainTrackId }.thenByDescending { it.kind == ClipKind.Text }.thenByDescending { order.indexOf(it.trackId) })
        .firstOrNull { boxOf(view, it, Placement.of(it), frame, bounds).contains(point, 8f) }
}
