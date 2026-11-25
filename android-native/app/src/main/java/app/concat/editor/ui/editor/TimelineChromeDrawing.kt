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

/** Before the main track, its cover and its sound switch; after it, "+"; on each cut, a transition button. */
internal fun DrawScope.drawMainExtras(
    measurer: TextMeasurer,
    glyphs: TimelineGlyphs,
    media: MediaCache,
    view: ProjectView,
    main: LaneRow,
    selected: String?,
    cover: ImageBitmap?,
    x: (Double) -> Float,
) {
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }

    // The cover: the one the project names, or a square of the first frame; labelled.
    val coverRight = x(0.0) - 12.dp.toPx()
    val side = main.height
    val coverLeft = coverRight - side
    if (coverRight > 0) {
        val rect = Rect(coverLeft, main.top, coverRight, main.bottom)
        val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(6.dp.toPx()))) }
        clipPath(shape) {
            drawRect(ConcatColors.raised, rect.topLeft, rect.size)
            val first = clips.firstOrNull { it.kind == ClipKind.Video || it.kind == ClipKind.Image }
            val file = first?.let { view.mediaOf(it)?.path }
            if (cover != null) {
                drawImage(
                    cover,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                )
            } else if (first != null && file != null) {
                drawFilmstrip(media, file, first.copy(duration = first.duration.coerceAtMost(0.5)), rect, 1f)
            }
            drawRect(Color.Black.copy(alpha = 0.72f), Offset(rect.left, rect.bottom - 16.dp.toPx()), Size(rect.width, 16.dp.toPx()))
        }
        drawRoundRect(ConcatColors.lineStrong, rect.topLeft, rect.size, CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
        val laid = measurer.measure("Cover", TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter))
        drawText(laid, topLeft = Offset(rect.center.x - laid.size.width / 2f, rect.bottom - 8.dp.toPx() - laid.size.height / 2f))

        // The main track's own sound: on or off.
        val videos = clips.filter { it.kind == ClipKind.Video }
        if (videos.isNotEmpty()) {
            val muted = videos.all { it.muted }
            val r = 15.dp.toPx()
            val cx = coverLeft - 10.dp.toPx() - r
            if (cx + r > 0) {
                drawCircle(ConcatColors.raised, r, Offset(cx, main.centre - 6.dp.toPx()))
                val g = 16.dp.toPx()
                glyph(if (muted) glyphs.volumeOff else glyphs.volume, Offset(cx - g / 2, main.centre - 6.dp.toPx() - g / 2), g, if (muted) ConcatColors.fgMuted else ConcatColors.fg)
                val label = measurer.measure(if (muted) "Muted" else "Sound", TextStyle(color = ConcatColors.fgMuted, fontSize = 8.sp, fontFamily = Inter))
                drawText(label, topLeft = Offset(cx - label.size.width / 2f, main.centre + r - 4.dp.toPx()))
            }
        }

    }

    // A transition button on every cut between two clips that touch.
    val transitions = view.clips.filter { it.kind == ClipKind.Transition }
    for (i in 0 until clips.size - 1) {
        val a = clips[i]
        val b = clips[i + 1]
        if (abs(a.end - b.start) > 1e-3) continue
        if (a.id == selected || b.id == selected) continue
        val cx = x(b.start)
        if (cx < -20 || cx > size.width + 20) continue
        val s = CUT_BUTTON.toPx()
        val has = transitions.any { it.start <= b.start + 1e-3 && it.end >= b.start - 1e-3 }
        drawRoundRect(
            if (has) ConcatColors.accent else Color.White,
            Offset(cx - s / 2, main.centre - s / 2),
            Size(s, s),
            CornerRadius(5.dp.toPx()),
        )
        drawRoundRect(ConcatColors.page.copy(alpha = 0.5f), Offset(cx - s / 2, main.centre - s / 2), Size(s, s), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
        val g = 14.dp.toPx()
        glyph(glyphs.transition, Offset(cx - g / 2, main.centre - g / 2), g, ConcatColors.onAccent)
    }

    // "+" after the last clip: more media.
    val end = clips.maxOfOrNull { it.end } ?: 0.0
    val plusLeft = x(end) + 12.dp.toPx()
    if (plusLeft < size.width) {
        val s = PLUS.toPx()
        drawRoundRect(Color.White, Offset(plusLeft, main.centre - s / 2), Size(s, s), CornerRadius(8.dp.toPx()))
        val g = 22.dp.toPx()
        glyph(glyphs.plus, Offset(plusLeft + (s - g) / 2, main.centre - g / 2), g, ConcatColors.page)
        if (clips.isEmpty()) {
            val hint = measurer.measure("Add media", TextStyle(color = ConcatColors.fgMuted, fontSize = 13.sp, fontFamily = Inter))
            val hx = plusLeft + s + 10.dp.toPx()
            if (hx + hint.size.width <= size.width) drawText(hint, topLeft = Offset(hx, main.centre - hint.size.height / 2f))
        }
    }
}

internal data class MovingClipDrawing(
    val layout: LaneLayout,
    val moving: DragPreview,
    val view: ProjectView,
    val measurer: TextMeasurer,
    val glyphs: TimelineGlyphs,
    val media: MediaCache,
    val projectPath: String,
    val x: (Double) -> Float,
)

internal fun DrawScope.drawMovingClip(drawing: MovingClipDrawing) {
    val moving = drawing.moving
    val clip = drawing.view.clip(moving.clipId) ?: return
    val layout = drawing.layout
    val gap = layout.gap
    val row = when {
        moving.row < 0 -> LaneRow(-1, "", layout.top - gap - Dimens.overlayLane.toPx(), Dimens.overlayLane.toPx(), false)
        moving.row >= layout.rows.size -> LaneRow(-1, "", layout.bottom + gap, Dimens.overlayLane.toPx(), false)
        else -> layout.rows[moving.row]
    }
    if (moving.row < 0 || moving.row >= layout.rows.size) {
        val y = if (moving.row < 0) layout.top - gap / 2f else layout.bottom + gap / 2f
        drawLine(
            ConcatColors.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
    }
    val left = drawing.x(moving.start)
    val right = drawing.x(moving.start + moving.duration)
    drawClip(drawing.measurer, drawing.glyphs, drawing.media, drawing.projectPath, drawing.view, clip, left, right, row, alpha = 0.92f)
    drawSelection(drawing.measurer, clip.copy(start = moving.start, duration = moving.duration), left, right, row, 1f)
}
