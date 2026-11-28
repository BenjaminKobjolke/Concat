// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.api

import android.util.Log
import app.concat.editor.engine.EngineBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A file's sound outline: `media.peaks`. */
class Peaks(val min: FloatArray, val max: FloatArray, val perSecond: Float)

/** A refusal from the API: its `code` ("refused", "notOpen", ...) and the sentence to show. */
class ApiException(val code: String, override val message: String) : Exception(message)

data class ProjectCreation(val location: String, val name: String, val width: Int, val height: Int, val fps: Int)

/**
 * The API's requests as Kotlin calls, each one JSON object through
 * [EngineBridge.call] on the IO dispatcher. The request shapes are
 * concat-api's `Request` (tagged by `method`, camelCase fields), and an
 * edit is concat-api's wire `Command` (tagged by `op`, times in seconds),
 * as [Commands] builds them.
 */
class ConcatApi(val engine: EngineBridge) {
    val events: Flow<ApiEvent> = engine.events.mapNotNull { ApiEvent.parse(it) }

    /** Every event as its JSON object: captions, models and speech read these. */
    val rawEvents: Flow<JSONObject> = engine.events.mapNotNull {
        try {
            JSONObject(it)
        } catch (_: Exception) {
            null
        }
    }

    /** Sends one request; its `result`, or the error thrown. */
    suspend fun request(request: JSONObject): Any = withContext(Dispatchers.IO) {
        val text = request.toString()
        val reply = JSONObject(engine.call(text))
        reply.optJSONObject("error")?.let { error ->
            Log.w(TAG, "${request.optString("method")} refused: $error")
            throw ApiException(error.optString("code", "failed"), error.optString("message", "the editor said no"))
        }
        reply.opt("result") ?: JSONObject()
    }

    private suspend fun view(request: JSONObject) = ProjectView.from(request(request) as JSONObject)

    suspend fun listProjects(): List<ProjectInfo> =
        (request(method("project.list")) as? JSONArray)?.objects()?.map(ProjectInfo::from) ?: emptyList()

    /** Makes a project folder named after [name] under [location], opens it and says where it is. */
    suspend fun createProject(project: ProjectCreation): String {
        request(
            method("project.create")
                .put("location", project.location)
                .put("name", project.name)
                .put("video", JSONObject().put("width", project.width).put("height", project.height).put("rateNum", project.fps).put("rateDen", 1)),
        )
        // The reply is the editor view, which names no folder; recents put
        // the new project first.
        return listProjects().firstOrNull()?.path ?: throw ApiException("failed", "the new project is not in recents")
    }

    suspend fun open(path: String) = view(method("project.open").put("path", path))
    suspend fun get(path: String) = view(method("project.get").put("path", path))
    suspend fun save(path: String) = request(method("project.save").put("path", path))

    /** Writes the document under a new name: `project.save` with `name`. */
    suspend fun rename(path: String, name: String) = request(method("project.save").put("path", path).put("name", name))

    /** Sets the active timeline's frame and rate, one undo step: `project.setVideo`. */
    suspend fun setVideo(path: String, video: VideoSettings) = view(
        method("project.setVideo").put("path", path)
            .put("video", JSONObject().put("width", video.width).put("height", video.height).put("rateNum", video.rateNum).put("rateDen", video.rateDen)),
    )

    // -- pictures and sound outlines for the timeline --
    //
    // Plain JSON requests like every other, so both bridges carry them
    // without a binding of their own.

    /**
     * `media.filmstrip`: [count] pictures of [file] side by side at
     * [height] pixels, evenly across [from]..[to] seconds of it, as PNG
     * bytes. The reply's picture is `png` (concat-api's `Picture`); an
     * older build's `pngBase64` is read too.
     */
    suspend fun mediaFilmstrip(input: FilmstripRequest): ByteArray {
        val reply = request(
            method("media.filmstrip").put("file", input.file).put("from", input.from).put("to", input.to)
                .put("count", input.count.coerceIn(1, 60)).put("height", input.height.coerceIn(16, 240)),
        ) as JSONObject
        return decodePicture(reply)
    }

    /** `media.peaks`: the loudness outline of [file]'s sound, [perSecond] slices a second. */
    suspend fun mediaPeaks(path: String?, file: String, perSecond: Int = 20): Peaks {
        val reply = request(
            method("media.peaks").put("path", path ?: JSONObject.NULL).put("file", file)
                .put("stream", JSONObject.NULL).put("perSecond", perSecond),
        ) as JSONObject
        fun floats(name: String): FloatArray {
            val array = reply.optJSONArray(name) ?: return FloatArray(0)
            return FloatArray(array.length()) { array.optDouble(it).toFloat() }
        }
        return Peaks(floats("min"), floats("max"), reply.optDouble("perSecond", perSecond.toDouble()).toFloat())
    }

    /** `preview.frame`: writes the requested frame as a PNG. */
    suspend fun previewFrameTo(input: PreviewFrameRequest) = request(
        method("preview.frame").put("path", input.path).put("time", input.time).put("output", input.output)
            .put("width", input.width).put("height", input.height),
    )

    private fun decodePicture(reply: JSONObject): ByteArray {
        val text = reply.optString("png").ifEmpty { reply.optString("pngBase64") }
        if (text.isEmpty()) throw ApiException("failed", "no picture in the reply")
        return android.util.Base64.decode(text, android.util.Base64.DEFAULT)
    }
    suspend fun close(path: String, save: Boolean) = request(method("project.close").put("path", path).put("save", save))
    suspend fun undo(path: String) = view(method("edit.undo").put("path", path))
    suspend fun redo(path: String) = view(method("edit.redo").put("path", path))
    suspend fun import(path: String, file: String) = view(method("media.import").put("path", path).put("file", file))

    suspend fun apply(path: String, command: JSONObject) =
        view(method("edit.apply").put("path", path).put("command", command))

    /** Starts an export; the job's name. */
    suspend fun exportRun(input: ExportRunRequest): String {
        val started = request(
            method("export.run")
                .put("path", input.path)
                .put("output", input.output)
                .put("width", input.width)
                .put("height", input.height)
                .put("crf", input.crf)
                .put("preset", input.preset)
                .put("codec", "h264"),
        ) as JSONObject
        return started.getString("job")
    }

    suspend fun exportCancel(job: String) = request(method("export.cancel").put("job", job))

    suspend fun version(): VersionInfo {
        val json = request(method("version")) as JSONObject
        val caps = json.optJSONArray("capabilities")
        return VersionInfo(
            json.optString("apiVersion"),
            json.optString("concat"),
            caps?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
    }

    /** The packages of one kind ("effect", "filter", "audio", "transition"), or all. */
    suspend fun catalogue(kind: String? = null): List<PackageInfo> {
        val request = method("catalogue.list").apply { if (kind != null) put("kind", kind) }
        return (request(request) as? JSONArray)?.objects()?.map(PackageInfo::from) ?: emptyList()
    }

    /** `project.cover`: the cover written to [output] as a PNG of [width] × [height]. */
    suspend fun projectCover(path: String, output: String, width: Int, height: Int) = request(
        method("project.cover").put("path", path).put("output", output).put("width", width).put("height", height),
    )

    // -- stickers and bounds --

    /** `shapes.list`: the Stickers page's cards, in order. */
    suspend fun shapes(): List<ShapeCard> =
        (request(method("shapes.list")) as? JSONArray)?.objects()?.map { ShapeCard(it.getString("id"), it.getJSONObject("style")) }
            ?: emptyList()

    /** `clips.bounds`: each title's and shape's painted block, by clip id. */
    suspend fun bounds(path: String): Map<String, ClipBounds> =
        (request(method("clips.bounds").put("path", path)) as? JSONArray)?.objects()?.associate {
            it.getString("clipId") to ClipBounds(it.optDouble("width"), it.optDouble("height"), it.optDouble("offsetX", 0.0), it.optDouble("offsetY", 0.0))
        } ?: emptyMap()

    // -- speech models --

    suspend fun models(): ModelsInfo = ModelsInfo.from(request(method("models.list")) as JSONObject)

    /** Starts a download; the job's name. [kind] is "transcriber" or "speech". */
    suspend fun modelsDownload(kind: String, id: String): String =
        (request(method("models.download").put("kind", kind).put("id", id)) as JSONObject).getString("job")

    suspend fun modelsDelete(kind: String, id: String) = request(method("models.delete").put("kind", kind).put("id", id))

    // -- captions --

    /** Starts hearing [clipId]; the job's name. Its words come in `captions.done`. */
    suspend fun captionsTranscribe(path: String, clipId: String, model: String, language: String?): String {
        val request = method("captions.transcribe").put("path", path).put("clipId", clipId).put("model", model)
        if (!language.isNullOrBlank()) request.put("language", language)
        return (request(request) as JSONObject).getString("job")
    }

    /** Puts the requested segments over their clip as titles, one undo step. */
    suspend fun captionsPlace(input: CaptionsPlaceRequest) = view(
        method("captions.place").put("path", input.path).put("clipId", input.clipId)
            .put("segments", JSONArray().apply { input.segments.forEach { put(it.toJson()) } })
            .put("placement", input.placement).put("size", input.size),
    )

    suspend fun captionsCancel(job: String) = request(method("captions.cancel").put("job", job))

    // -- reading aloud --

    /** Starts reading the requested text into a file in the project; the job's name. */
    suspend fun speechSpeak(input: SpeechRequest): String =
        (request(
            method("speech.speak").put("path", input.path).put("text", input.text).put("model", input.model)
                .put("voice", input.voice).put("speed", input.speed),
        ) as JSONObject).getString("job")

    suspend fun speechCancel(job: String) = request(method("speech.cancel").put("job", job))

    // -- clip operations that need the host: a frame grabbed, a file probed or written --

    /** The picture at [time] held for [duration] seconds: split, hold, ripple, one undo step. */
    suspend fun clipsFreeze(path: String, clipId: String, time: Double, duration: Double = 1.0) = view(
        method("clips.freeze").put("path", path).put("clipId", clipId).put("time", time).put("duration", duration),
    )

    /** The clip pointed at [file], keeping its place, length and look. */
    suspend fun clipsReplace(path: String, clipId: String, file: String) = view(
        method("clips.replace").put("path", path).put("clipId", clipId).put("file", file),
    )

    /** Starts writing the clip's span backwards; the job's name. `reverse.done` then says where. */
    suspend fun clipsReverse(path: String, clipId: String): String =
        (request(method("clips.reverse").put("path", path).put("clipId", clipId)) as JSONObject).getString("job")

    /** The clip shown from the copy a `reverse.done` [event] named. */
    suspend fun clipsUseReversed(path: String, event: JSONObject) = view(
        method("clips.useReversed").put("path", path).put("clipId", event.getString("clipId"))
            .put("file", event.getString("file")).put("sourceStart", event.getDouble("sourceStart"))
            .put("covered", event.getDouble("covered")),
    )

    suspend fun reverseCancel(job: String) = request(method("reverse.cancel").put("job", job))

    private fun method(name: String) = JSONObject().put("method", name)

    private companion object {
        const val TAG = "concat-api"
    }
}
