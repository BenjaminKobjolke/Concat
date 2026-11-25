// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import android.content.Context
import java.io.File
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal const val FAKE_DOCUMENT = "fake-project.json"
internal const val FAKE_MIN = 1.0 / 60

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

internal class FakeProjectStore(context: Context) {
    val config = File(context.filesDir, "config").apply { mkdirs() }
    private val recentsFile = File(config, "fake-recents.json")
    val sessions = HashMap<String, Session>()
    var nextId = 1

    fun session(request: JSONObject): Session =
        sessions[request.getString("path")] ?: throw Refusal("notOpen", "${request.getString("path")} is not open")

    fun viewOut(session: Session, createdId: String? = null): JSONObject {
        val out = JSONObject(session.view.toString()).put("canUndo", session.undo.isNotEmpty()).put("canRedo", session.redo.isNotEmpty())
        if (createdId != null) out.put("createdId", createdId)
        return out
    }

    fun recents(): JSONArray = try {
        val all = JSONArray(recentsFile.readText())
        JSONArray().apply { all.objectsList().filter { File(it.optString("path"), FAKE_DOCUMENT).exists() }.forEach { put(it) } }
    } catch (_: Exception) {
        JSONArray()
    }

    fun remember(info: JSONObject) {
        val list = recents()
        val kept = JSONArray().put(info.put("openedAt", System.currentTimeMillis()))
        for (entry in list.objectsList()) if (entry.optString("path") != info.optString("path")) kept.put(entry)
        recentsFile.writeText(kept.toString())
    }

    fun create(request: JSONObject): JSONObject {
        val name = request.getString("name").ifBlank { "Untitled" }
        val video = request.optJSONObject("video") ?: JSONObject().put("width", 1080).put("height", 1920).put("rateNum", 30).put("rateDen", 1)
        val folder = File(request.getString("location"), name.replace(Regex("[^A-Za-z0-9 _-]"), "_"))
        if (File(folder, FAKE_DOCUMENT).exists()) throw Refusal("failed", "a project is already in ${folder.path}")
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
        return viewOut(session)
    }

    fun open(path: String): Session {
        sessions[path]?.let { return it }
        val file = File(path, FAKE_DOCUMENT)
        if (!file.exists()) throw Refusal("failed", "no project in $path")
        val session = Session(path, JSONObject(file.readText()))
        val taken = (session.clips.objectsList() + session.media.objectsList() + session.tracks.objectsList())
            .mapNotNull { Regex("^[a-z]+(\\d+)$").find(it.optString("id"))?.groupValues?.get(1)?.toIntOrNull() }
        nextId = max(nextId, (taken.maxOrNull() ?: 0) + 1)
        sessions[path] = session
        remember(info(session))
        return session
    }

    fun info(session: Session): JSONObject {
        val settings = session.view.getJSONObject("settings")
        return JSONObject().put("path", session.path).put("name", settings.getString("name"))
            .put("width", settings.getInt("width")).put("height", settings.getInt("height"))
            .put("rateNum", settings.getLong("rateNum")).put("rateDen", settings.getLong("rateDen"))
            .put("canvas", JSONArray(listOf(0, 0, 0, 255)))
    }

    fun write(session: Session) {
        File(session.path, FAKE_DOCUMENT).writeText(session.view.toString())
    }

    fun snapshot(path: String): JSONObject? = sessions[path]?.view?.let { JSONObject(it.toString()) }

    fun readView(path: String): JSONObject? =
        File(path, FAKE_DOCUMENT).takeIf { it.exists() }?.let { JSONObject(it.readText()) }

    fun setVideo(session: Session, video: JSONObject): String? {
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
}
