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

internal fun round2(value: Double) = (value * 100).roundToInt() / 100.0

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

/** Before the main track, its cover and its sound switch; after it, "+"; on each cut, a transition button. */
internal fun DrawScope.drawMainExtras(
    measurer: TextMeasurer,
    glyphs: TimelineGlyphs,
    media: MediaCache,
    view: ProjectView,
    main: LaneRow,
    selected: String?,
    x: (Double) -> Float,
) {
    val clips = view.clips.filter { it.trackId == main.trackId }.sortedBy { it.start }

    // The cover: a square of the first frame, labelled.
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
            if (first != null && file != null) drawFilmstrip(media, file, first.copy(duration = first.duration.coerceAtMost(0.5)), rect, 1f)
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
