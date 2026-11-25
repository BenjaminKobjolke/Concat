// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import java.io.File
import android.media.MediaMetadataRetriever
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject

private val FAKE_WORDS = listOf(
    "This is what the in-memory engine heard.",
    "Every line can be corrected before it is placed.",
    "The real engine hears the clip's own sound.",
    "Captions land over the clip as titles.",
)

internal data class FakeMediaProbe(val duration: Double?, val hasVideo: Boolean, val hasAudio: Boolean)

internal fun probeFakeMedia(file: String): FakeMediaProbe? = try {
    MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(file)
            FakeMediaProbe(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null,
            )
        } finally {
            retriever.release()
        }
    }
} catch (_: Exception) {
    null
}

internal data class FakeMediaRuntime(
    val events: MutableSharedFlow<String>,
    val nextJob: () -> String,
    val isCancelled: (String) -> Boolean,
    val session: (JSONObject) -> Session,
)

internal data class FakeMediaMutations(
    val mint: (String) -> String,
    val importFile: (Session, String) -> String?,
    val firstFreeAbove: (Session, Double, Double) -> String,
)

private data class FakeModelOptions(val family: String, val englishOnly: Boolean, val sizeBytes: Long)
private data class FakeModelDetails(val id: String, val label: String, val blurb: String, val options: FakeModelOptions)

internal class FakeMediaOperations(
    config: File,
    private val sessions: Map<String, Session>,
    private val runtime: FakeMediaRuntime,
    private val mutations: FakeMediaMutations,
) {
    private val modelsFile = File(config, "fake-models.json")

    fun mediaDuration(file: String): Double {
        for (project in sessions.values) {
            project.media.objectsList().firstOrNull { it.optString("path") == file }?.let { item ->
                if (!item.isNull("duration")) return item.optDouble("duration", 5.0)
            }
        }
        return probeFakeMedia(file)?.duration ?: 5.0
    }

    private fun downloadedModels(): MutableSet<String> = try {
        JSONArray(modelsFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    } catch (_: Exception) {
        mutableSetOf()
    }

    fun setDownloaded(id: String, downloaded: Boolean) {
        val set = downloadedModels()
        if (downloaded) set += id else set -= id
        modelsFile.writeText(JSONArray(set.toList()).toString())
    }

    fun modelsList(): JSONObject {
        val have = downloadedModels()
        fun model(details: FakeModelDetails) = JSONObject().put("id", details.id).put("label", details.label)
            .put("blurb", details.blurb).put("family", details.options.family)
            .put("englishOnly", details.options.englishOnly).put("sizeBytes", details.options.sizeBytes)
            .put("downloaded", details.id in have)
        return JSONObject()
            .put("transcribers", JSONArray()
                .put(model(FakeModelDetails("tiny.en", "Tiny (English)", "Fastest; misses more words.", FakeModelOptions("", true, 75_000_000))))
                .put(model(FakeModelDetails("base", "Base", "Any language; a good balance.", FakeModelOptions("", false, 142_000_000))))
                .put(model(FakeModelDetails("small", "Small", "Hears best; slowest on a phone.", FakeModelOptions("", false, 466_000_000)))))
            .put("speech", JSONArray()
                .put(model(FakeModelDetails("system", "Phone voice", "The voice the phone has; nothing to download.", FakeModelOptions("system", false, 0)))
                    .put("downloaded", true))
                .put(model(FakeModelDetails("chatterbox", "Chatterbox", "Natural voices; a large download.", FakeModelOptions("chatterbox", false, 520_000_000)))))
            .put("voices", JSONArray()
                .put(JSONObject().put("id", 0).put("name", "Default").put("family", "system"))
                .put(JSONObject().put("id", 1).put("name", "Ava").put("family", "chatterbox"))
                .put(JSONObject().put("id", 2).put("name", "Noah").put("family", "chatterbox"))
                .put(JSONObject().put("id", 3).put("name", "Mia").put("family", "chatterbox")))
    }

    fun modelsDownload(kind: String, id: String): JSONObject {
        if (kind != "transcriber" && kind != "speech") throw Refusal("invalid", "$kind is not a model kind")
        val size = modelsList().let { all ->
            (all.getJSONArray("transcribers").objectsList() + all.getJSONArray("speech").objectsList())
                .firstOrNull { it.getString("id") == id }?.getLong("sizeBytes")
        } ?: throw Refusal("notFound", "no model $id")
        val job = "j${runtime.nextJob()}"
        thread(name = "fake-model-$job") {
            fun send(event: JSONObject) = runtime.events.tryEmit(event.put("job", job).put("id", id).toString())
            val total = max(1L, size)
            for (step in 1..40) {
                if (runtime.isCancelled(job)) {
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

    fun transcribe(request: JSONObject): JSONObject {
        val session = runtime.session(request)
        val clipId = request.getString("clipId")
        val clip = session.clip(clipId) ?: throw Refusal("notFound", "no clip $clipId")
        val model = request.getString("model")
        if (model !in downloadedModels()) throw Refusal("refused", "$model is not downloaded")
        val job = "j${runtime.nextJob()}"
        val path = session.path
        val heard = clip.getDouble("duration") * clip.getDouble("speed")
        val from = clip.getDouble("sourceStart")
        thread(name = "fake-captions-$job") {
            fun send(event: JSONObject) = runtime.events.tryEmit(event.put("job", job).put("path", path).toString())
            val stages = listOf("reading" to 6, "loading" to 6, "hearing" to 30)
            for ((stage, steps) in stages) {
                for (step in 1..steps) {
                    if (runtime.isCancelled(job)) {
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
                segments.put(JSONObject().put("start", from + at).put("end", from + end).put("text", FAKE_WORDS[line % FAKE_WORDS.size]))
                at = end
                line++
            }
            send(JSONObject().put("event", "captions.done").put("clipId", clipId).put("segments", segments))
        }
        return JSONObject().put("job", job).put("path", path).put("output", "")
    }

    fun placeCaptions(session: Session, request: JSONObject): String? {
        val clip = session.clip(request.getString("clipId")) ?: throw Refusal("notFound", "that clip is gone")
        val size = when (request.optString("size", "medium")) { "small" -> 0.045; "large" -> 0.09; else -> 0.065 }
        val offsetY = when (request.optString("placement", "bottom")) { "top" -> -0.35; "middle" -> 0.0; else -> 0.35 }
        val speed = clip.getDouble("speed")
        val sourceStart = clip.getDouble("sourceStart")
        var last: String? = null
        val segments = request.getJSONArray("segments").objectsList()
        if (segments.isEmpty()) return null
        val first = clip.getDouble("start") + (segments.minOf { it.getDouble("start") } - sourceStart) / speed
        val lastEnd = clip.getDouble("start") + (segments.maxOf { it.getDouble("end") } - sourceStart) / speed
        val track = mutations.firstFreeAbove(session, first, lastEnd)
        for (segment in segments) {
            val text = segment.optString("text").trim()
            if (text.isEmpty()) continue
            val start = clip.getDouble("start") + (segment.getDouble("start") - sourceStart) / speed
            val length = max(FAKE_MIN, (segment.getDouble("end") - segment.getDouble("start")) / speed)
            val style = JSONObject().put("content", text).put("fontSize", size).put("color", "#ffffff")
            val id = mutations.mint("c")
            session.clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "").put("name", text)
                .put("kind", "text").put("start", max(0.0, start)).put("duration", length).put("sourceStart", 0.0)
                .put("volume", 1.0).put("speed", 1.0).put("text", style).put("offsetY", offsetY))
            last = id
        }
        // A caption lane: its look shared, as the engine's PlaceCaptions makes it.
        session.tracks.objectsList().firstOrNull { it.optString("id") == track }?.put(
            "captions",
            JSONObject().put("style", JSONObject().put("fontSize", size).put("color", "#ffffff")).put("offsetY", offsetY),
        )
        return last
    }

    fun speak(request: JSONObject): JSONObject {
        val session = runtime.session(request)
        val text = request.getString("text").trim()
        if (text.isEmpty()) throw Refusal("invalid", "there is nothing to say")
        val model = request.getString("model")
        if (model != "system" && model !in downloadedModels()) throw Refusal("refused", "$model is not downloaded")
        val speed = request.optDouble("speed", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0
        val job = "j${runtime.nextJob()}"
        val path = session.path
        val seconds = max(1.0, text.split(Regex("\\s+")).size * 0.38 / speed)
        val file = File(path, "speech-${System.currentTimeMillis()}.wav")
        thread(name = "fake-speech-$job") {
            fun send(event: JSONObject) = runtime.events.tryEmit(event.put("job", job).put("path", path).toString())
            for (step in 1..25) {
                if (runtime.isCancelled(job)) {
                    send(JSONObject().put("event", "speech.failed").put("error", JSONObject().put("code", "cancelled").put("message", "Cancelled.")))
                    return@thread
                }
                send(JSONObject().put("event", "speech.progress").put("stage", if (step < 5) "loading" else "reading").put("fraction", step / 25.0))
                Thread.sleep(60)
            }
            try {
                FakeTone.write(file, seconds)
                send(JSONObject().put("event", "speech.done").put("file", file.absolutePath).put("duration", seconds))
            } catch (failure: Exception) {
                send(JSONObject().put("event", "speech.failed").put("error", JSONObject().put("code", "failed").put("message", failure.message ?: "write failed")))
            }
        }
        return JSONObject().put("job", job).put("path", path).put("output", file.absolutePath)
    }

    /** `clips.freeze`: the clip split at `time`, a hold of it laid between, the lane moved on. */
    fun freeze(session: Session, request: JSONObject): String? = with(session) {
        val clip = clip(request.getString("clipId")) ?: throw Refusal("invalid", "no such clip")
        val kind = clip.optString("kind")
        if (kind != "video" && kind != "image") throw Refusal("invalid", "only a picture holds")
        val time = request.getDouble("time")
        val hold = request.optDouble("duration", 1.0).takeIf { !it.isNaN() && it > 0 } ?: 1.0
        val start = clip.getDouble("start")
        if (time <= start + FAKE_MIN || time >= clip.end() - FAKE_MIN) throw Refusal("invalid", "$time s is not inside the clip")
        val track = clip.getString("trackId")
        shift(track, time, hold, except = clip.getString("id"))
        val tail = JSONObject(clip.toString()).put("id", mutations.mint("c")).put("start", time + hold)
            .put("duration", clip.end() - time)
            .put("sourceStart", clip.getDouble("sourceStart") + (time - start) * clip.getDouble("speed"))
        val id = mutations.mint("c")
        val still = JSONObject(clip.toString()).put("id", id).put("start", time).put("duration", hold)
            .put("name", "Freeze").put("speed", 1.0)
        clip.put("duration", time - start)
        clips.put(still)
        clips.put(tail)
        id
    }

    /** `clips.replace`: the file imported, the clip pointed at it in place. */
    fun replace(session: Session, request: JSONObject): String? = with(session) {
        val clip = clip(request.getString("clipId")) ?: throw Refusal("invalid", "no such clip")
        val file = request.getString("file")
        mutations.importFile(session, file)
        val media = media.objectsList().firstOrNull { it.getString("path") == file } ?: throw Refusal("failed", "$file would not import")
        clip.put("mediaId", media.getString("id")).put("name", media.optString("name"))
        null
    }

    /** `clips.reverse`: a job that pretends to write the copy. */
    fun reverse(request: JSONObject): JSONObject {
        val session = runtime.session(request)
        val clip = session.clip(request.getString("clipId")) ?: throw Refusal("invalid", "no such clip")
        if (clip.optString("kind") !in setOf("video", "audio")) throw Refusal("invalid", "nothing to reverse")
        val job = "j${runtime.nextJob()}"
        val path = session.path
        val clipId = clip.getString("id")
        val sourceStart = clip.getDouble("sourceStart")
        val covered = clip.getDouble("duration") * clip.getDouble("speed")
        thread(name = "fake-reverse-$job") {
            fun send(event: JSONObject) = runtime.events.tryEmit(event.put("job", job).put("path", path).put("clipId", clipId).toString())
            for (step in 1..20) {
                if (runtime.isCancelled(job)) {
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

}
