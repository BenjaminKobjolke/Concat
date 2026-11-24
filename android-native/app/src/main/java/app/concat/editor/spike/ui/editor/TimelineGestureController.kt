// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class GestureFrame(
    val down: PointerInputChange,
    val view: ProjectView,
    val scale: Float,
    val playhead: Double,
    val layout: LaneLayout,
    val centre: Float,
    val snapPx: Float,
) {
    fun timeAt(x: Float) = playhead + (x - centre) / scale
    fun xOf(time: Double) = centre + ((time - playhead) * scale).toFloat()

    fun snap(times: List<Double>, exclude: String): Pair<Double, Int>? {
        val points = buildList {
            add(playhead)
            add(0.0)
            view.clips.filterNot { it.id == exclude }.forEach { clip ->
                add(clip.start)
                add(clip.end)
            }
        }
        var best: Pair<Double, Int>? = null
        var bestDistance = snapPx / scale.toDouble()
        times.forEachIndexed { index, time ->
            points.forEach { point ->
                val distance = abs(point - time)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = point to index
                }
            }
        }
        return best
    }
}

private data class GestureIntent(val outcome: Outcome, val vertical: Boolean)

internal class TimelineGestureController(private val input: TimelineGestureContext) {
    suspend fun PointerInputScope.run() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            input.fling.value?.cancel()
            input.scope.launch { input.knob.animateTo(1f, Motion.knob()) }
            try {
                handleDown(down)
            } finally {
                input.scope.launch { input.knob.animateTo(0f, Motion.knob()) }
            }
        }
    }

    private suspend fun AwaitPointerEventScope.handleDown(down: PointerInputChange) {
        val view = input.currentView.value
        val scale = input.pxPerSecond.floatValue.takeIf { it > 0f } ?: 64.dp.toPx()
        val playhead = input.currentPosition.value
        val layout = layoutLanes(view, size.height.toFloat(), input.scrollY.value, this)
        val frame = GestureFrame(down, view, scale, playhead, layout, size.width / 2f, 10.dp.toPx())
        val downRow = layout.rows.getOrNull(layout.rowAt(down.position.y))
        val downTime = frame.timeAt(down.position.x)
        val hit = view.clips.firstOrNull {
            it.trackId == downRow?.trackId && downTime >= it.start && downTime < it.end &&
                down.position.y >= downRow.top && down.position.y <= downRow.bottom
        }
        val chosen = view.clip(input.currentSelected.value)
        val edge = chosen?.let { this.selectedEdge(frame, it) }
        if (chosen != null && edge != null) {
            down.consume()
            trim(frame, chosen, edge)
            return
        }

        val intent = awaitIntent(down)
        when {
            intent.outcome == Outcome.Tap -> tap(frame, hit)
            intent.outcome == Outcome.LongPress && hit != null -> move(frame, hit)
            intent.vertical -> scrollLanes(frame)
            else -> scrub(frame)
        }
    }

    private fun Density.selectedEdge(frame: GestureFrame, clip: Clip): String? {
        val row = frame.layout.rows.getOrNull(frame.layout.rowOfTrack(clip.trackId)) ?: return null
        val inLane = frame.down.position.y >= row.top - 6f && frame.down.position.y <= row.bottom + 6f
        val left = frame.xOf(clip.start)
        val right = frame.xOf(clip.end)
        val handlePx = 16.dp.toPx()
        val handleWidth = HANDLE.toPx()
        return when {
            !inLane -> null
            frame.down.position.x in (left - handleWidth - handlePx / 2)..(left + handlePx / 2) &&
                frame.down.position.x < (left + right) / 2 -> "start"
            frame.down.position.x in (right - handlePx / 2)..(right + handleWidth + handlePx / 2) -> "end"
            else -> null
        }
    }

    private suspend fun AwaitPointerEventScope.awaitIntent(down: PointerInputChange): GestureIntent {
        val slop = viewConfiguration.touchSlop
        var outcome = Outcome.LongPress
        var vertical = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) {
                    outcome = Outcome.Pan
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    outcome = Outcome.Tap
                    break
                }
                val moved = change.position - down.position
                if (moved.getDistance() > slop) {
                    outcome = Outcome.Pan
                    vertical = abs(moved.y) > abs(moved.x) * 1.4f
                    break
                }
            }
        }
        return GestureIntent(outcome, vertical)
    }

    private suspend fun AwaitPointerEventScope.tap(frame: GestureFrame, hit: Clip?) {
        when (val tapped = buttonAt(frame.view, frame.layout, frame.down.position, frame::xOf, this)) {
            is TimelineButton.Add -> input.addMedia.value()
            is TimelineButton.Cut -> input.transition.value(tapped.nextClipId)
            is TimelineButton.Mute -> input.muteTrack.value(tapped.trackId, tapped.mute)
            TimelineButton.Cover -> input.scrub.value(0.0)
            null -> input.choose.value(if (hit?.id == input.currentSelected.value) null else hit?.id)
        }
    }

    private suspend fun AwaitPointerEventScope.trim(frame: GestureFrame, clip: Clip, edge: String) {
        val (low, high) = trimRange(frame.view, clip, edge)
        var delta = 0.0
        var lastSnap: Double? = null
        var atLimit = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == frame.down.id } ?: break
            if (!change.pressed) break
            change.consume()
            val wanted = ((change.position.x - frame.down.position.x) / frame.scale).toDouble()
            val raw = wanted.coerceIn(low, high)
            val limited = wanted != raw
            if (limited && !atLimit) input.haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            atLimit = limited
            val moving = if (edge == "start") clip.start + raw else clip.end + raw
            val snapped = frame.snap(listOf(moving), clip.id)
            if (snapped != null && snapped.first != lastSnap) {
                input.haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
            lastSnap = snapped?.first
            delta = snapped?.let { (point, _) -> (point - if (edge == "start") clip.start else clip.end).coerceIn(low, high) } ?: raw
            val row = frame.layout.rowOfTrack(clip.trackId)
            input.drag.value = if (edge == "start") {
                DragPreview(clip.id, clip.start + delta, clip.duration - delta, row, snapped?.first)
            } else {
                DragPreview(clip.id, clip.start, clip.duration + delta, row, snapped?.first)
            }
        }
        input.drag.value = null
        if (abs(delta) > 1e-4) input.trim.value(clip.id, edge, delta)
    }

    private suspend fun AwaitPointerEventScope.move(frame: GestureFrame, clip: Clip) {
        input.haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        input.choose.value(clip.id)
        val grab = frame.timeAt(frame.down.position.x) - clip.start
        var start = clip.start
        var row = frame.layout.rowOfTrack(clip.trackId)
        var lastSnap: Double? = null
        input.drag.value = DragPreview(clip.id, start, clip.duration, row, null)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == frame.down.id } ?: break
            if (!change.pressed) break
            change.consume()
            val wanted = max(0.0, frame.timeAt(change.position.x) - grab)
            val snapped = frame.snap(listOf(wanted, wanted + clip.duration), clip.id)
            if (snapped != null && snapped.first != lastSnap) {
                input.haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
            lastSnap = snapped?.first
            start = when {
                snapped == null -> wanted
                snapped.second == 0 -> snapped.first
                else -> snapped.first - clip.duration
            }.coerceAtLeast(0.0)
            row = frame.layout.rowAt(change.position.y).coerceIn(-1, frame.layout.rows.size)
            input.drag.value = DragPreview(clip.id, start, clip.duration, row, snapped?.first)
        }
        input.drag.value = null
        val target = when {
            row < 0 -> LaneTarget.NewTop
            row >= frame.layout.rows.size -> LaneTarget.NewBottom
            else -> LaneTarget.Existing(frame.layout.rows[row].trackId)
        }
        val sameLane = target is LaneTarget.Existing && target.trackId == clip.trackId
        if (!sameLane || abs(start - clip.start) > 1e-4) input.move.value(clip.id, start, target)
    }

    private suspend fun AwaitPointerEventScope.scrollLanes(frame: GestureFrame) {
        val overflow = max(0f, frame.layout.bottom - frame.layout.top - (size.height - RULER.toPx() - 16.dp.toPx()))
        var offset = input.scrollY.value ?: autoScroll(frame.layout, size.height.toFloat(), this)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == frame.down.id } ?: break
            if (!change.pressed) break
            offset = (offset - (change.position.y - change.previousPosition.y)).coerceIn(0f, overflow)
            input.scrollY.value = offset
            change.consume()
        }
    }

    private suspend fun AwaitPointerEventScope.scrub(frame: GestureFrame) {
        val tracker = VelocityTracker()
        tracker.addPosition(frame.down.uptimeMillis, frame.down.position)
        var at = frame.playhead
        var pinched = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } > 1) pinched = true
            val zoom = event.calculateZoom()
            if (zoom != 1f) {
                val currentScale = input.pxPerSecond.floatValue.takeIf { it > 0f } ?: 64.dp.toPx()
                input.pxPerSecond.floatValue = (currentScale * zoom).coerceIn(8.dp.toPx(), 600.dp.toPx())
            }
            val pan = event.calculatePan()
            if (pan.x != 0f) {
                val scale = input.pxPerSecond.floatValue.takeIf { it > 0f } ?: 64.dp.toPx()
                at = (at - pan.x / scale).coerceIn(0.0, input.currentView.value.duration)
                input.scrub.value(at)
            }
            event.changes.firstOrNull { it.id == frame.down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
            event.changes.forEach { it.consume() }
        }
        if (!pinched) this.fling(frame, tracker.calculateVelocity().x, at)
    }

    private fun Density.fling(frame: GestureFrame, velocity: Float, from: Double) {
        if (abs(velocity) <= 200f) return
        val scale = input.pxPerSecond.floatValue.takeIf { it > 0f } ?: 64.dp.toPx()
        val end = input.currentView.value.duration
        input.fling.value = input.scope.launch {
            animateDecay(0f, -velocity, FloatExponentialDecaySpec()) { value, _ ->
                input.scrub.value((from + value / scale).coerceIn(0.0, end))
            }
        }
    }
}
