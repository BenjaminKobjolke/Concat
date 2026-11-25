// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.api

// API response models used by secondary editor requests.

import org.json.JSONArray
import org.json.JSONObject

/** Something that happened to an export job: message.rs's `Event`. */
sealed interface ApiEvent {
    val job: String

    data class Progress(override val job: String, val frame: Long, val total: Long, val stage: String) : ApiEvent
    data class Analysing(override val job: String, val what: String, val fraction: Double) : ApiEvent
    data class Done(override val job: String, val output: String, val width: Int, val height: Int) : ApiEvent
    data class Failed(override val job: String, val code: String, val message: String) : ApiEvent

    companion object {
        fun parse(text: String): ApiEvent? {
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            val job = json.optString("job")
            return when (json.optString("event")) {
                "export.progress" -> Progress(job, json.optLong("frame"), json.optLong("total"), json.optString("stage"))
                "cutout.progress" -> Analysing(job, "cutout", json.optDouble("fraction"))
                "stabilize.progress" -> Analysing(job, "stabilize", json.optDouble("fraction"))
                "export.done" -> Done(job, json.optString("output"), json.optInt("width"), json.optInt("height"))
                "export.failed" -> {
                    val error = json.optJSONObject("error") ?: JSONObject()
                    Failed(job, error.optString("code", "failed"), error.optString("message"))
                }
                else -> null
            }
        }
    }
}

/** One speech model: `models.list`'s `ModelInfo`. */
data class ModelInfo(
    val id: String,
    val label: String,
    val blurb: String,
    val family: String,
    val englishOnly: Boolean,
    val sizeBytes: Long,
    val downloaded: Boolean,
) {
    companion object {
        fun from(json: JSONObject) = ModelInfo(
            id = json.optString("id"),
            label = json.optString("label").ifBlank { json.optString("id") },
            blurb = json.optString("blurb"),
            family = json.optString("family"),
            englishOnly = json.optBoolean("englishOnly"),
            sizeBytes = json.optLong("sizeBytes"),
            downloaded = json.optBoolean("downloaded"),
        )
    }
}

data class VoiceItem(val id: Int, val name: String, val family: String)

/** `models.list`: what hears (captions), what reads aloud, and the voices. */
data class ModelsInfo(val transcribers: List<ModelInfo>, val speech: List<ModelInfo>, val voices: List<VoiceItem>) {
    companion object {
        val EMPTY = ModelsInfo(emptyList(), emptyList(), emptyList())

        fun from(json: JSONObject) = ModelsInfo(
            transcribers = (json.optJSONArray("transcribers") ?: JSONArray()).objects().map(ModelInfo::from),
            speech = (json.optJSONArray("speech") ?: JSONArray()).objects().map(ModelInfo::from),
            voices = (json.optJSONArray("voices") ?: JSONArray()).objects().map {
                VoiceItem(it.optInt("id"), it.optString("name"), it.optString("family"))
            },
        )
    }
}

/** One knob of a package: `ParamInfo`. */
data class ParamInfo(
    val key: String,
    val label: String,
    val type: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val step: Double,
    val unit: String,
    val values: List<Double>,
    val labels: List<String>,
) {
    /** What a slider can show: numbers, a switch, a choice. Colours and points are left to the desktop. */
    val sliderable get() = type == "float" || type == "int" || type == "bool" || type == "enum"

    companion object {
        fun from(json: JSONObject): ParamInfo {
            val values = json.optJSONArray("values")
            val labels = json.optJSONArray("labels")
            return ParamInfo(
                key = json.optString("key"),
                label = json.optString("label").ifBlank { json.optString("key") },
                type = json.optString("type", "float"),
                min = json.optDouble("min", 0.0),
                max = json.optDouble("max", 1.0),
                default = json.optDouble("default", 0.0),
                step = json.optDouble("step", 0.0),
                unit = json.optString("unit"),
                values = values?.let { a -> (0 until a.length()).map { a.optDouble(it) } } ?: emptyList(),
                labels = labels?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
    }
}

/** One effect, filter, sound effect or transition: `catalogue.list`'s `PackageInfo`. */
data class PackageInfo(
    val id: String,
    val name: String,
    val kind: String,
    val category: String,
    val description: String,
    val intensity: String?,
    val params: List<ParamInfo>,
) {
    companion object {
        fun from(json: JSONObject) = PackageInfo(
            id = json.optString("id"),
            name = json.optString("name").ifBlank { json.optString("id") },
            kind = json.optString("kind"),
            category = json.optString("category").ifBlank { "Other" },
            description = json.optString("description"),
            intensity = if (json.isNull("intensity")) null else json.optString("intensity").ifEmpty { null },
            params = (json.optJSONArray("params") ?: JSONArray()).objects().map(ParamInfo::from),
        )
    }
}

/** One stretch of speech heard in a clip, seconds into the clip's sound. */
data class CaptionSegment(val start: Double, val end: Double, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("text", text)

    companion object {
        fun list(array: JSONArray?): List<CaptionSegment> = array?.objects()?.map {
            CaptionSegment(it.optDouble("start"), it.optDouble("end"), it.optString("text"))
        } ?: emptyList()
    }
}

/** `version`: the build and the contract it speaks. */
data class VersionInfo(val apiVersion: String, val concat: String, val capabilities: List<String>)

/**
 * A title's or shape's block as the engine painted it (`clips.bounds`):
 * fractions of the frame, before the clip's scale; [offsetX]/[offsetY] are
 * where the block's centre sits from the clip's position.
 */
data class ClipBounds(val width: Double, val height: Double, val offsetX: Double, val offsetY: Double)

/** One Stickers card (`shapes.list`): its stable id and the look it places, as `addShapeClip`'s `style`. */
data class ShapeCard(val id: String, val style: JSONObject)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
