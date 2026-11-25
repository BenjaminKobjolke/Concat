// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
private val AUDIO_FILE_EXTENSIONS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac")

private fun importedKind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    in IMAGE_FILE_EXTENSIONS -> "image"
    in AUDIO_FILE_EXTENSIONS -> "audio"
    else -> "video"
}

/**
 * The editor without the engine: the API's JSON answered in memory, for a
 * build that has no native library. It speaks the same requests and
 * commands the real API does, for the ones the app sends, so the app above
 * it is the app that runs on the engine. Projects live as JSON in their
 * folders, recents in the config folder; the preview is a placeholder
 * painted on the Surface; an export copies the first video.
 */
class FakeEngineBridge(context: Context) : EngineBridge {
    override val isFake = true

    internal val _events = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<String> = _events

    private val store = FakeProjectStore(context)
    private val previewRequests by lazy { FakePreviewRequests(store) }
    internal val config get() = store.config
    internal val sessions get() = store.sessions
    private var nextId
        get() = store.nextId
        set(value) { store.nextId = value }
    private var nextJob = 1
    private val cancelled = HashSet<String>()
    private val mediaOperations by lazy {
        FakeMediaOperations(
            config,
            sessions,
            FakeMediaRuntime(
                _events,
                { "j${nextJob++}" },
                { job -> synchronized(this) { cancelled.remove(job) } },
                ::session,
            ),
            FakeMediaMutations(
                ::mint,
                { project, file -> import(project, file) },
                { project, start, end -> project.firstFreeAbove(start, end) },
            ),
        )
    }

    /** The Stickers page's ids, in the engine's order (ShapeStyle::CARDS). */
    private val stickerIds = listOf(
        "square", "rounded", "circle", "ring", "triangle", "pentagon", "polygon", "star", "heart",
        "pill", "parallelogram", "trapezoid", "line", "arrow", "double-arrow",
    )

    @Synchronized
    override fun call(request: String): String = try {
        val json = JSONObject(request)
        JSONObject().put("result", dispatch(json)).toString()
    } catch (refusal: Refusal) {
        error(refusal.code, refusal.message ?: "")
    } catch (failure: Exception) {
        Log.w(TAG, "fake engine failed on $request", failure)
        error("invalid", failure.message ?: failure.toString())
    }

    override fun preview(projectPath: String): PreviewBridge = FakePreview(this, projectPath)

    private fun error(code: String, message: String) =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun dispatch(request: JSONObject): Any = when (val method = request.optString("method")) {
        "version" -> JSONObject().put("apiVersion", "0.2").put("concat", "fake").put("capabilities", JSONArray().put("events"))
        "project.list" -> recents()
        "project.create" -> create(request)
        "project.open" -> open(request.getString("path")).viewOut()
        "project.get" -> session(request).viewOut()
        "project.save" -> session(request).let { session ->
            request.optString("name").takeIf { !request.isNull("name") && it.isNotBlank() }?.let { name ->
                session.view.getJSONObject("settings").put("name", name.trim())
                remember(info(session))
            }
            write(session)
            JSONObject()
        }
        "project.setVideo" -> session(request).let { session -> edit(session) { store.setVideo(session, request.getJSONObject("video")) } }
        "media.filmstrip" -> FakePictures.filmstrip(request)
        "media.peaks" -> FakePictures.peaks(request, mediaOperations.mediaDuration(request.getString("file")))
        "preview.frame" -> previewRequests.previewFrame(request)
        "project.cover" -> previewRequests.projectCover(request)
        "project.close" -> {
            val session = session(request)
            if (request.optBoolean("save")) write(session)
            sessions.remove(session.path)
            JSONObject()
        }
        "edit.apply" -> session(request).let { session -> edit(session) { applyCommand(session, request.getJSONObject("command")) } }
        "edit.undo" -> session(request).let { step(it, it.undo, it.redo) }
        "edit.redo" -> session(request).let { step(it, it.redo, it.undo) }
        "media.import" -> session(request).let { session -> edit(session) { import(session, request.getString("file")) } }
        "export.run" -> export(request)
        "export.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        "catalogue.list" -> FakeCatalogue.list(request.optString("kind").ifEmpty { null })
        "models.list" -> mediaOperations.modelsList()
        "shapes.list" -> JSONArray().apply {
            for (id in stickerIds) put(JSONObject().put("id", id).put("style", JSONObject().put("kind", id).put("fill", "#ffffff").put("width", 0.35)))
        }
        "clips.bounds" -> session(request).let { session -> FakeFrame.bounds(session) }
        "models.download" -> mediaOperations.modelsDownload(request.getString("kind"), request.getString("id"))
        "models.delete" -> JSONObject().also { mediaOperations.setDownloaded(request.getString("id"), false) }
        "captions.transcribe" -> mediaOperations.transcribe(request)
        "captions.place" -> session(request).let { session -> edit(session) { mediaOperations.placeCaptions(session, request) } }
        "captions.cancel", "speech.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        "speech.speak" -> mediaOperations.speak(request)
        "clips.freeze" -> session(request).let { session -> edit(session) { mediaOperations.freeze(session, request) } }
        "clips.replace" -> session(request).let { session -> edit(session) { mediaOperations.replace(session, request) } }
        "clips.reverse" -> mediaOperations.reverse(request)
        "clips.useReversed" -> session(request).let { session ->
            edit(session) {
                // The fake writes no copy: the clip is only marked as shown backwards.
                val clip = session.clip(request.getString("clipId")) ?: throw Refusal("invalid", "no such clip")
                clip.put("name", clip.optString("name").removeSuffix(" (reversed)") + " (reversed)")
                null
            }
        }
        "reverse.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        else -> throw Refusal("invalid", "the in-memory engine does not answer $method")
    }

    // -- frames, strips and outlines ----------------------------------------


    private fun session(request: JSONObject): Session = store.session(request)

    private fun Session.viewOut(createdId: String? = null): JSONObject = store.viewOut(this, createdId)

    // -- projects -----------------------------------------------------------

    private fun recents(): JSONArray = store.recents()
    private fun remember(info: JSONObject) = store.remember(info)
    private fun create(request: JSONObject): JSONObject = store.create(request)
    private fun open(path: String): Session = store.open(path)
    private fun info(session: Session): JSONObject = store.info(session)
    private fun write(session: Session) = store.write(session)
    private fun edit(session: Session, body: () -> String?): JSONObject {
        val before = session.view.toString()
        val created = try {
            body()
        } catch (failure: Exception) {
            session.view = JSONObject(before)
            throw failure
        }
        if (session.view.toString() != before) {
            session.undo.addLast(before)
            session.redo.clear()
        }
        return session.viewOut(created)
    }

    private fun step(session: Session, from: ArrayDeque<String>, to: ArrayDeque<String>): JSONObject {
        val state = from.removeLastOrNull() ?: return session.viewOut()
        to.addLast(session.view.toString())
        session.view = JSONObject(state)
        return session.viewOut()
    }

    private fun mint(prefix: String) = "$prefix${nextId++}"

    private val editCommands by lazy {
        FakeEditCommands(
            ::mint,
            { session, id -> session.media(id) },
            { session, media, track, start, length -> session.place(media, track, start, length) },
            ::addTrack,
            { session, start, end -> session.firstFreeAbove(start, end) },
        )
    }
    private fun applyCommand(session: Session, command: JSONObject): String? = editCommands.apply(session, command)
    private fun Session.media(id: String): JSONObject =
        media.objectsList().firstOrNull { it.getString("id") == id } ?: throw Refusal("refused", "that media is gone")

    private fun Session.place(media: JSONObject, track: String, start: Double, length: Double): String {
        val id = mint("c")
        clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", media.getString("id"))
            .put("name", media.getString("name")).put("kind", media.getString("kind")).put("start", start)
            .put("duration", length).put("sourceStart", 0.0).put("volume", 1.0).put("speed", 1.0))
        return id
    }

    private fun addTrack(session: Session, top: Boolean): String {
        val id = mint("t")
        val track = JSONObject().put("id", id).put("visible", true).put("muted", false)
        val list = session.tracks.objectsList().toMutableList()
        // Stored bottom first, as the engine does: the last lane is drawn on top.
        if (top) list.add(track) else list.add(0, track)
        session.timeline.put("tracks", JSONArray().apply { list.forEach { put(it) } })
        return id
    }

    /** The first lane free over the span above the highest one taken there, or a new one on top: where a title goes. */
    private fun Session.firstFreeAbove(start: Double, end: Double): String {
        val ids = tracks.objectsList().map { it.getString("id") }
        val floor = ids.indices.filter { !free(ids[it], start, end) }.maxOfOrNull { it + 1 } ?: 0
        return ids.drop(floor).firstOrNull { free(it, start, end) } ?: addTrack(this, top = true)
    }

    private fun import(session: Session, file: String): String? {
        if (session.media.objectsList().any { it.getString("path") == file }) return null
        val source = File(file)
        if (!source.exists()) throw Refusal("failed", "$file does not exist")
        val name = source.name
        var kind = importedKind(name)
        var duration: Double? = null
        if (kind != "image") {
            val probe = probeFakeMedia(file)
            duration = probe?.duration
            if (probe == null) Log.w(TAG, "could not probe $file")
            else if (!probe.hasVideo && probe.hasAudio) kind = "audio"
        }
        val id = mint("m")
        session.media.put(JSONObject().put("id", id).put("path", file).put("name", name).put("kind", kind)
            .put("duration", duration ?: JSONObject.NULL).put("hasAudio", kind != "image"))
        return id
    }

    // -- export -------------------------------------------------------------

    private fun export(request: JSONObject): JSONObject {
        val session = session(request)
        val output = request.getString("output")
        val job = "j${nextJob++}"
        val source = session.media.objectsList().firstOrNull { it.getString("kind") == "video" }?.getString("path")
        val settings = session.view.getJSONObject("settings")
        val width = request.optInt("width", settings.getInt("width"))
        val height = request.optInt("height", settings.getInt("height"))
        val total = max(1, (session.clipList().maxOfOrNull { it.end() } ?: 1.0).times(30).toInt())
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

    /** A snapshot of what the preview draws: the open project's view. */
    @Synchronized
    internal fun snapshot(path: String): JSONObject? = store.snapshot(path)

    companion object {
        private const val TAG = "concat-fake"
    }
}
