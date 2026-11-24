// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipBounds
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A picture's placement as concat-project keeps it: scale, offsets as fractions of the frame, clockwise degrees. */
data class Placement(val scale: Double, val offsetX: Double, val offsetY: Double, val rotation: Double) {
    companion object {
        fun of(clip: Clip) = Placement(clip.scale, clip.offsetX, clip.offsetY, clip.rotation)
    }
}

/** A clip's box on the frame, in pixels of it: centre, size, and turn. */
internal class Box(val centre: Offset, val width: Float, val height: Float, val degrees: Float, val anchor: Offset = centre) {
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

internal data class ClipBoxRequest(
    val view: ProjectView,
    val clip: Clip,
    val placement: Placement,
    val frame: Size,
    val bounds: Map<String, ClipBounds> = emptyMap(),
)

private data class ClipBoxGeometry(val centre: Offset, val scale: Float, val turn: Float, val frame: Size)

/**
 * Where [clip] is drawn on a [frame]-sized picture at [placement]: a title's
 * lines at its size, a picture fitted to the frame (by its own aspect when
 * the probe found one).
 */
internal fun boxOf(request: ClipBoxRequest): Box {
    val clip = request.clip
    val placement = request.placement
    val frame = request.frame
    val centre = Offset(
        frame.width / 2f + (placement.offsetX * frame.width).toFloat(),
        frame.height / 2f + (placement.offsetY * frame.height).toFloat(),
    )
    val geometry = ClipBoxGeometry(centre, placement.scale.toFloat(), placement.rotation.toFloat(), frame)
    val painted = request.bounds[clip.id]?.takeIf { clip.kind == ClipKind.Text || clip.kind == ClipKind.Shape }
    if (painted != null) return paintedBox(painted, geometry)
    if (clip.kind == ClipKind.Text) {
        return textBox(clip, geometry)
    }
    if (clip.kind == ClipKind.Shape) {
        return shapeBox(geometry)
    }
    return mediaBox(request, geometry)
}

private fun paintedBox(painted: ClipBounds, geometry: ClipBoxGeometry): Box {
    val px = (painted.offsetX * geometry.frame.width).toFloat() * geometry.scale
    val py = (painted.offsetY * geometry.frame.height).toFloat() * geometry.scale
    val radians = geometry.turn * PI.toFloat() / 180f
    val shift = Offset(px * cos(radians) - py * sin(radians), px * sin(radians) + py * cos(radians))
    return Box(
        geometry.centre + shift,
        (painted.width * geometry.frame.width).toFloat() * geometry.scale,
        (painted.height * geometry.frame.height).toFloat() * geometry.scale,
        geometry.turn,
        anchor = geometry.centre,
    )
}

private fun textBox(clip: Clip, geometry: ClipBoxGeometry): Box {
    val style = clip.text
    val font = ((style?.optDouble("fontSize", 0.09) ?: 0.09).toFloat() * geometry.frame.height * geometry.scale).coerceAtLeast(6f)
    val lines = (style?.optString("content") ?: clip.name).lines().ifEmpty { listOf("") }
    val longest = lines.maxOf { it.length }.coerceAtLeast(1)
    val pad = font * 0.3f
    return Box(geometry.centre, longest * font * 0.5f + pad * 2, lines.size * font * 1.2f + pad * 2, geometry.turn)
}

private fun shapeBox(geometry: ClipBoxGeometry): Box {
    val side = 0.35f * geometry.frame.height * geometry.scale
    return Box(geometry.centre, side, side, geometry.turn)
}

private fun mediaBox(request: ClipBoxRequest, geometry: ClipBoxGeometry): Box {
    val media = request.view.mediaOf(request.clip)
    val aspect = if (media != null && media.width > 0 && media.height > 0) {
        media.width.toFloat() / media.height
    } else {
        geometry.frame.width / geometry.frame.height
    }
    val fitted = if (aspect > geometry.frame.width / geometry.frame.height) {
        Size(geometry.frame.width, geometry.frame.width / aspect)
    } else {
        Size(geometry.frame.height * aspect, geometry.frame.height)
    }
    return Box(geometry.centre, fitted.width * geometry.scale, fitted.height * geometry.scale, geometry.turn)
}

