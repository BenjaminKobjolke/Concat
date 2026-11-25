// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Outcome { Tap, Pan, LongPress }
internal fun Modifier.timelineInteraction(state: TimelineInteractionState): Modifier =
    pointerInput(Unit) {
        var pxPerSecond by state.pxPerSecond
        var drag by state.drag
        var fling by state.fling
        var scrollY by state.scrollY
        val scope = state.scope
        val haptics = state.haptics
        val knob = state.knob
        val currentView by state.currentView
        val currentPosition by state.currentPosition
        val currentSelected by state.currentSelected
        val scrub by state.scrub
        val choose by state.choose
        val move by state.move
        val trim by state.trim
        val addMedia by state.addMedia
        val transition by state.transition
        val muteTrack by state.muteTrack
        val openCover by state.openCover
                fun scaleNow() = if (pxPerSecond <= 0f) 64.dp.toPx() else pxPerSecond
                val snapPx = 10.dp.toPx()
                val handlePx = 16.dp.toPx()
                val handleWidth = HANDLE.toPx()

                /** The nearest snap point to any of [times] within reach, as (point, which time). */
                fun snap(times: List<Double>, exclude: String, scale: Float, playhead: Double): Pair<Double, Int>? {
                    val points = ArrayList<Double>()
                    points += playhead
                    points += 0.0
                    for (clip in currentView.clips) {
                        if (clip.id == exclude) continue
                        points += clip.start
                        points += clip.end
                    }
                    var best: Pair<Double, Int>? = null
                    var bestDistance = snapPx / scale.toDouble()
                    times.forEachIndexed { which, time ->
                        for (point in points) {
                            val distance = abs(point - time)
                            if (distance < bestDistance) {
                                bestDistance = distance
                                best = point to which
                            }
                        }
                    }
                    return best
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    scope.launch { knob.animateTo(1f, Motion.knob()) }
                    try {
                        val view = currentView
                        val scale = scaleNow()
                        val playhead = currentPosition
                        val layout = layoutLanes(view, size.height.toFloat(), scrollY, this)
                        val centre = size.width / 2f
                        fun timeAt(x: Float) = playhead + (x - centre) / scale
                        fun xOf(t: Double) = centre + ((t - playhead) * scale).toFloat()
                        val downRowIndex = layout.rowAt(down.position.y)
                        val downRow = layout.rows.getOrNull(downRowIndex)
                        val downTime = timeAt(down.position.x)
                        val hit = view.clips.firstOrNull {
                            it.trackId == downRow?.trackId && downTime >= it.start && downTime < it.end &&
                                down.position.y >= downRow.top && down.position.y <= downRow.bottom
                        }

                        // A handle of the selected clip: trim that end.
                        val chosen = view.clip(currentSelected)
                        val edge = chosen?.let { clip ->
                            val row = layout.rows.getOrNull(layout.rowOfTrack(clip.trackId)) ?: return@let null
                            val inLane = down.position.y >= row.top - 6f && down.position.y <= row.bottom + 6f
                            val left = xOf(clip.start)
                            val right = xOf(clip.end)
                            when {
                                !inLane -> null
                                down.position.x in (left - handleWidth - handlePx / 2)..(left + handlePx / 2) &&
                                    down.position.x < (left + right) / 2 -> "start"
                                down.position.x in (right - handlePx / 2)..(right + handleWidth + handlePx / 2) -> "end"
                                else -> null
                            }
                        }
                        if (chosen != null && edge != null) {
                            down.consume()
                            val row = layout.rowOfTrack(chosen.trackId)
                            val (low, high) = trimRange(view, chosen, edge)
                            var delta = 0.0
                            var lastSnap: Double? = null
                            var atLimit = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val wanted = ((change.position.x - down.position.x) / scale).toDouble()
                                val raw = wanted.coerceIn(low, high)
                                val limited = wanted != raw
                                if (limited && !atLimit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                atLimit = limited
                                val moving = if (edge == "start") chosen.start + raw else chosen.end + raw
                                val snapped = snap(listOf(moving), chosen.id, scale, playhead)
                                if (snapped != null && snapped.first != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = snapped?.first
                                delta = snapped?.let { (point, _) -> (point - if (edge == "start") chosen.start else chosen.end).coerceIn(low, high) } ?: raw
                                drag = if (edge == "start") {
                                    DragPreview(chosen.id, chosen.start + delta, chosen.duration - delta, row, snapped?.first)
                                } else {
                                    DragPreview(chosen.id, chosen.start, chosen.duration + delta, row, snapped?.first)
                                }
                            }
                            drag = null
                            if (abs(delta) > 1e-4) trim(chosen.id, edge, delta)
                            return@awaitEachGesture
                        }

                        // Wait to see what this touch is: a tap, a drag, or a press held still.
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

                        if (outcome == Outcome.Tap) {
                            val tapped = buttonAt(view, layout, TimelineHitPoint(down.position, ::xOf), this)
                            when (tapped) {
                                is TimelineButton.Add -> addMedia()
                                is TimelineButton.Cut -> transition(tapped.nextClipId)
                                is TimelineButton.Mute -> muteTrack(tapped.trackId, tapped.mute)
                                TimelineButton.Cover -> openCover()
                                null -> choose(if (hit?.id == currentSelected) null else hit?.id)
                            }
                            return@awaitEachGesture
                        }

                        if (outcome == Outcome.LongPress && hit != null) {
                            // Picked up: follow the finger, then drop.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            choose(hit.id)
                            val grab = downTime - hit.start
                            var start = hit.start
                            var row = layout.rowOfTrack(hit.trackId)
                            var lastSnap: Double? = null
                            drag = DragPreview(hit.id, start, hit.duration, row, null)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val wanted = max(0.0, timeAt(change.position.x) - grab)
                                val snapped = snap(listOf(wanted, wanted + hit.duration), hit.id, scale, playhead)
                                if (snapped != null && snapped.first != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = snapped?.first
                                start = when {
                                    snapped == null -> wanted
                                    snapped.second == 0 -> snapped.first
                                    else -> snapped.first - hit.duration
                                }.coerceAtLeast(0.0)
                                row = layout.rowAt(change.position.y).coerceIn(-1, layout.rows.size)
                                drag = DragPreview(hit.id, start, hit.duration, row, snapped?.first)
                            }
                            drag = null
                            val target = when {
                                row < 0 -> LaneTarget.NewTop
                                row >= layout.rows.size -> LaneTarget.NewBottom
                                else -> LaneTarget.Existing(layout.rows[row].trackId)
                            }
                            val sameLane = target is LaneTarget.Existing && target.trackId == hit.trackId
                            if (!sameLane || abs(start - hit.start) > 1e-4) move(hit.id, start, target)
                            return@awaitEachGesture
                        }

                        if (vertical) {
                            // Lanes taller than the space: scroll them.
                            val overflow = max(0f, layout.bottom - layout.top - (size.height - RULER.toPx() - 16.dp.toPx()))
                            var offset = scrollY ?: autoScroll(layout, size.height.toFloat(), this)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                offset = (offset - (change.position.y - change.previousPosition.y)).coerceIn(0f, overflow)
                                scrollY = offset
                                change.consume()
                            }
                            return@awaitEachGesture
                        }

                        // Scrub and zoom, then fling what is left of a one-finger drag.
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var at = playhead
                        var pinched = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } > 1) pinched = true
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) pxPerSecond = (scaleNow() * zoom).coerceIn(8.dp.toPx(), 600.dp.toPx())
                            val pan = event.calculatePan()
                            if (pan.x != 0f) {
                                at = (at - pan.x / scaleNow()).coerceIn(0.0, currentView.duration)
                                scrub(at)
                            }
                            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            event.changes.forEach { it.consume() }
                        }
                        if (!pinched) {
                            val velocity = tracker.calculateVelocity().x
                            if (abs(velocity) > 200f) {
                                val from = at
                                val flingScale = scaleNow()
                                val end = currentView.duration
                                fling = scope.launch {
                                    animateDecay(0f, -velocity, FloatExponentialDecaySpec()) { value, _ ->
                                        scrub((from + value / flingScale).coerceIn(0.0, end))
                                    }
                                }
                            }
                        }
                    } finally {
                        scope.launch { knob.animateTo(0f, Motion.knob()) }
                    }
                }
    }
