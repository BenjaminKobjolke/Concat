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

/** A small dark pill with white text; its width. */
internal fun DrawScope.drawBadge(measurer: TextMeasurer, text: String, at: Offset, background: Color = Color.Black.copy(alpha = 0.6f)): Float {
    val laid = measurer.measure(text, TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum"))
    val pad = 4.dp.toPx()
    val w = laid.size.width + pad * 2
    drawRoundRect(background, at, Size(w, laid.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
    drawText(laid, topLeft = Offset(at.x + pad, at.y + 1.dp.toPx()))
    return w
}

/**
 * Square tiles of the clip's own frames, left to right at the lane's height,
 * each sampled from the strip where it falls in time.
 */
internal fun DrawScope.drawFilmstrip(media: MediaCache, file: String, clip: Clip, box: Rect, alpha: Float) {
    val tile = box.height
    val tilesWanted = ceil(box.width / tile).toInt().coerceAtLeast(1)
    val count = MediaCache.bucket(tilesWanted).coerceAtMost(60)
    val from = if (clip.kind == ClipKind.Image) 0.0 else clip.sourceStart
    val to = if (clip.kind == ClipKind.Image) 0.0 else clip.sourceStart + clip.duration * clip.speed
    val key = StripKey(file, round2(from), round2(to), count, tile.roundToInt().coerceIn(16, 240))
    val strip: ImageBitmap = media.strip(key) ?: return
    val stripCount = max(1, (strip.width.toFloat() / max(1, strip.height) + 0.5f).toInt()).let { guess ->
        // The strip is `count` tiles wide; a nearest stand-in may hold another count.
        if (strip.width % count == 0 && strip.width / count > 0) count else guess
    }
    val srcTileWidth = strip.width / stripCount
    val srcSide = min(srcTileWidth, strip.height)
    val first = max(0, floor((0f - box.left) / tile).toInt())
    val last = min(tilesWanted - 1, ceil((size.width - box.left) / tile).toInt())
    for (i in first..last) {
        val dstLeft = box.left + i * tile
        val centreAt = ((i * tile + tile / 2f) / box.width).coerceIn(0f, 0.9999f)
        val j = (centreAt * stripCount).toInt().coerceIn(0, stripCount - 1)
        val srcX = j * srcTileWidth + (srcTileWidth - srcSide) / 2
        val srcY = (strip.height - srcSide) / 2
        drawImage(
            strip,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcSide, srcSide),
            dstOffset = IntOffset(dstLeft.roundToInt(), box.top.roundToInt()),
            dstSize = IntSize(ceil(tile).toInt() + 1, box.height.roundToInt()),
            alpha = alpha,
        )
    }
}

private fun round2(value: Double) = (value * 100).roundToInt() / 100.0

/** The clip's stretch of its file's loudness outline, mirrored about the lane's middle. */
internal fun DrawScope.drawWaveform(media: MediaCache, projectPath: String, file: String, clip: Clip, box: Rect, colour: Color) {
    val peaks = media.peaks(projectPath, file) ?: return
    if (peaks.max.isEmpty()) return
    val mid = box.center.y
    val half = box.height * 0.42f
    val step = 2.dp.toPx()
    val scale = box.width / clip.duration.coerceAtLeast(1e-6)
    var x = max(box.left, 0f)
    val end = min(box.right, size.width)
    while (x < end) {
        val t = clip.sourceStart + (x - box.left) / scale * clip.speed
        val i = (t * peaks.perSecond).toInt()
        if (i in peaks.max.indices) {
            val top = mid - peaks.max[i].coerceIn(0f, 1f) * half
            val bottom = mid - peaks.min.getOrElse(i) { -peaks.max[i] }.coerceIn(-1f, 0f) * half
            drawLine(colour, Offset(x, top), Offset(x, max(bottom, top + 1f)), strokeWidth = step * 0.6f)
        }
        x += step
    }
}

/** The selected clip's frame: 3dp lime, white handles outside each end, and its length. */
internal fun DrawScope.drawSelection(measurer: TextMeasurer, clip: Clip, left: Float, right: Float, row: LaneRow, alpha: Float) {
    if (right < -HANDLE.toPx() || left > size.width + HANDLE.toPx()) return
    val stroke = 3.dp.toPx()
    val top = row.top - stroke / 2f
    val bottom = row.bottom + stroke / 2f
    val lime = ConcatColors.accent.copy(alpha = alpha)
    drawRoundRect(
        lime,
        Offset(left, top),
        Size(max(2f, right - left), bottom - top),
        CornerRadius(2.dp.toPx()),
        style = Stroke(width = stroke),
    )
    val handle = HANDLE.toPx()
    val radius = CornerRadius(6.dp.toPx())
    val white = Color.White.copy(alpha = alpha)
    val grip = ConcatColors.page.copy(alpha = alpha)
    val height = bottom - top + stroke
    val y = top - stroke / 2f
    // Left handle: rounded on the outside.
    val leftHandle = Path().apply {
        addRoundRect(RoundRect(Rect(left - handle, y, left, y + height), topLeft = radius, bottomLeft = radius))
    }
    drawPath(leftHandle, white)
    val rightHandle = Path().apply {
        addRoundRect(RoundRect(Rect(right, y, right + handle, y + height), topRight = radius, bottomRight = radius))
    }
    drawPath(rightHandle, white)
    val gripHalf = min(8.dp.toPx(), height * 0.22f)
    val mid = row.centre
    drawLine(grip, Offset(left - handle / 2f, mid - gripHalf), Offset(left - handle / 2f, mid + gripHalf), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(grip, Offset(right + handle / 2f, mid - gripHalf), Offset(right + handle / 2f, mid + gripHalf), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)

    // The duration badge, at the bottom left inside the clip (on screen).
    if (right - left > 34.dp.toPx()) {
        val laid = measurer.measure(secondsLabel(clip.duration), TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum"))
        val pad = 4.dp.toPx()
        val bx = max(left + 4.dp.toPx(), 4.dp.toPx()).coerceAtMost(right - laid.size.width - pad * 2 - 4.dp.toPx())
        val by = row.bottom - laid.size.height - 5.dp.toPx()
        if (row.height >= 40.dp.toPx()) {
            drawRoundRect(Color.Black.copy(alpha = 0.65f * alpha), Offset(bx, by), Size(laid.size.width + pad * 2, laid.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(laid, topLeft = Offset(bx + pad, by + 1.dp.toPx()), alpha = alpha)
        }
    }
}

