// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.api

import app.concat.editor.engine.EngineBridge
import app.concat.editor.engine.PreviewBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConcatApiTest {
    @Test(timeout = 30_000)
    fun previewFrameRequestKeepsItsCurrentWireShape() = runBlocking {
        val engine = RecordingEngineBridge()

        ConcatApi(engine).previewFrameTo(PreviewFrameRequest("project.concat", 1.25, "frame.png", 320, 180))

        assertRequest(
            engine,
            "preview.frame",
            "path" to "project.concat",
            "time" to 1.25,
            "output" to "frame.png",
            "width" to 320,
            "height" to 180,
        )
    }

    @Test(timeout = 30_000)
    fun exportRequestKeepsItsCurrentWireShape() = runBlocking {
        val engine = RecordingEngineBridge()

        assertEquals("job-1", ConcatApi(engine).exportRun(ExportRunRequest("project.concat", "render.mp4", 1920, 1080, 20, "medium")))

        assertRequest(
            engine,
            "export.run",
            "path" to "project.concat",
            "output" to "render.mp4",
            "width" to 1920,
            "height" to 1080,
            "crf" to 20,
            "preset" to "medium",
            "codec" to "h264",
        )
    }

    @Test(timeout = 30_000)
    fun captionsPlaceRequestKeepsItsCurrentWireShape() = runBlocking {
        val engine = RecordingEngineBridge()

        ConcatApi(engine).captionsPlace(
            CaptionsPlaceRequest("project.concat", "clip-1", listOf(CaptionSegment(1.0, 2.5, "Hello")), "bottom", "large"),
        )

        val request = JSONObject(engine.request)
        assertEquals("captions.place", request.getString("method"))
        assertEquals("project.concat", request.getString("path"))
        assertEquals("clip-1", request.getString("clipId"))
        assertEquals(1.0, request.getJSONArray("segments").getJSONObject(0).getDouble("start"), 0.0)
        assertEquals(2.5, request.getJSONArray("segments").getJSONObject(0).getDouble("end"), 0.0)
        assertEquals("Hello", request.getJSONArray("segments").getJSONObject(0).getString("text"))
        assertEquals("bottom", request.getString("placement"))
        assertEquals("large", request.getString("size"))
    }

    @Test(timeout = 30_000)
    fun speechRequestKeepsItsCurrentWireShape() = runBlocking {
        val engine = RecordingEngineBridge()

        assertEquals("job-1", ConcatApi(engine).speechSpeak(SpeechRequest("project.concat", "Hello", "voice-model", 3, 1.25)))

        assertRequest(
            engine,
            "speech.speak",
            "path" to "project.concat",
            "text" to "Hello",
            "model" to "voice-model",
            "voice" to 3,
            "speed" to 1.25,
        )
    }
}

private fun assertRequest(engine: RecordingEngineBridge, method: String, vararg fields: Pair<String, Any>) {
    val request = JSONObject(engine.request)
    assertEquals(method, request.getString("method"))
    fields.forEach { (name, value) -> assertEquals(value, request.get(name)) }
}

private class RecordingEngineBridge : EngineBridge {
    override val isFake = true
    private val mutableEvents = MutableSharedFlow<String>()
    override val events: SharedFlow<String> = mutableEvents
    lateinit var request: String

    override fun call(request: String): String {
        this.request = request
        val result = when (JSONObject(request).getString("method")) {
            "export.run", "speech.speak" -> JSONObject().put("job", "job-1")
            "captions.place" -> JSONObject().put("project", JSONObject().put("timelines", org.json.JSONArray()))
            else -> JSONObject()
        }
        return JSONObject().put("result", result).toString()
    }

    override fun preview(projectPath: String): PreviewBridge = error("preview is not used by API request tests")
}
