// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.graphics.Canvas
import java.io.File
import org.json.JSONObject

internal object FakeFrameRequests {
    fun previewFrame(request: JSONObject, sessions: Map<String, FakeEngineBridge.Session>, document: String): JSONObject {
        val path = request.getString("path")
        val view = sessions[path]?.view ?: File(path, document).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            ?: throw FakeEngineBridge.Refusal("notOpen", "$path is not a project")
        val video = view.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)?.optJSONObject("video")
        val frameWidth = video?.optInt("width", 1080) ?: 1080
        val frameHeight = video?.optInt("height", 1920) ?: 1920
        var width = request.optInt("width", 0)
        var height = request.optInt("height", 0)
        if (width <= 0 && height <= 0) { width = frameWidth / 4; height = frameHeight / 4 }
        if (width <= 0) width = (height * frameWidth.toDouble() / frameHeight).toInt()
        if (height <= 0) height = (width * frameHeight.toDouble() / frameWidth).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(width.coerceIn(8, 2048), height.coerceIn(8, 2048), android.graphics.Bitmap.Config.ARGB_8888)
        FakeFrame.paint(Canvas(bitmap), view, request.optDouble("time", 0.0), showClock = false)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
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
