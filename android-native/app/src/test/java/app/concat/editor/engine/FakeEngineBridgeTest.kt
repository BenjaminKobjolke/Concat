// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

class FakeEngineBridgeTest {
    @Test(timeout = 30_000)
    fun setClipSpeedLeavesMissingClipsAlone() {
        val clips = JsonArray(mutableListOf())
        val timeline = JsonObject(mutableMapOf("clips" to clips, "tracks" to JsonArray(mutableListOf())))
        val project = JsonObject(mutableMapOf("timelines" to JsonArray(mutableListOf(timeline))))
        val session = Session("unused", JsonObject(mutableMapOf("project" to project)))
        val command = JsonObject(mutableMapOf("op" to "setClipSpeed", "clipId" to "missing", "speed" to 2.0))
        val edits = FakeEditCommands(
            mint = { error("setClipSpeed does not mint ids") },
            media = { _, _ -> error("setClipSpeed does not read media") },
            place = { _, _, _, _, _ -> error("setClipSpeed does not place media") },
            addTrack = { _, _ -> error("setClipSpeed does not add tracks") },
            firstFreeAbove = { _, _, _ -> error("setClipSpeed does not select tracks") },
        )

        assertNull(edits.apply(session, command))
    }
}

private class JsonObject(private val values: MutableMap<String, Any?>) : JSONObject() {
    override fun getString(name: String): String = values[name] as String
    override fun getDouble(name: String): Double = (values[name] as Number).toDouble()
    override fun getJSONObject(name: String): JSONObject = values[name] as JSONObject
    override fun getJSONArray(name: String): JSONArray = values[name] as JSONArray
    override fun optBoolean(name: String?): Boolean = values[name.orEmpty()] as? Boolean ?: false
    override fun optString(name: String?): String = values[name.orEmpty()] as? String ?: ""
    override fun has(name: String?): Boolean = values.containsKey(name.orEmpty())
}

private class JsonArray(private val values: MutableList<Any?>) : JSONArray() {
    override fun length(): Int = values.size
    override fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
    override fun getJSONObject(index: Int): JSONObject = values[index] as JSONObject
}
