// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.media.MediaCache
import app.concat.editor.media.StripKey
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.secondsLabel
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Dimens
import app.concat.editor.ui.theme.Inter
import app.concat.editor.ui.theme.KindColors
import app.concat.editor.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A tappable mark on the timeline that is not a clip. */
internal sealed interface TimelineButton {
    data object Add : TimelineButton
    data object Cover : TimelineButton
    data class Cut(val nextClipId: String) : TimelineButton
    data class Mute(val trackId: String, val mute: Boolean) : TimelineButton
}

internal data class TimelineHitPoint(val position: Offset, val x: (Double) -> Float)

internal fun buttonAt(view: ProjectView, layout: LaneLayout, point: TimelineHitPoint, density: androidx.compose.ui.unit.Density): TimelineButton? = with(density) {
    val at = point.position
    val x = point.x
    val main = layout.main ?: return null
    val reach = 10.dp.toPx()
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }
    transitionAt(clips, main, at, x, reach)?.let { return it }
    if (at.y < main.top - reach || at.y > main.bottom + reach) return null
    val end = clips.maxOfOrNull { it.end } ?: 0.0
    val plusLeft = x(end) + 12.dp.toPx()
    if (at.x in (plusLeft - reach)..(plusLeft + PLUS.toPx() + reach)) return TimelineButton.Add
    val coverRight = x(0.0) - 12.dp.toPx()
    val coverLeft = coverRight - main.height
    if (at.x in coverLeft..coverRight) return TimelineButton.Cover
    val muteRight = coverLeft - 10.dp.toPx()
    if (at.x in (muteRight - 40.dp.toPx())..muteRight) {
        val videos = clips.filter { it.kind == ClipKind.Video }
        if (videos.isNotEmpty()) return TimelineButton.Mute(main.trackId, mute = videos.any { !it.muted })
    }
    null
}

private fun Density.transitionAt(clips: List<Clip>, main: LaneRow, at: Offset, x: (Double) -> Float, reach: Float): TimelineButton.Cut? {
    if (abs(at.y - main.centre) >= CUT_BUTTON.toPx() / 2 + reach) return null
    for (i in 0 until clips.size - 1) {
        val first = clips[i]
        val next = clips[i + 1]
        if (abs(first.end - next.start) > 1e-3) continue
        if (abs(at.x - x(next.start)) < CUT_BUTTON.toPx() / 2 + reach / 2) return TimelineButton.Cut(next.id)
    }
    return null
}

/**
 * How far an edge may go, in signed seconds: never shorter than a frame
 * at 60, never onto the neighbour on the lane, and for footage never past
 * what the file holds.
 */
internal fun trimRange(view: ProjectView, clip: Clip, edge: String): Pair<Double, Double> {
    val lane = view.clips.filter { it.trackId == clip.trackId && it.id != clip.id }
    val footage = clip.kind == ClipKind.Video || clip.kind == ClipKind.Audio
    val mediaLength = view.mediaOf(clip)?.duration
    return if (edge == "start") {
        val previousEnd = lane.filter { it.end <= clip.start + 1e-6 }.maxOfOrNull { it.end } ?: 0.0
        var low = previousEnd - clip.start
        if (footage) low = max(low, -clip.sourceStart / clip.speed.coerceAtLeast(1e-6))
        low to (clip.duration - MIN_LENGTH)
    } else {
        val nextStart = lane.filter { it.start >= clip.end - 1e-6 }.minOfOrNull { it.start }
        var high = (nextStart ?: Double.MAX_VALUE) - clip.end
        if (footage && mediaLength != null) {
            high = min(high, (mediaLength - clip.sourceStart) / clip.speed.coerceAtLeast(1e-6) - clip.duration)
        }
        -(clip.duration - MIN_LENGTH) to max(0.0, high)
    }
}
