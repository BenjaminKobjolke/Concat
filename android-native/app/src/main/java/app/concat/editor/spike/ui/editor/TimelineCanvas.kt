// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.media.MediaCache
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.Dimens

internal data class TimelineFrameState(
    val view: ProjectView,
    val projectPath: String,
    val media: MediaCache,
    val position: Double,
    val selected: String?,
    val pxPerSecond: Float,
    val scrollY: Float?,
    val drag: DragPreview?,
    val selection: Float,
    val knob: Float,
    val measurer: TextMeasurer,
    val glyphs: TimelineGlyphs,
)

internal fun DrawScope.drawTimelineFrame(state: TimelineFrameState) {
    val view = state.view
    val projectPath = state.projectPath
    val media = state.media
    val position = state.position
    val selected = state.selected
    val pxPerSecond = state.pxPerSecond
    val scrollY = state.scrollY
    val drag = state.drag
    val selection = state.selection
    val knob = state.knob
    val measurer = state.measurer
    val glyphs = state.glyphs
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
                drawSelection(measurer, chosen, x(chosen.start), x(chosen.end), row, selection)
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
    val radius = (5 + 2.5f * knob).dp.toPx()
    drawCircle(head, radius, Offset(centre, knobY))
    drawCircle(ConcatColors.page, radius * 0.35f, Offset(centre, knobY))

}
