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

internal data class TimelinePresentation(
    val view: ProjectView,
    val projectPath: String,
    val media: MediaCache,
    val position: Double,
    val selected: String?,
)

internal data class TimelineCallbacks(
    val onScrub: (Double) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (clipId: String, start: Double, target: LaneTarget) -> Unit,
    val onTrim: (clipId: String, edge: String, delta: Double) -> Unit,
    val onAddMedia: () -> Unit,
    val onTransition: (nextClipId: String) -> Unit,
    val onMuteTrack: (trackId: String, muted: Boolean) -> Unit,
)

/** What a finger is doing to a clip right now, drawn over the edit until it lifts. */
@Composable
internal fun Timeline(
    presentation: TimelinePresentation,
    actions: TimelineCallbacks,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
) {
    val view = presentation.view
    val projectPath = presentation.projectPath
    val media = presentation.media
    val position = presentation.position
    val selected = presentation.selected
    val onScrub = actions.onScrub
    val onSelect = actions.onSelect
    val onMove = actions.onMove
    val onTrim = actions.onTrim
    val onAddMedia = actions.onAddMedia
    val onTransition = actions.onTransition
    val onMuteTrack = actions.onMuteTrack
    val pxPerSecondState = remember { mutableFloatStateOf(0f) }
    var pxPerSecond by pxPerSecondState
    val dragState = remember { mutableStateOf<DragPreview?>(null) }
    var drag by dragState
    val flingState = remember { mutableStateOf<Job?>(null) }
    var fling by flingState
    /** Vertical scroll when the lanes overflow; null follows the main track. */
    val scrollYState = remember { mutableStateOf<Float?>(null) }
    var scrollY by scrollYState
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val currentView = rememberUpdatedState(view)
    val currentPosition = rememberUpdatedState(position)
    val currentSelected = rememberUpdatedState(selected)
    val scrub = rememberUpdatedState(onScrub)
    val choose = rememberUpdatedState(onSelect)
    val move = rememberUpdatedState(onMove)
    val trim = rememberUpdatedState(onTrim)
    val addMedia = rememberUpdatedState(onAddMedia)
    val transition = rememberUpdatedState(onTransition)
    val muteTrack = rememberUpdatedState(onMuteTrack)

    // The selection fades in; the playhead's knob springs while a finger is down.
    val selection = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected != null) {
            selection.snapTo(0f)
            selection.animateTo(1f, Motion.tweenShort())
        }
    }
    val knob = remember { Animatable(0f) }

    val gesture = TimelineGestureContext(
        pxPerSecondState, dragState, flingState, scrollYState, scope, haptics, knob,
        currentView, currentPosition, currentSelected, scrub, choose, move, trim, addMedia, transition, muteTrack,
    )
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
            .timelinePointerInput(gesture),
    ) {
        drawTimelineFrame(
            TimelineFrameState(
                view = view,
                projectPath = projectPath,
                media = media,
                position = position,
                selected = selected,
                pxPerSecond = pxPerSecond,
                scrollY = scrollY,
                drag = drag,
                selection = selection.value,
                knob = knob.value,
                measurer = measurer,
                glyphs = glyphs,
            ),
        )
    }
}

internal val RULER = Dimens.ruler
internal val SOUND_LANE = 40.dp
internal val HANDLE = 14.dp
internal val LANE_GAP = 6.dp

internal enum class Outcome { Tap, Pan, LongPress }

/** The lanes laid out in [height], following the main track unless scrolled. */
