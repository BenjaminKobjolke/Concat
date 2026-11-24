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

internal fun ruleStyle() = TextStyle(color = ConcatColors.fgDim, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum")

/** Time along the top: a labelled tick every so often and dots between. */
internal fun DrawScope.drawRuler(measurer: TextMeasurer, position: Double, scale: Float, centre: Float) {
    drawRect(ConcatColors.page, Offset.Zero, Size(size.width, RULER.toPx()))
    val step = when {
        scale > 90.dp.toPx() -> 1.0
        scale > 40.dp.toPx() -> 2.0
        scale > 15.dp.toPx() -> 5.0
        else -> 10.0
    }
    val first = floor((position - centre / scale) / step) * step
    val last = ceil((position + centre / scale) / step) * step
    var tick = max(0.0, first)
    val label = ruleStyle()
    val mid = 8.dp.toPx()
    while (tick <= last) {
        val at = centre + ((tick - position) * scale).toFloat()
        val seconds = tick.toLong()
        // Measured first and drawn only where it fits: drawText given a place
        // past the right edge asks for a negative width and throws.
        val text = measurer.measure("%02d:%02d".format(seconds / 60, seconds % 60), label)
        val x = at - text.size.width / 2f
        if (x >= 0f && x + text.size.width <= size.width && abs(at - centre) > text.size.width / 2f + 2.dp.toPx()) {
            drawText(text, topLeft = Offset(x, mid - text.size.height / 2f))
        }
        val half = centre + ((tick + step / 2 - position) * scale).toFloat()
        if (half in 0f..size.width) drawCircle(ConcatColors.fgDim, 1.2.dp.toPx(), Offset(half, mid))
        tick += step
    }
}

internal fun DrawScope.glyph(painter: VectorPainter, topLeft: Offset, size: Float, tint: Color) {
    translate(topLeft.x, topLeft.y) {
        with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
    }
}

internal fun kindGlyph(glyphs: TimelineGlyphs, clip: Clip): VectorPainter = when (clip.kind) {
    ClipKind.Text -> glyphs.text
    ClipKind.Shape -> glyphs.shapes
    ClipKind.Audio, ClipKind.SoundLayer -> glyphs.music
    ClipKind.Image -> glyphs.image
    ClipKind.Video -> glyphs.film
    ClipKind.Transition -> glyphs.transition
    ClipKind.Layer -> glyphs.filter
    ClipKind.Other -> glyphs.shapes
}

/** One clip on its lane: filmstrip, waveform or kind colour, with its badges. */
internal fun DrawScope.drawClip(
    measurer: TextMeasurer,
    glyphs: TimelineGlyphs,
    media: MediaCache,
    projectPath: String,
    view: ProjectView,
    clip: Clip,
    left: Float,
    right: Float,
    row: LaneRow,
    alpha: Float,
) {
    if (right < 0 || left > size.width) return
    val kind = KindColors.of(clip.kind)
    val inset = if (row.main) 0f else 1.dp.toPx()
    val box = Rect(left + 1f, row.top + inset, max(left + 3f, right - 1f), row.bottom - inset)
    val corner = CornerRadius((if (row.main) 6 else 5).dp.toPx())
    val shape = Path().apply { addRoundRect(RoundRect(box, corner)) }
    val file = view.mediaOf(clip)?.path
    clipPath(shape) {
        when {
            (clip.kind == ClipKind.Video || clip.kind == ClipKind.Image) && file != null -> {
                drawRect(kind.well.copy(alpha = alpha), box.topLeft, box.size)
                drawFilmstrip(media, file, clip, box, alpha)
                if (!row.main) {
                    // An overlay's strip is darkened under its label.
                    drawRect(Color.Black.copy(alpha = 0.25f * alpha), box.topLeft, box.size)
                }
            }
            clip.kind == ClipKind.Audio && file != null -> {
                drawRect(kind.well.copy(alpha = alpha), box.topLeft, box.size)
                val wave = Rect(box.left, box.top + 13.dp.toPx(), box.right, box.bottom - 1.dp.toPx())
                drawWaveform(media, projectPath, file, clip, wave, kind.color.copy(alpha = alpha))
            }
            else -> {
                drawRect(kind.color.copy(alpha = 0.92f * alpha), box.topLeft, box.size)
            }
        }
    }

    val width = box.width
    val glyphSize = 14.dp.toPx()
    var labelLeft = box.left + 6.dp.toPx()
    val clipLeftOnScreen = max(box.left, 0f)
    if (!row.main && width > 22.dp.toPx()) {
        // A sound's name sits small above its waveform; the rest are centred.
        val sound = clip.kind == ClipKind.Audio
        val size = if (sound) 10.dp.toPx() else glyphSize
        val lineY = if (sound) box.top + 7.dp.toPx() else row.centre
        labelLeft = clipLeftOnScreen + 6.dp.toPx()
        glyph(kindGlyph(glyphs, clip), Offset(labelLeft, lineY - size / 2f), size, Color.White.copy(alpha = alpha))
        labelLeft += size + 4.dp.toPx()
        val room = (box.right - labelLeft - 6.dp.toPx()).toInt()
        if (room > 12.dp.toPx()) {
            val laid = measurer.measure(
                clip.name.ifBlank { clip.kind.name },
                TextStyle(color = Color.White.copy(alpha = if (sound) 0.85f * alpha else alpha), fontSize = if (sound) 9.sp else 11.sp, fontFamily = Inter),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = room),
            )
            drawText(laid, topLeft = Offset(labelLeft, lineY - laid.size.height / 2f))
        }
    }

    // Badges along the top: speed when it is not 1x, muted, and the effects dot.
    var badgeX = box.left + 4.dp.toPx()
    val badgeY = box.top + 3.dp.toPx()
    if (row.main && abs(clip.speed - 1.0) > 1e-3 && width > 36.dp.toPx()) {
        badgeX += drawBadge(measurer, "%.1fx".format(clip.speed), Offset(max(badgeX, 4.dp.toPx()), badgeY)) + 4.dp.toPx()
    }
    if (row.main && clip.muted && width > 28.dp.toPx()) {
        val s = 12.dp.toPx()
        drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(max(badgeX, 4.dp.toPx()), badgeY), Size(s + 6.dp.toPx(), s + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
        glyph(glyphs.volumeOff, Offset(max(badgeX, 4.dp.toPx()) + 3.dp.toPx(), badgeY + 2.dp.toPx()), s, Color.White)
    }
    if (clip.videoEffects.isNotEmpty() && clip.kind != ClipKind.Transition && width > 18.dp.toPx()) {
        drawCircle(ConcatColors.accent, 3.dp.toPx(), Offset(box.right - 7.dp.toPx(), box.top + 7.dp.toPx()))
    }
}

/** A small dark pill with white text; its width. */
internal fun DrawScope.drawBadge(measurer: TextMeasurer, text: String, at: Offset, background: Color = Color.Black.copy(alpha = 0.6f)): Float {
    val laid = measurer.measure(text, TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = Inter, fontFeatureSettings = "tnum"))
    val pad = 4.dp.toPx()
    val w = laid.size.width + pad * 2
    drawRoundRect(background, at, Size(w, laid.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
    drawText(laid, topLeft = Offset(at.x + pad, at.y + 1.dp.toPx()))
    return w
}
