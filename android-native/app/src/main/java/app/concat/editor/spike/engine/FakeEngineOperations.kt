// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

private data class FakeModelDefinition(
    val id: String,
    val label: String,
    val blurb: String,
    val family: String,
    val englishOnly: Boolean,
    val sizeBytes: Long,
)

internal fun FakeEngineBridge.setVideo(session: Session, video: JSONObject): String? {
        val width = video.optInt("width")
        val height = video.optInt("height")
        if (width <= 0 || height <= 0) throw Refusal("invalid", "a frame needs a size")
        val current = session.timeline.optJSONObject("video") ?: JSONObject()
        current.put("width", width).put("height", height)
            .put("rateNum", video.optLong("rateNum", current.optLong("rateNum", 30)))
            .put("rateDen", video.optLong("rateDen", current.optLong("rateDen", 1)))
        session.timeline.put("video", current)
        session.view.getJSONObject("settings").put("width", width).put("height", height)
        return null
    }

    /** A file's length, from any open project's bin, or by asking the file. */
internal fun FakeEngineBridge.mediaDuration(file: String): Double {
        for (session in sessions.values) {
            session.media.objectsList().firstOrNull { it.optString("path") == file }?.let { item ->
                if (!item.isNull("duration")) return item.optDouble("duration", 5.0)
            }
        }
        return try {
            MediaMetadataRetriever().run {
                setDataSource(file)
                val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                release()
                (ms ?: 5000L) / 1000.0
            }
        } catch (_: Exception) {
            5.0
        }
    }

    /** The placeholder picture at an instant: what the preview paints, as a PNG. */
internal fun FakeEngineBridge.downloadedModels(): MutableSet<String> = try {
        JSONArray(modelsFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    } catch (_: Exception) {
        mutableSetOf()
    }

internal fun FakeEngineBridge.setDownloaded(id: String, downloaded: Boolean) {
        val set = downloadedModels()
        if (downloaded) set += id else set -= id
        modelsFile.writeText(JSONArray(set.toList()).toString())
    }

internal fun FakeEngineBridge.modelsList(): JSONObject {
        val have = downloadedModels()
        fun model(definition: FakeModelDefinition) =
            JSONObject().put("id", definition.id).put("label", definition.label).put("blurb", definition.blurb).put("family", definition.family)
                .put("englishOnly", definition.englishOnly).put("sizeBytes", definition.sizeBytes).put("downloaded", definition.id in have)
        return JSONObject()
            .put("transcribers", JSONArray()
                .put(model(FakeModelDefinition("tiny.en", "Tiny (English)", "Fastest; misses more words.", "", true, 75_000_000)))
                .put(model(FakeModelDefinition("base", "Base", "Any language; a good balance.", "", false, 142_000_000)))
                .put(model(FakeModelDefinition("small", "Small", "Hears best; slowest on a phone.", "", false, 466_000_000))))
            .put("speech", JSONArray()
                .put(model(FakeModelDefinition("system", "Phone voice", "The voice the phone has; nothing to download.", "system", false, 0))
                    .put("downloaded", true))
                .put(model(FakeModelDefinition("chatterbox", "Chatterbox", "Natural voices; a large download.", "chatterbox", false, 520_000_000))))
            .put("voices", JSONArray()
                .put(JSONObject().put("id", 0).put("name", "Default").put("family", "system"))
                .put(JSONObject().put("id", 1).put("name", "Ava").put("family", "chatterbox"))
                .put(JSONObject().put("id", 2).put("name", "Noah").put("family", "chatterbox"))
                .put(JSONObject().put("id", 3).put("name", "Mia").put("family", "chatterbox")))
    }

internal fun FakeEngineBridge.modelsDownload(kind: String, id: String): JSONObject {
        if (kind != "transcriber" && kind != "speech") throw Refusal("invalid", "$kind is not a model kind")
        val size = modelsList().let { all ->
            (all.getJSONArray("transcribers").objectsList() + all.getJSONArray("speech").objectsList())
                .firstOrNull { it.getString("id") == id }?.getLong("sizeBytes")
        } ?: throw Refusal("notFound", "no model $id")
        val job = "j${nextJob++}"
        thread(name = "fake-model-$job") {
            fun send(event: JSONObject) = _events.tryEmit(event.put("job", job).put("id", id).toString())
            val total = max(1L, size)
            for (step in 1..40) {
                if (synchronized(this) { cancelled.remove(job) }) {
                    send(JSONObject().put("event", "models.failed").put("error", JSONObject().put("code", "cancelled").put("message", "Cancelled.")))
                    return@thread
                }
                send(JSONObject().put("event", "models.progress").put("received", total * step / 40).put("total", total).put("unpacking", step == 40))
                Thread.sleep(60)
            }
            synchronized(this) { setDownloaded(id, true) }
            send(JSONObject().put("event", "models.done"))
        }
        return JSONObject().put("job", job).put("path", "").put("output", "")
    }

internal fun FakeEngineBridge.transcribe(request: JSONObject): JSONObject {
        val session = session(request)
        val clipId = request.getString("clipId")
        val clip = commands.clip(session, clipId) ?: throw Refusal("notFound", "no clip $clipId")
        val model = request.getString("model")
        if (model !in downloadedModels()) throw Refusal("refused", "$model is not downloaded")
        val job = "j${nextJob++}"
        val path = session.path
        val heard = clip.getDouble("duration") * clip.getDouble("speed")
        val from = clip.getDouble("sourceStart")
        thread(name = "fake-captions-$job") {
            fun send(event: JSONObject) = _events.tryEmit(event.put("job", job).put("path", path).toString())
            val stages = listOf("reading" to 6, "loading" to 6, "hearing" to 30)
            for ((stage, steps) in stages) {
                for (step in 1..steps) {
                    if (synchronized(this) { cancelled.remove(job) }) {
                        send(JSONObject().put("event", "captions.failed").put("error", JSONObject().put("code", "cancelled").put("message", "Cancelled.")))
                        return@thread
                    }
                    send(JSONObject().put("event", "captions.progress").put("stage", stage)
                        .put("fraction", if (stage == "hearing") step.toDouble() / steps else 0.0))
                    Thread.sleep(70)
                }
            }
            val segments = JSONArray()
            var at = 0.0
            var line = 0
            while (at < heard - 0.2) {
                val end = min(heard, at + 2.0)
                segments.put(JSONObject().put("start", from + at).put("end", from + end).put("text", FakeEngineBridge.FAKE_WORDS[line % FakeEngineBridge.FAKE_WORDS.size]))
                at = end
                line++
            }
            send(JSONObject().put("event", "captions.done").put("clipId", clipId).put("segments", segments))
        }
        return JSONObject().put("job", job).put("path", path).put("output", "")
    }

internal fun FakeEngineBridge.placeCaptions(session: Session, request: JSONObject): String? {
        val clip = commands.clip(session, request.getString("clipId")) ?: throw Refusal("notFound", "that clip is gone")
        val size = when (request.optString("size", "medium")) { "small" -> 0.045; "large" -> 0.09; else -> 0.065 }
        val offsetY = when (request.optString("placement", "bottom")) { "top" -> -0.35; "middle" -> 0.0; else -> 0.35 }
        val speed = clip.getDouble("speed")
        val sourceStart = clip.getDouble("sourceStart")
        var last: String? = null
        val segments = request.getJSONArray("segments").objectsList()
        if (segments.isEmpty()) return null
        val first = clip.getDouble("start") + (segments.minOf { it.getDouble("start") } - sourceStart) / speed
        val lastEnd = clip.getDouble("start") + (segments.maxOf { it.getDouble("end") } - sourceStart) / speed
        val track = commands.firstFreeAbove(session, first, lastEnd)
        for (segment in segments) {
            val text = segment.optString("text").trim()
            if (text.isEmpty()) continue
            val start = clip.getDouble("start") + (segment.getDouble("start") - sourceStart) / speed
            val length = max(FakeEngineBridge.MIN, (segment.getDouble("end") - segment.getDouble("start")) / speed)
            val style = JSONObject().put("content", text).put("fontSize", size).put("color", "#ffffff")
            val id = mint("c")
            session.clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "").put("name", text)
                .put("kind", "text").put("start", max(0.0, start)).put("duration", length).put("sourceStart", 0.0)
                .put("volume", 1.0).put("speed", 1.0).put("text", style).put("offsetY", offsetY))
            last = id
        }
        session.tracks.objectsList().firstOrNull { it.optString("id") == track }?.put(
            "captions",
            JSONObject().put("style", JSONObject().put("fontSize", size).put("color", "#ffffff")).put("offsetY", offsetY),
        )
        return last
    }

internal fun FakeEngineBridge.speak(request: JSONObject): JSONObject {
        val session = session(request)
        val text = request.getString("text").trim()
        if (text.isEmpty()) throw Refusal("invalid", "there is nothing to say")
        val model = request.getString("model")
        if (model != "system" && model !in downloadedModels()) throw Refusal("refused", "$model is not downloaded")
        val speed = request.optDouble("speed", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0
        val job = "j${nextJob++}"
        val path = session.path
        val seconds = max(1.0, text.split(Regex("\\s+")).size * 0.38 / speed)
        val file = File(path, "speech-${System.currentTimeMillis()}.wav")
        thread(name = "fake-speech-$job") {
            fun send(event: JSONObject) = _events.tryEmit(event.put("job", job).put("path", path).toString())
            for (step in 1..25) {
                if (synchronized(this) { cancelled.remove(job) }) {
                    send(JSONObject().put("event", "speech.failed").put("error", JSONObject().put("code", "cancelled").put("message", "Cancelled.")))
                    return@thread
                }
                send(JSONObject().put("event", "speech.progress").put("stage", if (step < 5) "loading" else "reading").put("fraction", step / 25.0))
                Thread.sleep(60)
            }
            try {
                writeTone(file, seconds)
                send(JSONObject().put("event", "speech.done").put("file", file.absolutePath).put("duration", seconds))
            } catch (failure: Exception) {
                send(JSONObject().put("event", "speech.failed").put("error", JSONObject().put("code", "failed").put("message", failure.message ?: "write failed")))
            }
        }
        return JSONObject().put("job", job).put("path", path).put("output", file.absolutePath)
    }

    /** A quiet hum, as a 16-bit mono WAV, standing in for a voice. */
internal fun FakeEngineBridge.writeTone(file: File, seconds: Double) {
        val rate = 16_000
        val samples = (seconds * rate).toInt()
        val data = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val envelope = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 3 * t)
            data.putShort((kotlin.math.sin(2 * Math.PI * 180 * t) * 3000 * envelope).toInt().toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(data.array())
    }

internal fun FakeEngineBridge.export(request: JSONObject): JSONObject {
        val session = session(request)
        val output = request.getString("output")
        val job = "j${nextJob++}"
        val source = session.media.objectsList().firstOrNull { it.getString("kind") == "video" }?.getString("path")
        val settings = session.view.getJSONObject("settings")
        val width = request.optInt("width", settings.getInt("width"))
        val height = request.optInt("height", settings.getInt("height"))
        val total = max(1, (session.clips.objectsList().maxOfOrNull { it.getDouble("start") + it.getDouble("duration") } ?: 1.0).times(30).toInt())
        val path = session.path
        thread(name = "fake-export-$job") {
            fun send(event: JSONObject) = _events.tryEmit(event.put("job", job).put("path", path).toString())
            var frame = 0
            while (frame < total) {
                if (synchronized(this) { cancelled.remove(job) }) {
                    send(JSONObject().put("event", "export.failed").put("error", JSONObject().put("code", "cancelled").put("message", "The export was cancelled.")))
                    return@thread
                }
                frame = min(total, frame + max(1, total / 60))
                send(JSONObject().put("event", "export.progress").put("frame", frame).put("total", total).put("stage", "video"))
                Thread.sleep(50)
            }
            if (source == null) {
                send(JSONObject().put("event", "export.failed").put("error", JSONObject().put("code", "failed")
                    .put("message", "The in-memory engine renders nothing; it copies the first video, and there is none.")))
                return@thread
            }
            try {
                File(output).parentFile?.mkdirs()
                File(source).copyTo(File(output), overwrite = true)
                send(JSONObject().put("event", "export.done").put("output", output).put("width", width).put("height", height))
            } catch (failure: Exception) {
                send(JSONObject().put("event", "export.failed").put("error", JSONObject().put("code", "failed").put("message", failure.message ?: "copy failed")))
            }
        }
        return JSONObject().put("job", job).put("path", path).put("output", output)
    }

