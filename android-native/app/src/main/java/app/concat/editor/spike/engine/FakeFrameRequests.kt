// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.graphics.Canvas
import java.io.File
import org.json.JSONObject

internal object FakeFrameRequests {
    fun previewFrame(request: JSONObject, sessions: Map<String, FakeEngineBridge.Session>, document: String): JSONObject {
        val view = projectView(request.getString("path"), sessions, document)
        val (frameWidth, frameHeight) = frameSize(view)
        val (width, height) = requestedSize(request, frameWidth, frameHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        FakeFrame.paint(Canvas(bitmap), view, request.optDouble("time", 0.0), showClock = false)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return writeFrame(request, bitmap, bytes)
    }

    private fun projectView(path: String, sessions: Map<String, FakeEngineBridge.Session>, document: String): JSONObject =
        sessions[path]?.view ?: File(path, document).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            ?: throw FakeEngineBridge.Refusal("notOpen", "$path is not a project")

    private fun frameSize(view: JSONObject): Pair<Int, Int> {
        val video = view.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)?.optJSONObject("video")
        return (video?.optInt("width", 1080) ?: 1080) to (video?.optInt("height", 1920) ?: 1920)
    }

    private fun requestedSize(request: JSONObject, frameWidth: Int, frameHeight: Int): Pair<Int, Int> {
        var width = request.optInt("width", 0)
        var height = request.optInt("height", 0)
        if (width <= 0 && height <= 0) { width = frameWidth / 4; height = frameHeight / 4 }
        if (width <= 0) width = (height * frameWidth.toDouble() / frameHeight).toInt()
        if (height <= 0) height = (width * frameHeight.toDouble() / frameWidth).toInt()
        return width.coerceIn(8, 2048) to height.coerceIn(8, 2048)
    }

    private fun writeFrame(request: JSONObject, bitmap: android.graphics.Bitmap, bytes: ByteArray): JSONObject {
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
