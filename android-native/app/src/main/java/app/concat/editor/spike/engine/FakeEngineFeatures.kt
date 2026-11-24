// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal fun FakeEngineBridge.projectCover(request: JSONObject): JSONObject {
    val session = session(request)
    val cover = session.project.optJSONObject("cover")
    if (cover?.optString("kind") == "picture") {
        val source = BitmapFactory.decodeFile(cover.optString("path"))
            ?: throw FakeEngineBridge.Refusal("failed", "${cover.optString("path")} would not open")
        val width = request.optInt("width", 270).coerceIn(8, 2048)
        val height = request.optInt("height", 480).coerceIn(8, 2048)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val shownWidth = source.width * scale
        val shownHeight = source.height * scale
        val target = RectF((width - shownWidth) / 2, (height - shownHeight) / 2, (width + shownWidth) / 2, (height + shownHeight) / 2)
        Canvas(bitmap).drawBitmap(source, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        val output = request.getString("output")
        File(output).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return JSONObject().put("path", output).put("width", width).put("height", height)
    }
    val length = session.clips.featureObjectsList().maxOfOrNull { it.optDouble("start") + it.optDouble("duration") } ?: 0.0
    val time = if (cover?.optString("kind") == "frame") cover.optDouble("time", 0.0).coerceAtMost(length) else (length * 0.25).coerceAtMost(2.0)
    return FakeFrameRequests.previewFrame(JSONObject(request.toString()).put("time", time), sessions, FakeEngineBridge.DOCUMENT)
}

internal fun FakeEngineBridge.fakeBounds(session: FakeEngineBridge.Session): JSONArray {
    val video = session.timeline.optJSONObject("video") ?: JSONObject()
    val aspect = video.optDouble("width", 1080.0) / video.optDouble("height", 1920.0).coerceAtLeast(1.0)
    return JSONArray().apply {
        for (clip in session.clips.featureObjectsList()) {
            val (width, height) = when (clip.optString("kind")) {
                "text" -> {
                    val style = clip.optJSONObject("text") ?: JSONObject()
                    val em = style.optDouble("fontSize", 0.09)
                    val lines = style.optString("content").lines().ifEmpty { listOf("") }
                    val longest = lines.maxOf { it.length }.coerceAtLeast(1)
                    (longest * em * 0.6 + em * 0.6) / aspect to (lines.size * em * 1.2 + em * 0.5)
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

internal fun FakeEngineBridge.freeze(session: FakeEngineBridge.Session, request: JSONObject): String? {
    val clip = commands.clip(session, request.getString("clipId")) ?: throw FakeEngineBridge.Refusal("invalid", "no such clip")
    if (clip.optString("kind") !in setOf("video", "image")) throw FakeEngineBridge.Refusal("invalid", "only a picture holds")
    val time = request.getDouble("time")
    val hold = request.optDouble("duration", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0
    val start = clip.getDouble("start")
    val end = start + clip.getDouble("duration")
    if (time <= start + FakeEngineBridge.MIN || time >= end - FakeEngineBridge.MIN) {
        throw FakeEngineBridge.Refusal("invalid", "$time s is not inside the clip")
    }
    val trackId = clip.getString("trackId")
    for (other in session.clips.featureObjectsList()) {
        if (other.getString("trackId") == trackId && other.getString("id") != clip.getString("id") && other.getDouble("start") >= time - 1e-6) {
            other.put("start", max(0.0, other.getDouble("start") + hold))
        }
    }
    val tail = JSONObject(clip.toString()).put("id", mint("c")).put("start", time + hold)
        .put("duration", end - time)
        .put("sourceStart", clip.getDouble("sourceStart") + (time - start) * clip.getDouble("speed"))
    val still = JSONObject(clip.toString()).put("id", mint("c")).put("start", time).put("duration", hold)
        .put("name", "Freeze").put("speed", 1.0)
    clip.put("duration", time - start)
    session.clips.put(still)
    session.clips.put(tail)
    return still.getString("id")
}

internal fun FakeEngineBridge.replace(session: FakeEngineBridge.Session, request: JSONObject): String? {
    val clip = commands.clip(session, request.getString("clipId")) ?: throw FakeEngineBridge.Refusal("invalid", "no such clip")
    val file = request.getString("file")
    commands.import(session, file)
    val media = session.media.featureObjectsList().firstOrNull { it.getString("path") == file }
        ?: throw FakeEngineBridge.Refusal("failed", "$file would not import")
    clip.put("mediaId", media.getString("id")).put("name", media.optString("name"))
    return null
}

internal fun FakeEngineBridge.reverse(request: JSONObject): JSONObject {
    val session = session(request)
    val clip = commands.clip(session, request.getString("clipId")) ?: throw FakeEngineBridge.Refusal("invalid", "no such clip")
    if (clip.optString("kind") !in setOf("video", "audio")) throw FakeEngineBridge.Refusal("invalid", "nothing to reverse")
    val job = "j${nextJob++}"
    val path = session.path
    val clipId = clip.getString("id")
    val sourceStart = clip.getDouble("sourceStart")
    val covered = clip.getDouble("duration") * clip.getDouble("speed")
    thread(name = "fake-reverse-$job") {
        fun send(event: JSONObject) = _events.tryEmit(event.put("job", job).put("path", path).put("clipId", clipId).toString())
        for (step in 1..20) {
            if (synchronized(this) { cancelled.remove(job) }) {
                send(JSONObject().put("event", "reverse.failed").put("error", JSONObject().put("code", "cancelled").put("message", "Cancelled.")))
                return@thread
            }
            send(JSONObject().put("event", "reverse.progress").put("fraction", step / 20.0))
            Thread.sleep(80)
        }
        send(JSONObject().put("event", "reverse.done").put("file", File(path, "cache/reversed-$clipId.mp4").absolutePath)
            .put("sourceStart", sourceStart).put("covered", covered))
    }
    return JSONObject().put("job", job).put("path", path).put("output", "")
}

private fun JSONArray.featureObjectsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
