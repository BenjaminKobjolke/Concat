// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

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

internal data class DragPreview(
    val clipId: String,
    val start: Double,
    val duration: Double,
    /** Display row: -1 above the lanes (a new top lane), `rows` below them. */
    val row: Int,
    val snappedAt: Double?,
)

/** One lane on screen: which track, where, how tall, and whether it is the main one. */
internal class LaneRow(val trackIndex: Int, val trackId: String, val top: Float, val height: Float, val main: Boolean) {
    val bottom get() = top + height
    val centre get() = top + height / 2f
}

/**
 * The lanes, top first, over the document's tracks, which are stored
 * bottom first. The main track is the lowest lane holding pictures (the
 * first when none does): 56dp and filmstripped; the lanes above it are
 * overlays at 32dp; an empty lane is a thin 20dp slot to drop into.
 */
internal class LaneLayout(val rows: List<LaneRow>, val gap: Float) {
    val top get() = rows.firstOrNull()?.top ?: 0f
    val bottom get() = rows.lastOrNull()?.bottom ?: 0f
    val main get() = rows.firstOrNull { it.main }

    /** The row under [y]: -1 above them all, `rows.size` below. */
    fun rowAt(y: Float): Int {
        if (rows.isEmpty()) return 0
        if (y < top - gap) return -1
        rows.forEachIndexed { i, row -> if (y < row.bottom + gap / 2f) return i }
        return if (y > bottom + gap * 2 + 12f) rows.size else rows.size - 1
    }

    fun rowOfTrack(trackId: String) = rows.indexOfFirst { it.trackId == trackId }
}

private const val MIN_LENGTH = 1.0 / 60

/** The main track: the lowest lane with a picture on it, else the lowest lane. */
internal fun mainTrackIndex(view: ProjectView): Int {
    val pictures = view.clips.filter { it.kind == ClipKind.Video || it.kind == ClipKind.Image }.map { it.trackId }.toSet()
    return view.tracks.indexOfFirst { it.id in pictures }.takeIf { it >= 0 } ?: 0
}

/** The glyphs the timeline paints, as painters it can draw in a DrawScope. */
internal class TimelineGlyphs(
    val text: VectorPainter,
    val music: VectorPainter,
    val image: VectorPainter,
    val film: VectorPainter,
    val transition: VectorPainter,
    val sparkle: VectorPainter,
    val plus: VectorPainter,
    val volume: VectorPainter,
    val volumeOff: VectorPainter,
    val filter: VectorPainter,
    val shapes: VectorPainter,
)

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
internal fun layoutLanes(view: ProjectView, height: Float, scrollY: Float?, density: androidx.compose.ui.unit.Density): LaneLayout =
    with(density) {
        val mainIndex = mainTrackIndex(view)
        val gap = LANE_GAP.toPx()
        val heights = view.tracks.indices.reversed().map { index ->
            val track = view.tracks[index]
            val used = view.clips.any { it.trackId == track.id }
            val sound = view.clips.any { it.trackId == track.id && it.kind == ClipKind.Audio }
            index to when {
                index == mainIndex -> Dimens.mainTrack.toPx()
                sound -> SOUND_LANE.toPx()
                used -> Dimens.overlayLane.toPx()
                else -> Dimens.emptyLane.toPx()
            }
        }
        val total = heights.sumOf { it.second.toDouble() }.toFloat() + gap * max(0, heights.size - 1)
        val available = height - RULER.toPx() - 16.dp.toPx()
        var y = RULER.toPx() + 10.dp.toPx()
        if (total <= available) {
            // Room for all: the block sits a little above the middle, as CapCut's does.
            y += (available - total) * 0.3f
        } else {
            val probe = LaneLayout(rowsFrom(view, heights, y, gap, mainIndex), gap)
            y -= scrollY ?: autoScroll(probe, height, density)
        }
        LaneLayout(rowsFrom(view, heights, y, gap, mainIndex), gap)
    }

internal fun rowsFrom(view: ProjectView, heights: List<Pair<Int, Float>>, startY: Float, gap: Float, mainIndex: Int): List<LaneRow> {
    var y = startY
    return heights.map { (index, h) ->
        LaneRow(index, view.tracks[index].id, y, h, index == mainIndex).also { y += h + gap }
    }
}

/** How far to scroll overflowing lanes so the main track sits about halfway down. */
internal fun autoScroll(layout: LaneLayout, height: Float, density: androidx.compose.ui.unit.Density): Float = with(density) {
    val main = layout.main ?: return 0f
    val overflow = max(0f, layout.bottom - layout.top - (height - RULER.toPx() - 16.dp.toPx()))
    (main.centre - height * 0.55f).coerceIn(0f, overflow)
}

/** A tappable mark on the timeline that is not a clip. */
private sealed interface TimelineButton {
    data object Add : TimelineButton
    data object Cover : TimelineButton
    data class Cut(val nextClipId: String) : TimelineButton
    data class Mute(val trackId: String, val mute: Boolean) : TimelineButton
}

internal fun buttonAt(
    view: ProjectView,
    layout: LaneLayout,
    at: Offset,
    x: (Double) -> Float,
    density: androidx.compose.ui.unit.Density,
): TimelineButton? = with(density) {
    val main = layout.main ?: return null
    val reach = 10.dp.toPx()
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }
    // The transition buttons sit on the cuts, half over each clip.
    if (abs(at.y - main.centre) < CUT_BUTTON.toPx() / 2 + reach) {
        for (i in 0 until clips.size - 1) {
            val a = clips[i]
            val b = clips[i + 1]
            if (abs(a.end - b.start) > 1e-3) continue
            if (abs(at.x - x(b.start)) < CUT_BUTTON.toPx() / 2 + reach / 2) return TimelineButton.Cut(b.id)
        }
    }
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

private val CUT_BUTTON = 22.dp
private val PLUS = 40.dp

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

