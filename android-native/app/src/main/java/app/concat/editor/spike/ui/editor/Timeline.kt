// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.media.MediaCache
import app.concat.editor.spike.media.StripKey
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.secondsLabel
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Inter
import app.concat.editor.spike.ui.theme.KindColors
import app.concat.editor.spike.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where a dragged clip lands: a lane that exists, or a new one above or below them all. */
sealed interface LaneTarget {
    data class Existing(val trackId: String) : LaneTarget
    data object NewTop : LaneTarget
    data object NewBottom : LaneTarget
}

/** What a finger is doing to a clip right now, drawn over the edit until it lifts. */
@Composable
fun Timeline(
    view: ProjectView,
    projectPath: String,
    media: MediaCache,
    position: Double,
    selected: String?,
    onScrub: (Double) -> Unit,
    onSelect: (String?) -> Unit,
    onMove: (clipId: String, start: Double, target: LaneTarget) -> Unit,
    onTrim: (clipId: String, edge: String, delta: Double) -> Unit,
    onAddMedia: () -> Unit,
    onTransition: (nextClipId: String) -> Unit,
    onMuteTrack: (trackId: String, muted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pxPerSecond by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<DragPreview?>(null) }
    var fling by remember { mutableStateOf<Job?>(null) }
    /** Vertical scroll when the lanes overflow; null follows the main track. */
    var scrollY by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val currentView by rememberUpdatedState(view)
    val currentPosition by rememberUpdatedState(position)
    val currentSelected by rememberUpdatedState(selected)
    val scrub by rememberUpdatedState(onScrub)
    val choose by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val trim by rememberUpdatedState(onTrim)
    val addMedia by rememberUpdatedState(onAddMedia)
    val transition by rememberUpdatedState(onTransition)
    val muteTrack by rememberUpdatedState(onMuteTrack)

    // The selection fades in; the playhead's knob springs while a finger is down.
    val selection = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected != null) {
            selection.snapTo(0f)
            selection.animateTo(1f, Motion.tweenShort())
        }
    }
    val knob = remember { Animatable(0f) }

    val glyphs = TimelineGlyphs(
        text = rememberVectorPainter(ConcatIcons.TextMark),
        music = rememberVectorPainter(ConcatIcons.Music),
        image = rememberVectorPainter(ConcatIcons.Image),
        film = rememberVectorPainter(ConcatIcons.Film),
        transition = rememberVectorPainter(ConcatIcons.Transition),
        sparkle = rememberVectorPainter(ConcatIcons.Sparkle),
        plus = rememberVectorPainter(ConcatIcons.Plus),
        volume = rememberVectorPainter(ConcatIcons.Volume),
        volumeOff = rememberVectorPainter(ConcatIcons.VolumeOff),
        filter = rememberVectorPainter(ConcatIcons.Filter),
        shapes = rememberVectorPainter(ConcatIcons.Shapes),
    )

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
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
                            val tapped = buttonAt(view, layout, down.position, ::xOf, this)
                            when (tapped) {
                                is TimelineButton.Add -> addMedia()
                                is TimelineButton.Cut -> transition(tapped.nextClipId)
                                is TimelineButton.Mute -> muteTrack(tapped.trackId, tapped.mute)
                                TimelineButton.Cover -> scrub(0.0)
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
            },
    ) {
        val scale = if (pxPerSecond <= 0f) 64.dp.toPx() else pxPerSecond
        val centre = size.width / 2f
        val layout = layoutLanes(view, size.height, scrollY, this)
        fun x(seconds: Double) = centre + ((seconds - position) * scale).toFloat()
        // Read so a strip or an outline landing draws the timeline again.
        media.version

        drawRect(ConcatColors.page)
        val main = layout.main
        val moving = drag

        clipRect(top = RULER.toPx()) {
            // Lanes, then their clips.
            for (row in layout.rows) {
                drawRoundRect(
                    if (row.main) ConcatColors.well else ConcatColors.launchPanel.copy(alpha = 0.6f),
                    Offset(0f, row.top),
                    Size(size.width, row.height),
                    CornerRadius(0f),
                )
            }
            for (row in layout.rows) {
                for (clip in view.clips) {
                    if (clip.trackId != row.trackId) continue
                    val beingMoved = moving?.clipId == clip.id
                    drawClip(
                        measurer, glyphs, media, projectPath, view, clip, x(clip.start), x(clip.end), row,
                        alpha = if (beingMoved) 0.3f else 1f,
                    )
                }
            }
            if (main != null) drawMainExtras(measurer, glyphs, media, view, main, selected, ::x)

            // The selection: a thick lime frame and chunky white handles.
            val chosen = view.clip(selected)
            if (chosen != null && moving?.clipId != chosen.id) {
                layout.rows.getOrNull(layout.rowOfTrack(chosen.trackId))?.let { row ->
                    drawSelection(measurer, chosen, x(chosen.start), x(chosen.end), row, selection.value)
                }
            }

            if (moving != null) {
                val clip = view.clip(moving.clipId)
                if (clip != null) {
                    val gap = layout.gap
                    val row = when {
                        moving.row < 0 -> LaneRow(-1, "", layout.top - gap - Dimens.overlayLane.toPx(), Dimens.overlayLane.toPx(), false)
                        moving.row >= layout.rows.size -> LaneRow(-1, "", layout.bottom + gap, Dimens.overlayLane.toPx(), false)
                        else -> layout.rows[moving.row]
                    }
                    if (moving.row < 0 || moving.row >= layout.rows.size) {
                        // A new lane is made where it drops.
                        val y = if (moving.row < 0) layout.top - gap / 2f else layout.bottom + gap / 2f
                        drawLine(
                            ConcatColors.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                        )
                    }
                    val left = x(moving.start)
                    val right = x(moving.start + moving.duration)
                    drawClip(measurer, glyphs, media, projectPath, view, clip, left, right, row, alpha = 0.92f)
                    drawSelection(measurer, clip.copy(start = moving.start, duration = moving.duration), left, right, row, 1f)
                }
                moving.snappedAt?.let { at ->
                    drawLine(ConcatColors.accent, Offset(x(at), RULER.toPx()), Offset(x(at), size.height), strokeWidth = 1.dp.toPx())
                }
            }
        }

        drawRuler(measurer, position, scale, centre)

        // The playhead: a 2dp lime line with a knob that springs under a finger.
        val head = ConcatColors.accent
        val knobY = RULER.toPx() - 7.dp.toPx()
        drawLine(head, Offset(centre, knobY), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
        val radius = (5 + 2.5f * knob.value).dp.toPx()
        drawCircle(head, radius, Offset(centre, knobY))
        drawCircle(ConcatColors.page, radius * 0.35f, Offset(centre, knobY))
    }
}

private val RULER = Dimens.ruler
private val SOUND_LANE = 40.dp
private val HANDLE = 14.dp
private val LANE_GAP = 6.dp

private enum class Outcome { Tap, Pan, LongPress }

/** The lanes laid out in [height], following the main track unless scrolled. */
