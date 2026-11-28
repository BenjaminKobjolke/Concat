// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
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
import app.concat.editor.api.Clip
import app.concat.editor.api.ProjectView
import app.concat.editor.media.MediaCache
import app.concat.editor.media.StripKey
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.secondsLabel
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Inter
import app.concat.editor.ui.theme.KindColors
import app.concat.editor.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** Where a dragged clip lands: a lane that exists, or a new one above or below them all. */
sealed interface LaneTarget {
    data class Existing(val trackId: String) : LaneTarget
    data object NewTop : LaneTarget
    data object NewBottom : LaneTarget
}

/** What a finger is doing to a clip right now, drawn over the edit until it lifts. */
internal data class DragPreview(
    val clipId: String,
    val start: Double,
    val duration: Double,
    /** Display row: -1 above the lanes (a new top lane), `rows` below them. */
    val row: Int,
    val snappedAt: Double?,
)

internal const val MIN_LENGTH = 1.0 / 60

/**
 * The timeline: the playhead fixed at the centre and the edit sliding under
 * it. A drag scrubs and flings, a pinch zooms, a tap selects the clip under
 * the finger (or nothing). A long press on a clip picks it up to move it,
 * onto another lane or past the top or bottom one for a new lane; the
 * selected clip's handles trim its ends. Moves and trims snap to the
 * playhead and to the other clips' edges, with a tick of haptics.
 *
 * The main track is tiled with filmstrips (`media.filmstrip`), sound with
 * its waveform (`media.peaks`); a cover square and the track's sound switch
 * sit before it, a "+" after it, and a transition button on every cut.
 */
@Composable
internal fun Timeline(
    context: TimelineContext,
) {
    val state = rememberTimelineDrawState(context.selected)
    val pxPerSecondState = state.pxPerSecond
    var pxPerSecond by pxPerSecondState
    val dragState = state.drag
    var drag by dragState
    val flingState = state.fling
    var fling by flingState
    val scrollState = state.scroll
    var scrollY by scrollState
    val scope = state.scope
    val haptics = state.haptics
    val measurer = state.measurer
    val selection = state.selection
    val knob = state.knob
    val glyphs = state.glyphs

    Canvas(
        context.modifier
            .fillMaxSize()
            .timelineInteraction(
                TimelineInteractionState(
                    pxPerSecond = pxPerSecondState,
                    drag = dragState,
                    fling = flingState,
                    scrollY = scrollState,
                    scope = scope,
                    haptics = haptics,
                    knob = knob,
                    currentView = rememberUpdatedState(context.view),
                    currentPosition = rememberUpdatedState(context.position),
                    currentSelected = rememberUpdatedState(context.selected),
                    scrub = rememberUpdatedState(context.onScrub),
                    choose = rememberUpdatedState(context.onSelect),
                    move = rememberUpdatedState(context.onMove),
                    trim = rememberUpdatedState(context.onTrim),
                    addMedia = rememberUpdatedState(context.onAddMedia),
                    transition = rememberUpdatedState(context.onTransition),
                    muteTrack = rememberUpdatedState(context.onMuteTrack),
                    openCover = rememberUpdatedState(context.onCover),
                ),
            ),
    ) {
        drawTimeline(context, state, pxPerSecond, drag, scrollY)
    }
}

private fun DrawScope.drawTimeline(
    context: TimelineContext,
    state: TimelineDrawState,
    pxPerSecond: Float,
    drag: DragPreview?,
    scrollY: Float?,
) {
    val scale = if (pxPerSecond <= 0f) 64.dp.toPx() else pxPerSecond
    val centre = size.width / 2f
    val layout = layoutLanes(context.view, size.height, scrollY, this)
    fun x(seconds: Double) = centre + ((seconds - context.position) * scale).toFloat()
    // Read so a strip or an outline landing draws the timeline again.
    context.media.version

    drawRect(ConcatColors.page)
    drawTimelineLanes(context, state, layout, drag, ::x)
    drawRuler(state.measurer, context.position, scale, centre)
    drawPlayhead(state.knob, centre)
}

private fun DrawScope.drawTimelineLanes(
    context: TimelineContext,
    state: TimelineDrawState,
    layout: LaneLayout,
    moving: DragPreview?,
    x: (Double) -> Float,
) {
    val view = context.view
    clipRect(top = RULER.toPx()) {
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
                    state.measurer, state.glyphs, context.media, context.projectPath, view, clip,
                    x(clip.start), x(clip.end), row, alpha = if (beingMoved) 0.3f else 1f,
                )
            }
        }
        layout.main?.let { main ->
            drawMainExtras(state.measurer, state.glyphs, context.media, view, main, context.selected, context.cover, x)
        }
        val chosen = view.clip(context.selected)
        if (chosen != null && moving?.clipId != chosen.id) {
            layout.rows.getOrNull(layout.rowOfTrack(chosen.trackId))?.let { row ->
                drawSelection(state.measurer, chosen, x(chosen.start), x(chosen.end), row, state.selection.value)
            }
        }
        if (moving != null) {
            drawMovingClip(MovingClipDrawing(layout, moving, view, state.measurer, state.glyphs, context.media, context.projectPath, x))
            moving.snappedAt?.let { at ->
                drawLine(ConcatColors.accent, Offset(x(at), RULER.toPx()), Offset(x(at), size.height), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

private fun DrawScope.drawPlayhead(knob: Animatable<Float, AnimationVector1D>, centre: Float) {
    val head = ConcatColors.accent
    val knobY = RULER.toPx() - 7.dp.toPx()
    drawLine(head, Offset(centre, knobY), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
    val radius = (5 + 2.5f * knob.value).dp.toPx()
    drawCircle(head, radius, Offset(centre, knobY))
    drawCircle(ConcatColors.page, radius * 0.35f, Offset(centre, knobY))
}
