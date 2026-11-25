// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.File
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

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
