// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objectsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** A clock and a placeholder picture: the clip under the playhead, named, on its colour. */
internal class FakePreview(private val bridge: FakeEngineBridge, private val path: String) : PreviewBridge {
    @Volatile private var view: JSONObject? = bridge.snapshot(path)
    @Volatile private var surface: Surface? = null
    @Volatile private var playing = false
    @Volatile private var anchor = 0.0
    @Volatile private var anchorAt = 0L
    private var painter: Thread? = null

    override fun attachSurface(surface: Surface, width: Int, height: Int, mode: String) {
        detachSurface()
        this.surface = surface
        painter = thread(name = "fake-preview") {
            while (this.surface === surface) {
                paint(surface)
                Thread.sleep(33)
            }
        }
    }

    override fun detachSurface() {
        surface = null
        painter?.join(500)
        painter = null
    }

    override fun refresh() {
        view = bridge.snapshot(path)
        if (position() > duration()) seek(duration())
    }

    override fun play() {
        if (position() >= duration() - 0.01) anchor = 0.0 else anchor = position()
        anchorAt = SystemClock.elapsedRealtimeNanos()
        playing = duration() > 0
    }

    override fun pause() {
        anchor = position()
        playing = false
    }

    override fun seek(seconds: Double) {
        anchor = seconds.coerceIn(0.0, max(0.0, duration()))
        anchorAt = SystemClock.elapsedRealtimeNanos()
    }

    override fun position(): Double {
        if (!playing) return anchor
        val at = anchor + (SystemClock.elapsedRealtimeNanos() - anchorAt) / 1e9
        if (at >= duration()) {
            playing = false
            anchor = duration()
        }
        return min(at, duration())
    }

    override fun duration(): Double {
        val clips = view?.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)?.optJSONArray("clips") ?: return 0.0
        return clips.objectsList().maxOfOrNull { it.optDouble("start") + it.optDouble("duration") } ?: 0.0
    }

    override fun isPlaying() = playing && position() < duration()

    override fun release() = detachSurface()

    private fun paint(surface: Surface) {
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            FakeFrame.paint(canvas, view, position(), showClock = true)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * The in-memory engine's picture at an instant, on any canvas: the
 * preview's Surface and `preview.frame`'s PNG. Pictures are gradients with
 * their names; titles are drawn where their transform puts them.
 */
internal object FakeFrame {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun drawBackground(canvas: Canvas, timeline: JSONObject?): RectF {
        val video = timeline?.optJSONObject("video")
        val aspect = (video?.optDouble("width", 1080.0) ?: 1080.0) / (video?.optDouble("height", 1920.0) ?: 1920.0)
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val frame = if (width / height > aspect) {
            val frameWidth = (height * aspect).toFloat()
            RectF((width - frameWidth) / 2, 0f, (width + frameWidth) / 2, height)
        } else {
            val frameHeight = (width / aspect).toFloat()
            RectF(0f, (height - frameHeight) / 2, width, (height + frameHeight) / 2)
        }
        val background = timeline?.optJSONObject("background")
        fill.shader = null
        fill.color = when (background?.optString("kind")) {
            "color" -> try {
                Color.parseColor(background.optString("color"))
            } catch (_: Exception) {
                Color.BLACK
            }
            "blur" -> Color.rgb(40, 44, 52)
            else -> Color.BLACK
        }
        canvas.drawRect(frame, fill)
        return frame
    }

    @Synchronized
    fun paint(canvas: Canvas, view: JSONObject?, at: Double, showClock: Boolean) {
        canvas.drawColor(Color.rgb(14, 14, 16))
        val timeline = view?.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)
        val frame = drawBackground(canvas, timeline)
        val clips = timeline?.optJSONArray("clips")?.objectsList().orEmpty()
            .filter { at >= it.optDouble("start") && at < it.optDouble("start") + it.optDouble("duration") }
        val tracks = timeline?.optJSONArray("tracks")?.objectsList().orEmpty().map { it.optString("id") }
        canvas.save()
        canvas.clipRect(frame)
        // Lanes are stored bottom first: lower lanes are drawn first, under the rest.
        for (clip in clips.sortedBy { tracks.indexOf(it.optString("trackId")) }) {
            val kind = clip.optString("kind")
            if (kind != "video" && kind != "image" && kind != "text") continue
            val scale = clip.optDouble("scale", 1.0).toFloat()
            val cx = frame.centerX() + clip.optDouble("offsetX", 0.0).toFloat() * frame.width()
            val cy = frame.centerY() + clip.optDouble("offsetY", 0.0).toFloat() * frame.height()
            val opacity = (clip.optDouble("opacity", 1.0) * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(clip.optDouble("rotation", 0.0).toFloat(), cx, cy)
            if (kind == "text") {
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
            } else {
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
}

/** Stand-ins for the engine's filmstrips and sound outlines: gradient tiles with their frame number, and a made-up wave. */
internal object FakePictures {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Synchronized
    fun filmstrip(request: JSONObject): JSONObject {
        val file = request.getString("file")
        val count = request.optInt("count", 8).coerceIn(1, 60)
        val height = request.optInt("height", 96).coerceIn(16, 240)
        val from = request.optDouble("from", 0.0).takeIf { !it.isNaN() } ?: 0.0
        val to = request.optDouble("to", from + 5.0).takeIf { !it.isNaN() } ?: (from + 5.0)
        val tile = height
        val bitmap = android.graphics.Bitmap.createBitmap(tile * count, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = (File(file).name.hashCode() and 0xff) * 360f / 256f
        for (i in 0 until count) {
            val t = from + (i + 0.5) * (to - from) / count
            val hue = (base + t.toFloat() * 9f) % 360f
            val left = (i * tile).toFloat()
            paint.shader = android.graphics.LinearGradient(
                left, 0f, left + tile, height.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f)),
                Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.7f, 0.32f)),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(left, 0f, left + tile, height.toFloat(), paint)
            paint.shader = null
            // A horizon and a sun, so the tiles read as pictures of something.
            paint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRect(left, height * 0.68f, left + tile, height.toFloat(), paint)
            paint.color = Color.argb(110, 255, 255, 255)
            canvas.drawCircle(left + tile * (0.3f + 0.4f * ((t.toFloat() * 0.13f) % 1f)), height * 0.36f, tile * 0.1f, paint)
            paint.color = Color.argb(80, 0, 0, 0)
            canvas.drawRect(left + tile - 1f, 0f, left + tile.toFloat(), height.toFloat(), paint)
            label.textSize = height * 0.16f
            canvas.drawText("${(t * 30).toInt()}", left + height * 0.08f, height * 0.92f, label)
        }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return JSONObject().put("width", bitmap.width).put("height", bitmap.height)
            .put("png", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    fun peaks(request: JSONObject, duration: Double): JSONObject {
        val perSecond = request.optDouble("perSecond", 20.0).takeIf { !it.isNaN() && it > 0 } ?: 20.0
        val n = max(1, (duration * perSecond).toInt())
        val seed = request.getString("file").hashCode()
        val random = java.util.Random(seed.toLong())
        val low = JSONArray()
        val high = JSONArray()
        for (i in 0 until n) {
            val t = i / perSecond
            val phrase = 0.5 + 0.5 * kotlin.math.sin(t * 1.7 + seed % 7)
            val beat = 0.6 + 0.4 * kotlin.math.abs(kotlin.math.sin(t * Math.PI * 2))
            val level = (0.12 + 0.75 * phrase * beat * (0.7 + 0.3 * random.nextDouble())).coerceIn(0.0, 1.0)
            high.put(level)
            low.put(-level * (0.85 + 0.1 * random.nextDouble()))
        }
        return JSONObject().put("min", low).put("max", high).put("perSecond", perSecond)
    }
}
