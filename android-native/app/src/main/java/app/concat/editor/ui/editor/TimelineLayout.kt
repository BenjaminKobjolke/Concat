// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.theme.Dimens
import kotlin.math.max

/** One lane on screen: which track, where, how tall, and whether it is the main one. */
internal class LaneRow(val trackIndex: Int, val trackId: String, val top: Float, val height: Float, val main: Boolean) {
    val bottom get() = top + height
    val centre get() = top + height / 2f
}

/** The lanes in display order, with helpers for mapping vertical gestures to tracks. */
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

internal val RULER = Dimens.ruler
private val SOUND_LANE = 40.dp
private val LANE_GAP = 6.dp
internal val CUT_BUTTON = 22.dp
internal val PLUS = 40.dp
internal val HANDLE = 14.dp

/** The main track: the lowest lane with a picture on it, else the lowest lane. */
fun mainTrackIndex(view: ProjectView): Int {
    val pictures = view.clips.filter { it.kind == ClipKind.Video || it.kind == ClipKind.Image }.map { it.trackId }.toSet()
    return view.tracks.indexOfFirst { it.id in pictures }.takeIf { it >= 0 } ?: 0
}

/** The lanes laid out in [height], following the main track unless scrolled. */
internal fun layoutLanes(view: ProjectView, height: Float, scrollY: Float?, density: Density): LaneLayout =
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
            y += (available - total) * 0.3f
        } else {
            val probe = LaneLayout(rowsFrom(view, heights, LaneSpacing(y, gap), mainIndex), gap)
            y -= scrollY ?: autoScroll(probe, height, density)
        }
        LaneLayout(rowsFrom(view, heights, LaneSpacing(y, gap), mainIndex), gap)
    }

private data class LaneSpacing(val startY: Float, val gap: Float)

private fun rowsFrom(view: ProjectView, heights: List<Pair<Int, Float>>, spacing: LaneSpacing, mainIndex: Int): List<LaneRow> {
    val startY = spacing.startY
    val gap = spacing.gap
    var y = startY
    return heights.map { (index, h) ->
        LaneRow(index, view.tracks[index].id, y, h, index == mainIndex).also { y += h + gap }
    }
}

/** How far to scroll overflowing lanes so the main track sits about halfway down. */
internal fun autoScroll(layout: LaneLayout, height: Float, density: Density): Float = with(density) {
    val main = layout.main ?: return 0f
    val overflow = max(0f, layout.bottom - layout.top - (height - RULER.toPx() - 16.dp.toPx()))
    (main.centre - height * 0.55f).coerceIn(0f, overflow)
}
