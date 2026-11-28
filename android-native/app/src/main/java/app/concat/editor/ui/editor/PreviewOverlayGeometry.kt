// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A picture's placement as concat-doc keeps it: scale, offsets as fractions of the frame, clockwise degrees. */
data class Placement(val scale: Double, val offsetX: Double, val offsetY: Double, val rotation: Double) {
    companion object {
        fun of(clip: Clip) = Placement(clip.scale, clip.offsetX, clip.offsetY, clip.rotation)
    }
}

/**
 * A clip's box on the frame, in pixels of it: centre, size, and turn.
 * [anchor] is the clip's own position, which a gesture moves and turns it
 * about; it is the box's centre but for a title aligned to an edge, whose
 * block the painter lays beside its position.
 */
internal class OverlayBox(val centre: Offset, val width: Float, val height: Float, val degrees: Float, val anchor: Offset = centre) {
    fun contains(point: Offset, slack: Float = 0f): Boolean {
        val local = unrotate(point)
        return abs(local.x) <= width / 2 + slack && abs(local.y) <= height / 2 + slack
    }

    /** [point] in the box's own frame, its centre the origin. */
    fun unrotate(point: Offset): Offset {
        val d = point - centre
        val r = -degrees * PI.toFloat() / 180f
        return Offset(d.x * cos(r) - d.y * sin(r), d.x * sin(r) + d.y * cos(r))
    }

    fun corner(sx: Float, sy: Float): Offset {
        val r = degrees * PI.toFloat() / 180f
        val x = sx * width / 2
        val y = sy * height / 2
        return centre + Offset(x * cos(r) - y * sin(r), x * sin(r) + y * cos(r))
    }

    val corners get() = listOf(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f))
}

/**
 * Where [clip] is drawn on a [frame]-sized picture at [placement]: a title's
 * or shape's block as the engine painted it ([bounds], from `clips.bounds`),
 * a reading of its style until that comes, and a picture fitted to the
 * frame (by its own aspect when the probe found one).
 */
internal fun boxOf(view: ProjectView, clip: Clip, placement: Placement, frame: Size, bounds: Map<String, ClipBounds>): OverlayBox {
    val centre = Offset(
        frame.width / 2f + (placement.offsetX * frame.width).toFloat(),
        frame.height / 2f + (placement.offsetY * frame.height).toFloat(),
    )
    val scale = placement.scale.toFloat()
    val turn = placement.rotation.toFloat()
    val painted = bounds[clip.id]?.takeIf { clip.kind == ClipKind.Text || clip.kind == ClipKind.Shape }
    if (painted != null) {
        // The block's offset is canvas pixels: it scales and turns with the clip.
        val px = (painted.offsetX * frame.width).toFloat() * scale
        val py = (painted.offsetY * frame.height).toFloat() * scale
        val r = turn * PI.toFloat() / 180f
        val shift = Offset(px * cos(r) - py * sin(r), px * sin(r) + py * cos(r))
        return OverlayBox(
            centre + shift,
            (painted.width * frame.width).toFloat() * scale,
            (painted.height * frame.height).toFloat() * scale,
            turn,
            anchor = centre,
        )
    }
    if (clip.kind == ClipKind.Text) {
        val style = clip.text
        val font = ((style?.optDouble("fontSize", 0.09) ?: 0.09).toFloat() * frame.height * scale).coerceAtLeast(6f)
        val lines = (style?.optString("content") ?: clip.name).lines().ifEmpty { listOf("") }
        val longest = lines.maxOf { it.length }.coerceAtLeast(1)
        val pad = font * 0.3f
        return OverlayBox(centre, longest * font * 0.5f + pad * 2, lines.size * font * 1.2f + pad * 2, turn)
    }
    if (clip.kind == ClipKind.Shape) {
        // Until it is painted: a figure's default size, a third of the frame's height.
        val side = 0.35f * frame.height * scale
        return OverlayBox(centre, side, side, turn)
    }
    val media = view.mediaOf(clip)
    val aspect = if (media != null && media.width > 0 && media.height > 0) media.width.toFloat() / media.height else frame.width / frame.height
    val fitted = if (aspect > frame.width / frame.height) Size(frame.width, frame.width / aspect) else Size(frame.height * aspect, frame.height)
    return OverlayBox(centre, fitted.width * scale, fitted.height * scale, turn)
}

