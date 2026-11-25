// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModelTest {
    @Test(timeout = 30_000)
    fun projectViewParsesTheActiveTimelineAndUsesSettingsFallbacks() {
        val view = ProjectView.from(
            TestJsonObject(
                mapOf(
                    "project" to TestJsonObject(
                        mapOf(
                            "activeTimelineId" to "active",
                            "timelines" to TestJsonArray(
                                listOf(
                                    TestJsonObject(
                                        mapOf(
                                            "id" to "inactive",
                                            "clips" to TestJsonArray(listOf(TestJsonObject(mapOf("id" to "ignored")))),
                                        ),
                                    ),
                                    TestJsonObject(
                                        mapOf(
                                            "id" to "active",
                                            "clips" to TestJsonArray(
                                                listOf(
                                                    TestJsonObject(mapOf("id" to "later", "kind" to "image", "start" to 4.0, "duration" to 2.0)),
                                                    TestJsonObject(mapOf("id" to "first", "kind" to "video", "start" to 1.0, "duration" to 3.0)),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            "media" to TestJsonArray(
                                listOf(TestJsonObject(mapOf("id" to "video", "path" to "video.mp4", "duration" to 8.0))),
                            ),
                        ),
                    ),
                    "settings" to TestJsonObject(mapOf("name" to "Edit", "width" to 1920, "height" to 1080)),
                ),
            ),
        )

        assertEquals("active", view.timelineId)
        assertEquals("Edit", view.name)
        assertEquals(1920, view.width)
        assertEquals(1080, view.height)
        assertEquals(30L, view.rateNum)
        assertEquals(1L, view.rateDen)
        assertEquals(listOf("first", "later"), view.clips.map { it.id })
        assertEquals(ClipKind.Video, view.clips.first().kind)
        assertEquals(6.0, view.duration, 0.0)
        assertEquals("video.mp4", view.media.single().path)
    }

    @Test(timeout = 30_000)
    fun projectViewLookupAndJsonArrayConversionKeepTheirCurrentBehavior() {
        val clip = Clip("clip", "track", "media", "Clip", ClipKind.Video, 1.0, 2.0, 0.0, 1.0, 1.0, null)
        val media = MediaItem("media", "video.mp4", "Video", "video", 8.0)
        val view = ProjectView(
            name = "Edit",
            width = 1920,
            height = 1080,
            rateNum = 30,
            rateDen = 1,
            canUndo = false,
            canRedo = false,
            media = listOf(media),
            tracks = emptyList(),
            clips = listOf(clip),
            createdId = null,
        )

        assertSame(clip, view.clip("clip"))
        assertNull(view.clip("missing"))
        assertSame(media, view.mediaOf(clip))
        assertEquals(3.0, view.duration, 0.0)
        assertEquals(3.0, view.pictureEnd, 0.0)
        assertEquals(
            listOf("first", "second"),
            TestJsonArray(
                listOf(
                    TestJsonObject(mapOf("id" to "first")),
                    "not an object",
                    TestJsonObject(mapOf("id" to "second")),
                ),
            ).objects().map { it.optString("id") },
        )
    }
}

private class TestJsonObject(private val values: Map<String, Any?>) : JSONObject() {
    override fun optJSONObject(name: String?): JSONObject? = values[name.orEmpty()] as? JSONObject
    override fun optJSONArray(name: String?): org.json.JSONArray? =
        values[name.orEmpty()] as? TestJsonArray ?: TestJsonArray(emptyList())

    override fun optString(name: String?): String = values[name.orEmpty()] as? String ?: ""
    override fun optString(name: String?, fallback: String): String = values[name.orEmpty()] as? String ?: fallback
    override fun optInt(name: String?): Int = optInt(name, 0)
    override fun optInt(name: String?, fallback: Int): Int =
        (values[name.orEmpty()] as? Number)?.toInt() ?: fallback

    override fun optLong(name: String?): Long = optLong(name, 0L)
    override fun optLong(name: String?, fallback: Long): Long =
        (values[name.orEmpty()] as? Number)?.toLong() ?: fallback

    override fun optBoolean(name: String?): Boolean = values[name.orEmpty()] as? Boolean ?: false
    override fun optBoolean(name: String?, fallback: Boolean): Boolean = values[name.orEmpty()] as? Boolean ?: fallback
    override fun optDouble(name: String?): Double = optDouble(name, Double.NaN)
    override fun optDouble(name: String?, fallback: Double): Double =
        (values[name.orEmpty()] as? Number)?.toDouble() ?: fallback

    override fun isNull(name: String?): Boolean = !values.containsKey(name.orEmpty()) || values[name.orEmpty()] == null
}

private class TestJsonArray(private val values: List<Any?>) : org.json.JSONArray() {
    override fun length(): Int = values.size
    override fun getJSONObject(index: Int): JSONObject = values[index] as JSONObject
    override fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
}
