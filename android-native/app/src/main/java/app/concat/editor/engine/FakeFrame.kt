// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import org.json.JSONArray

/**
 * The in-memory engine's picture at an instant, on any canvas: the
 * preview's Surface and `preview.frame`'s PNG. Pictures are gradients with
 * their names; titles are drawn where their transform puts them.
 */
internal object FakeFrame {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private data class ClipPosition(val scale: Float, val centerX: Float, val centerY: Float)
    private data class ClipDrawing(
        val canvas: Canvas,
        val clip: JSONObject,
        val frame: RectF,
        val position: ClipPosition,
        val opacity: Int,
    )

    /** Bounds match the approximation used by the in-memory painter. */
    fun bounds(session: Session): JSONArray {
        val video = session.timeline.optJSONObject("video") ?: JSONObject()
        val aspect = video.optDouble("width", 1080.0) / video.optDouble("height", 1920.0).coerceAtLeast(1.0)
        return JSONArray().apply {
            for (clip in session.clips.objectsList()) {
                val (width, height) = when (clip.optString("kind")) {
                    "text" -> {
                        val style = clip.optJSONObject("text") ?: JSONObject()
                        val em = style.optDouble("fontSize", 0.09)
                        val lines = style.optString("content").lines().ifEmpty { listOf("") }
                        val longest = lines.maxOf { it.length }.coerceAtLeast(1)
                        (longest * em * 0.6 + em * 0.6) / aspect to lines.size * em * 1.2 + em * 0.5
                    }
                    "shape" -> {
                        val side = clip.optJSONObject("shape")?.optDouble("width", 0.35) ?: 0.35
                        side / aspect to side
                    }
                    else -> continue
                }
                put(JSONObject().put("clipId", clip.getString("id")).put("width", width).put("height", height)
                    .put("offsetX", 0.0).put("offsetY", 0.0))
            }
        }
    }

    @Synchronized
    fun paint(canvas: Canvas, view: JSONObject?, at: Double, showClock: Boolean) {
        canvas.drawColor(Color.rgb(14, 14, 16))
        val timeline = view?.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)
        val video = timeline?.optJSONObject("video")
        val aspect = (video?.optDouble("width", 1080.0) ?: 1080.0) / (video?.optDouble("height", 1920.0) ?: 1920.0)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val frame = if (w / h > aspect) {
            val fw = (h * aspect).toFloat(); RectF((w - fw) / 2, 0f, (w + fw) / 2, h)
        } else {
            val fh = (w / aspect).toFloat(); RectF(0f, (h - fh) / 2, w, (h + fh) / 2)
        }
        fill.shader = null
        fill.color = backgroundColor(timeline?.optJSONObject("background"))
        canvas.drawRect(frame, fill)
        val clips = visibleClips(timeline, at)
        val tracks = timeline?.optJSONArray("tracks")?.objectsList().orEmpty().map { it.optString("id") }
        canvas.save()
        canvas.clipRect(frame)
        // Lanes are stored bottom first: lower lanes are drawn first, under the rest.
        for (clip in clips.sortedBy { tracks.indexOf(it.optString("trackId")) }) {
            if (!isPaintable(clip)) continue
            val kind = clip.optString("kind")
            val scale = clip.optDouble("scale", 1.0).toFloat()
            val cx = frame.centerX() + clip.optDouble("offsetX", 0.0).toFloat() * frame.width()
            val cy = frame.centerY() + clip.optDouble("offsetY", 0.0).toFloat() * frame.height()
            val opacity = (clip.optDouble("opacity", 1.0) * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(clip.optDouble("rotation", 0.0).toFloat(), cx, cy)
            val drawing = ClipDrawing(canvas, clip, frame, ClipPosition(scale, cx, cy), opacity)
            if (kind == "text") {
                paintText(drawing)
            } else {
                paintMedia(drawing)
            }
            canvas.restore()
        }
        canvas.restore()
        if (showClock) {
            text.textSize = frame.height() * 0.022f
            text.color = Color.argb(120, 255, 255, 255)
            text.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText("in-memory engine  %.2f s".format(at), frame.centerX(), frame.top + text.textSize * 2, text)
        }
    }

    private fun paintText(drawing: ClipDrawing) {
        val (canvas, clip, frame, position, opacity) = drawing
        val scale = position.scale
        val cx = position.centerX
        val cy = position.centerY
        val style = clip.optJSONObject("text") ?: JSONObject()
        text.textSize = (style.optDouble("fontSize", 0.09) * frame.height() * scale).toFloat().coerceAtLeast(4f)
        text.typeface = if (style.optDouble("fontWeight", 700.0) >= 600) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        text.color = try {
            Color.parseColor(style.optString("color", "#ffffff"))
        } catch (_: Exception) {
            Color.WHITE
        }
        text.alpha = opacity
        text.setShadowLayer(text.textSize * 0.08f, 0f, text.textSize * 0.04f, Color.argb(160, 0, 0, 0))
        val lines = style.optString("content").lines()
        val lineHeight = text.textSize * 1.2f
        var y = cy - lineHeight * (lines.size - 1) / 2f + text.textSize * 0.35f
        for (line in lines) {
            canvas.drawText(line, cx, y, text)
            y += lineHeight
        }
        text.clearShadowLayer()
    }

    private fun paintMedia(drawing: ClipDrawing) {
        val (canvas, clip, frame, position, opacity) = drawing
        val scale = position.scale
        val cx = position.centerX
        val cy = position.centerY
        val hue = (clip.optString("mediaId").hashCode() and 0xff) * 360f / 256f
        val bw = frame.width() * scale
        val bh = frame.height() * scale
        val box = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        fill.shader = android.graphics.LinearGradient(
            box.left, box.top, box.right, box.bottom,
            Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.6f)),
            Color.HSVToColor(floatArrayOf((hue + 50f) % 360f, 0.6f, 0.28f)),
            android.graphics.Shader.TileMode.CLAMP,
        )
        fill.alpha = opacity
        canvas.drawRect(box, fill)
        fill.shader = null
        fill.alpha = 255
        text.textSize = frame.height() * 0.028f
        text.color = Color.argb(150, 255, 255, 255)
        text.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(clip.optString("name"), cx, box.bottom - text.textSize * 2, text)
    }

    private fun isPaintable(clip: JSONObject): Boolean =
        clip.optString("kind") in setOf("video", "image", "text")

    private fun backgroundColor(background: JSONObject?): Int = when (background?.optString("kind")) {
        "color" -> try {
            Color.parseColor(background.optString("color"))
        } catch (_: Exception) {
            Color.BLACK
        }
        "blur" -> Color.rgb(40, 44, 52)
        else -> Color.BLACK
    }

    private fun visibleClips(timeline: JSONObject?, at: Double): List<JSONObject> =
        timeline?.optJSONArray("clips")?.objectsList().orEmpty()
            .filter { at >= it.optDouble("start") && at < it.optDouble("start") + it.optDouble("duration") }
}
