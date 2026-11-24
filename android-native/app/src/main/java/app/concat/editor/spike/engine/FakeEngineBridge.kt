// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
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

    internal val config = File(context.filesDir, "config").apply { mkdirs() }
    private val recentsFile = File(config, "fake-recents.json")
    internal val modelsFile = File(config, "fake-models.json")
    internal val sessions = HashMap<String, Session>()
    private var nextId = 1
    internal var nextJob = 1
    internal val commands = FakeCommandHandler(::mint)
    internal val cancelled = HashSet<String>()

    /** The Stickers page's ids, in the engine's order (ShapeStyle::CARDS). */
    internal val stickerIds = listOf(
        "square", "rounded", "circle", "ring", "triangle", "pentagon", "polygon", "star", "heart",
        "pill", "parallelogram", "trapezoid", "line", "arrow", "double-arrow",
    )

    internal class Session(val path: String, var view: JSONObject) {
        val undo = ArrayDeque<String>()
        val redo = ArrayDeque<String>()
        val project get() = view.getJSONObject("project")
        val timeline get() = project.getJSONArray("timelines").getJSONObject(0)
        val tracks get() = timeline.getJSONArray("tracks")
        val clips get() = timeline.getJSONArray("clips")
        val media get() = project.getJSONArray("media")
    }

    internal class Refusal(val code: String, message: String) : Exception(message)

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
        "project.setVideo" -> session(request).let { session -> edit(session) { setVideo(session, request.getJSONObject("video")) } }
        "media.filmstrip" -> FakePictures.filmstrip(request)
        "media.peaks" -> FakePictures.peaks(request, mediaDuration(request.getString("file")))
        "preview.frame" -> FakeFrameRequests.previewFrame(request, sessions, DOCUMENT)
        "project.cover" -> projectCover(request)
        "project.close" -> {
            val session = session(request)
            if (request.optBoolean("save")) write(session)
            sessions.remove(session.path)
            JSONObject()
        }
        "edit.apply" -> session(request).let { session -> edit(session) { commands.applyCommand(session, request.getJSONObject("command")) } }
        "edit.undo" -> session(request).let { step(it, it.undo, it.redo) }
        "edit.redo" -> session(request).let { step(it, it.redo, it.undo) }
        "media.import" -> session(request).let { session -> edit(session) { commands.import(session, request.getString("file")) } }
        "export.run" -> export(request)
        "export.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        "catalogue.list" -> FakeCatalogue.list(request.optString("kind").ifEmpty { null })
        "models.list" -> modelsList()
        "shapes.list" -> JSONArray().apply {
            for (id in stickerIds) put(JSONObject().put("id", id).put("style", JSONObject().put("kind", id).put("fill", "#ffffff").put("width", 0.35)))
        }
        "clips.bounds" -> session(request).let { session -> fakeBounds(session) }
        "models.download" -> modelsDownload(request.getString("kind"), request.getString("id"))
        "models.delete" -> JSONObject().also { setDownloaded(request.getString("id"), false) }
        "captions.transcribe" -> transcribe(request)
        "captions.place" -> session(request).let { session -> edit(session) { placeCaptions(session, request) } }
        "captions.cancel", "speech.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        "speech.speak" -> speak(request)
        "clips.freeze" -> session(request).let { session -> edit(session) { freeze(session, request) } }
        "clips.replace" -> session(request).let { session -> edit(session) { replace(session, request) } }
        "clips.reverse" -> reverse(request)
        "clips.useReversed" -> session(request).let { session ->
            edit(session) {
                // The fake writes no copy: the clip is only marked as shown backwards.
                val clip = commands.clip(session, request.getString("clipId")) ?: throw Refusal("invalid", "no such clip")
                clip.put("name", clip.optString("name").removeSuffix(" (reversed)") + " (reversed)")
                null
            }
        }
        "reverse.cancel" -> JSONObject().also { cancelled += request.getString("job") }
        else -> throw Refusal("invalid", "the in-memory engine does not answer $method")
    }

    // -- frames, strips and outlines ----------------------------------------

    internal fun session(request: JSONObject): Session =
        sessions[request.getString("path")] ?: throw Refusal("notOpen", "${request.getString("path")} is not open")

    private fun Session.viewOut(createdId: String? = null): JSONObject {
        val out = JSONObject(view.toString()).put("canUndo", undo.isNotEmpty()).put("canRedo", redo.isNotEmpty())
        if (createdId != null) out.put("createdId", createdId)
        return out
    }

    // -- projects -----------------------------------------------------------

    private fun recents(): JSONArray = try {
        // A folder deleted behind the editor's back is no longer a recent.
        val all = JSONArray(recentsFile.readText())
        JSONArray().apply { all.objectsList().filter { File(it.optString("path"), DOCUMENT).exists() }.forEach { put(it) } }
    } catch (_: Exception) {
        JSONArray()
    }

    private fun remember(info: JSONObject) {
        val list = recents()
        val kept = JSONArray().put(info.put("openedAt", System.currentTimeMillis()))
        for (entry in list.objectsList()) if (entry.optString("path") != info.optString("path")) kept.put(entry)
        recentsFile.writeText(kept.toString())
    }

    private fun create(request: JSONObject): JSONObject {
        val name = request.getString("name").ifBlank { "Untitled" }
        val video = request.optJSONObject("video") ?: JSONObject().put("width", 1080).put("height", 1920).put("rateNum", 30).put("rateDen", 1)
        val folder = File(request.getString("location"), name.replace(Regex("[^A-Za-z0-9 _-]"), "_"))
        if (File(folder, DOCUMENT).exists()) throw Refusal("failed", "a project is already in ${folder.path}")
        folder.mkdirs()
        val view = JSONObject()
            .put("project", JSONObject()
                .put("media", JSONArray())
                .put("activeTimelineId", "TL1")
                .put("timelines", JSONArray().put(JSONObject()
                    .put("id", "TL1").put("name", "Timeline 1").put("video", video)
                    .put("tracks", JSONArray().apply { for (i in 1..3) put(JSONObject().put("id", "T$i").put("visible", true).put("muted", false)) })
                    .put("clips", JSONArray()))))
            .put("settings", JSONObject().put("name", name).put("width", video.getInt("width")).put("height", video.getInt("height"))
                .put("rateNum", video.getLong("rateNum")).put("rateDen", video.getLong("rateDen")))
        val session = Session(folder.absolutePath, view)
        sessions[session.path] = session
        write(session)
        remember(info(session))
        return session.viewOut()
    }

    private fun open(path: String): Session {
        sessions[path]?.let { return it }
        val file = File(path, DOCUMENT)
        if (!file.exists()) throw Refusal("failed", "no project in $path")
        val session = Session(path, JSONObject(file.readText()))
        // Ids minted from here on must not be ones the document already holds
        // (the counter starts again with every process).
        val taken = (session.clips.objectsList() + session.media.objectsList() + session.tracks.objectsList())
            .mapNotNull { Regex("^[a-z]+(\\d+)$").find(it.optString("id"))?.groupValues?.get(1)?.toIntOrNull() }
        nextId = max(nextId, (taken.maxOrNull() ?: 0) + 1)
        sessions[path] = session
        remember(info(session))
        return session
    }

    private fun info(session: Session): JSONObject {
        val settings = session.view.getJSONObject("settings")
        return JSONObject().put("path", session.path).put("name", settings.getString("name"))
            .put("width", settings.getInt("width")).put("height", settings.getInt("height"))
            .put("rateNum", settings.getLong("rateNum")).put("rateDen", settings.getLong("rateDen"))
            .put("canvas", JSONArray(listOf(0, 0, 0, 255)))
    }

    private fun write(session: Session) {
        File(session.path, DOCUMENT).writeText(session.view.toString())
    }

    // -- edits --------------------------------------------------------------

    /** Runs one undoable edit on a copy, committing it only if it worked. */
    internal fun edit(session: Session, body: () -> String?): JSONObject {
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

    internal fun mint(prefix: String) = "$prefix${nextId++}"

        // -- export -------------------------------------------------------------

    /** A snapshot of what the preview draws: the open project's view. */
    @Synchronized
    internal fun snapshot(path: String): JSONObject? = sessions[path]?.view?.let { JSONObject(it.toString()) }

    companion object {
        internal const val TAG = "concat-fake"
        internal const val DOCUMENT = "fake-project.json"
        internal const val MIN = 1.0 / 60
        internal val FAKE_WORDS = listOf(
            "This is what the in-memory engine heard.",
            "Every line can be corrected before it is placed.",
            "The real engine hears the clip's own sound.",
            "Captions land over the clip as titles.",
        )
    }
}

