// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import android.graphics.Canvas
import java.io.File
import org.json.JSONObject

internal class FakePreviewRequests(private val store: FakeProjectStore) {    /** The placeholder picture at an instant: what the preview paints, as a PNG. */
    private data class FrameDimensions(val width: Int, val height: Int)
    /** `project.cover`: the picture set as the cover, cut to fill; else the frame it names, or one early in. */
    fun projectCover(request: JSONObject): JSONObject {
        val session = store.session(request)
        val cover = session.project.optJSONObject("cover")
        if (cover?.optString("kind") == "picture") {
            val source = android.graphics.BitmapFactory.decodeFile(cover.optString("path"))
                ?: throw Refusal("failed", "${cover.optString("path")} would not open")
            val width = request.optInt("width", 270).coerceIn(8, 2048)
            val height = request.optInt("height", 480).coerceIn(8, 2048)
            val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val shownW = source.width * scale
            val shownH = source.height * scale
            val target = android.graphics.RectF((width - shownW) / 2, (height - shownH) / 2, (width + shownW) / 2, (height + shownH) / 2)
            Canvas(bitmap).drawBitmap(source, null, target, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val output = request.getString("output")
            File(output).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return JSONObject().put("path", output).put("width", width).put("height", height)
        }
        val length = session.clips.objectsList().maxOfOrNull { it.getDouble("start") + it.getDouble("duration") } ?: 0.0
        val time = if (cover?.optString("kind") == "frame") cover.optDouble("time", 0.0).coerceAtMost(length) else (length * 0.25).coerceAtMost(2.0)
        return previewFrame(JSONObject(request.toString()).put("time", time))
    }

    fun previewFrame(request: JSONObject): JSONObject {
        val path = request.getString("path")
        val view = store.snapshot(path) ?: store.readView(path)
            ?: throw Refusal("notOpen", "$path is not a project")
        val video = view.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)?.optJSONObject("video")
        val dimensions = frameDimensions(request, video)
        val bitmap = android.graphics.Bitmap.createBitmap(dimensions.width, dimensions.height, android.graphics.Bitmap.Config.ARGB_8888)
        FakeFrame.paint(Canvas(bitmap), view, request.optDouble("time", 0.0), showClock = false)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return frameResult(request, bitmap, bytes)
    }

    private fun frameDimensions(request: JSONObject, video: JSONObject?): FrameDimensions {
        val frameWidth = video?.optInt("width", 1080) ?: 1080
        val frameHeight = video?.optInt("height", 1920) ?: 1920
        var width = request.optInt("width", 0)
        var height = request.optInt("height", 0)
        if (width <= 0 && height <= 0) { width = frameWidth / 4; height = frameHeight / 4 }
        if (width <= 0) width = (height * frameWidth.toDouble() / frameHeight).toInt()
        if (height <= 0) height = (width * frameHeight.toDouble() / frameWidth).toInt()
        return FrameDimensions(width.coerceIn(8, 2048), height.coerceIn(8, 2048))
    }

    private fun frameResult(request: JSONObject, bitmap: android.graphics.Bitmap, bytes: ByteArray): JSONObject {
        val output = request.optString("output").takeIf { !request.isNull("output") && it.isNotEmpty() }
        return if (output != null) {
            File(output).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            JSONObject().put("output", output).put("width", bitmap.width).put("height", bitmap.height)
        } else {
            JSONObject().put("width", bitmap.width).put("height", bitmap.height)
                .put("png", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
    }


}
