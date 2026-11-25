// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal class FakeEditCommands(
    private val mint: (String) -> String,
    private val media: (Session, String) -> JSONObject,
    private val place: (Session, JSONObject, String, Double, Double) -> String,
    private val addTrack: (Session, Boolean) -> String,
    private val firstFreeAbove: (Session, Double, Double) -> String,
) {
    fun apply(session: Session, command: JSONObject): String? =
        applyCommand(session, command, mint, media, place, addTrack, firstFreeAbove)
}

private fun applyCommand(
    session: Session,
    command: JSONObject,
    mint: (String) -> String,
    media: (Session, String) -> JSONObject,
    place: (Session, JSONObject, String, Double, Double) -> String,
    addTrack: (Session, Boolean) -> String,
    firstFreeAbove: (Session, Double, Double) -> String,
): String? = with(session) {
        when (val op = command.getString("op")) {
            "batch" -> {
                var last: String? = null
                for (inner in command.getJSONArray("commands").objectsList()) last = applyCommand(session, inner, mint, media, place, addTrack, firstFreeAbove) ?: last
                last
            }
            "addClip" -> {
                val item = media(session, command.getString("mediaId"))
                val track = command.getString("trackId")
                val start = max(0.0, command.getDouble("start"))
                val length = item.optDouble("duration", 5.0).takeIf { !it.isNaN() } ?: 5.0
                if (command.optBoolean("ripple") && !free(track, start, start + length)) shift(track, start, length)
                place(session, item, track, start, length)
            }
            "addClipsInSequence" -> {
                val ids = command.getJSONArray("mediaIds")
                val items = (0 until ids.length()).map { media(session, ids.getString(it)) }
                val lengths = items.map { it.optDouble("duration", 5.0).takeIf { d -> !d.isNaN() } ?: 5.0 }
                var at = max(0.0, command.getDouble("start"))
                val total = lengths.sum()
                val named = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                // The lowest lane free for the whole run: lanes are stored bottom first.
                val track = named ?: (0 until tracks.length()).map { tracks.getJSONObject(it).getString("id") }
                    .firstOrNull { free(it, at, at + total) } ?: addTrack(session, true)
                if (named != null && command.optBoolean("ripple") && !free(track, at, at + total)) shift(track, at, total)
                var last: String? = null
                items.forEachIndexed { i, item -> last = place(session, item, track, at, lengths[i]); at += lengths[i] }
                last
            }
            "addTextClip" -> {
                val start = max(0.0, command.getDouble("start"))
                val length = command.optDouble("duration", 3.0).takeIf { !it.isNaN() } ?: 3.0
                val style = command.optJSONObject("style") ?: JSONObject().put("content", "Your text")
                val track = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                    ?: firstFreeAbove(session, start, start + length)
                val id = mint("c")
                clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "")
                    .put("name", style.optString("content").lineSequence().firstOrNull().orEmpty())
                    .put("kind", "text").put("start", start).put("duration", length).put("sourceStart", 0.0)
                    .put("volume", 1.0).put("speed", 1.0).put("text", style))
                id
            }
            "addShapeClip" -> {
                val start = max(0.0, command.getDouble("start"))
                val length = command.optDouble("duration", 3.0).takeIf { !it.isNaN() } ?: 3.0
                val style = command.optJSONObject("style") ?: JSONObject().put("kind", "square")
                val track = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                    ?: firstFreeAbove(session, start, start + length)
                val id = mint("c")
                clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "")
                    .put("name", command.optString("name").ifBlank { style.optString("kind") })
                    .put("kind", "shape").put("start", start).put("duration", length).put("sourceStart", 0.0)
                    .put("volume", 1.0).put("speed", 1.0).put("shape", style))
                id
            }
            "splitClips" -> {
                val time = command.getDouble("time")
                val ids = command.getJSONArray("clipIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                var last: String? = null
                for (id in ids) {
                    val clip = clip(id) ?: continue
                    val start = clip.getDouble("start")
                    if (time <= start + FAKE_MIN || time >= clip.end() - FAKE_MIN) continue
                    val tail = JSONObject(clip.toString()).put("id", mint("c")).put("start", time)
                        .put("duration", clip.end() - time)
                        .put("sourceStart", clip.getDouble("sourceStart") + (time - start) * clip.getDouble("speed"))
                    clip.put("duration", time - start)
                    clips.put(tail)
                    last = tail.getString("id")
                }
                last
            }
            "duplicateClips" -> {
                val ids = command.getJSONArray("clipIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                var first: String? = null
                for (id in ids.mapNotNull { clip(it) }.sortedByDescending { it.getDouble("start") }) {
                    val copy = JSONObject(id.toString()).put("id", mint("c")).put("start", id.end())
                    clips.put(copy)
                    first = copy.getString("id")
                }
                first
            }
            "moveClips" -> {
                val known = tracks.objectsList().map { it.getString("id") }.toSet()
                val moves = command.getJSONArray("moves").objectsList()
                val moving = moves.map { it.getString("clipId") }.toSet()
                for (move in moves) {
                    val clip = clip(move.getString("clipId")) ?: continue
                    val track = move.optString("trackId").takeIf { it in known } ?: clip.getString("trackId")
                    var start = max(0.0, move.getDouble("start"))
                    val length = clip.getDouble("duration")
                    // Onto another clip is never where a move lands: the nearest gap that holds it.
                    val others = clipList().filter { it.getString("trackId") == track && it.getString("id") !in moving }
                        .map { it.getDouble("start") to it.end() }.sortedBy { it.first }
                    fun fits(at: Double) = others.none { (s, e) -> s < at + length - 1e-6 && e > at + 1e-6 }
                    if (!fits(start)) {
                        val candidates = listOf(0.0) + others.map { it.second } + others.map { it.first - length }
                        start = candidates.filter { it >= 0 && fits(it) }.minByOrNull { kotlin.math.abs(it - start) } ?: clip.getDouble("start")
                    }
                    clip.put("start", start).put("trackId", track)
                }
                null
            }
            "moveClipsToNewTrack" -> {
                // The lane and the move as one step; a move that lands no clip makes no lane.
                val moves = command.getJSONArray("moves").objectsList().filter { clip(it.getString("clipId")) != null }
                if (moves.isEmpty()) null
                else {
                    val lane = addTrack(session, !command.optBoolean("bottom"))
                    for (move in moves) clip(move.getString("clipId"))?.put("start", max(0.0, move.getDouble("start")))?.put("trackId", lane)
                    lane
                }
            }
            "addTrack" -> addTrack(session, !command.optBoolean("bottom"))
            "addTransitionClip" -> {
                val start = max(0.0, command.getDouble("start"))
                val length = command.optDouble("duration", 1.0).takeIf { !it.isNaN() } ?: 1.0
                val track = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                    ?: firstFreeAbove(session, start, start + length)
                val id = mint("c")
                val pkg = command.getString("transitionId")
                clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "")
                    .put("name", command.optString("name").ifBlank { pkg }).put("kind", "transition")
                    .put("start", start).put("duration", length).put("sourceStart", 0.0).put("volume", 1.0).put("speed", 1.0)
                    .put("videoEffects", JSONArray().put(JSONObject().put("id", pkg).put("params", JSONObject()).put("enabled", true))))
                id
            }
            "removeClips" -> {
                val ids = command.getJSONArray("clipIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val removed = clipList().filter { it.getString("id") in ids }
                var kept = clipList().filter { it.getString("id") !in ids }
                if (command.optBoolean("ripple")) {
                    kept = kept.onEach { clip ->
                        val gone = removed.filter { it.getString("trackId") == clip.getString("trackId") && it.end() <= clip.getDouble("start") + 1e-6 }
                        clip.put("start", clip.getDouble("start") - gone.sumOf { it.getDouble("duration") })
                    }
                }
                setClips(kept)
                null
            }
            "setClipSpeed" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                val speed = command.getDouble("speed").coerceIn(0.0625, 16.0)
                val old = clip.getDouble("speed")
                val end = clip.end()
                val length = max(FAKE_MIN, clip.getDouble("duration") * old / speed)
                clip.put("speed", speed).put("duration", length)
                if (command.optBoolean("ripple")) shift(clip.getString("trackId"), end, clip.end() - end, except = clip.getString("id"))
                null
            }
            "trimClip" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                val ripple = command.optBoolean("ripple")
                val end = clip.end()
                val delta = command.getDouble("delta")
                if (command.getString("edge") == "start") {
                    val cut = delta.coerceIn(-clip.getDouble("sourceStart") / clip.getDouble("speed"), clip.getDouble("duration") - FAKE_MIN)
                    clip.put("sourceStart", clip.getDouble("sourceStart") + cut * clip.getDouble("speed"))
                        .put("duration", clip.getDouble("duration") - cut)
                    if (ripple) shift(clip.getString("trackId"), end, -cut, except = clip.getString("id"))
                    else clip.put("start", clip.getDouble("start") + cut)
                } else {
                    clip.put("duration", max(FAKE_MIN, clip.getDouble("duration") + delta))
                    if (ripple) shift(clip.getString("trackId"), end, clip.end() - end, except = clip.getString("id"))
                }
                null
            }
            "updateClip" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                updateClip(clip, command.getJSONObject("patch"), mint)
                null
            }
            "setClipTransform" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                fun take(key: String) = command.optDouble(key).takeIf { command.has(key) && !command.isNull(key) && !it.isNaN() }
                take("scale")?.let { clip.put("scale", it.coerceIn(0.05, 8.0)) }
                take("offsetX")?.let { clip.put("offsetX", it.coerceIn(-3.0, 3.0)) }
                take("offsetY")?.let { clip.put("offsetY", it.coerceIn(-3.0, 3.0)) }
                take("rotation")?.let {
                    var r = it % 360.0
                    if (r > 180) r -= 360
                    if (r <= -180) r += 360
                    clip.put("rotation", r)
                }
                null
            }
            "setClipKey" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                val property = command.getString("property")
                val at = command.getDouble("at").coerceIn(0.0, 1.0)
                val keys = (clip.optJSONArray("keys") ?: JSONArray()).objectsList()
                    .filterNot { it.optString("property") == property && kotlin.math.abs(it.optDouble("at") - at) < 0.002 }
                val added = keys + JSONObject().put("property", property).put("at", at).put("value", command.getDouble("value")).put("ease", "linear")
                clip.put("keys", JSONArray().apply { added.sortedWith(compareBy({ it.optString("property") }, { it.optDouble("at") })).forEach { put(it) } })
                null
            }
            "clearClipKey" -> {
                val clip = clip(command.getString("clipId")) ?: return null
                val property = command.getString("property")
                val at = command.getDouble("at")
                val kept = (clip.optJSONArray("keys") ?: JSONArray()).objectsList()
                    .filterNot { it.optString("property") == property && kotlin.math.abs(it.optDouble("at") - at) < 0.002 }
                clip.put("keys", JSONArray().apply { kept.forEach { put(it) } })
                null
            }
            "setCaptionLook" -> {
                setCaptionLook(command)
                null
            }
            "setCover" -> {
                val cover = command.optJSONObject("cover")
                if (cover == null) session.project.remove("cover")
                else session.project.put("cover", JSONObject(cover.toString()).apply { if (has("time")) put("time", max(0.0, getDouble("time"))) })
                null
            }
            "setTimelineBackground" -> {
                timeline.put("background", command.getJSONObject("background"))
                null
            }
            else -> throw Refusal("invalid", "the in-memory engine does not apply $op")
        }
    }

