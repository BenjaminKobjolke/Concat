// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal typealias Session = FakeEngineBridge.Session
internal typealias Refusal = FakeEngineBridge.Refusal

internal class FakeCommandHandler(private val mintId: (String) -> String) {
    private val mediaImporter = FakeMediaImporter(mintId)
    private fun mint(prefix: String) = mintId(prefix)
    internal fun clip(session: Session, id: String) = session.clip(id)
    internal fun firstFreeAbove(session: Session, start: Double, end: Double) = session.firstFreeAbove(start, end)
private fun Session.clipList() = clips.objectsList()
    private fun Session.clip(id: String) = clipList().firstOrNull { it.getString("id") == id }
    private fun Session.setClips(list: List<JSONObject>) {
        timeline.put("clips", JSONArray().apply { list.forEach { put(it) } })
    }
    private fun JSONObject.clipIds() = getJSONArray("clipIds").let { ids ->
        (0 until ids.length()).map { ids.getString(it) }
    }
    private fun JSONObject.end() = getDouble("start") + getDouble("duration")

    /** Every clip on [track] starting at or after [from] moves by [delta]. */
    private fun Session.shift(track: String, from: Double, delta: Double, except: String? = null) {
        for (clip in clipList()) {
            if (clip.getString("trackId") == track && clip.getString("id") != except && clip.getDouble("start") >= from - 1e-6) {
                clip.put("start", max(0.0, clip.getDouble("start") + delta))
            }
        }
    }

    private fun Session.free(track: String, start: Double, end: Double) =
        clipList().none { it.getString("trackId") == track && it.getDouble("start") < end - 1e-6 && it.end() > start + 1e-6 }

    internal fun applyCommand(session: Session, command: JSONObject): String? = with(session) {
        when (val op = command.getString("op")) {
            "batch" -> applyBatch(session, command)
            "addClip" -> {
                val media = media(command.getString("mediaId"))
                val track = command.getString("trackId")
                val start = max(0.0, command.getDouble("start"))
                val length = media.optDouble("duration", 5.0).takeIf { !it.isNaN() } ?: 5.0
                if (command.optBoolean("ripple") && !free(track, start, start + length)) shift(track, start, length)
                place(media, track, start, length)
            }
            "addClipsInSequence" -> {
                val ids = command.getJSONArray("mediaIds")
                val items = (0 until ids.length()).map { media(ids.getString(it)) }
                val lengths = items.map { it.optDouble("duration", 5.0).takeIf { d -> !d.isNaN() } ?: 5.0 }
                var at = max(0.0, command.getDouble("start"))
                val total = lengths.sum()
                val named = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                // The lowest lane free for the whole run: lanes are stored bottom first.
                val track = named ?: (0 until tracks.length()).map { tracks.getJSONObject(it).getString("id") }
                    .firstOrNull { free(it, at, at + total) } ?: addTrack(session, top = true)
                if (named != null && command.optBoolean("ripple") && !free(track, at, at + total)) shift(track, at, total)
                var last: String? = null
                items.forEachIndexed { i, item -> last = place(item, track, at, lengths[i]); at += lengths[i] }
                last
            }
            "addTextClip" -> {
                val start = max(0.0, command.getDouble("start"))
                val length = command.optDouble("duration", 3.0).takeIf { !it.isNaN() } ?: 3.0
                val style = command.optJSONObject("style") ?: JSONObject().put("content", "Your text")
                val track = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                    ?: firstFreeAbove(start, start + length)
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
                    ?: firstFreeAbove(start, start + length)
                val id = mint("c")
                clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "")
                    .put("name", command.optString("name").ifBlank { style.optString("kind") })
                    .put("kind", "shape").put("start", start).put("duration", length).put("sourceStart", 0.0)
                    .put("volume", 1.0).put("speed", 1.0).put("shape", style))
                id
            }
            "splitClips" -> {
                val time = command.getDouble("time")
                val ids = command.clipIds()
                var last: String? = null
                for (id in ids) {
                    val clip = clip(id) ?: continue
                    val start = clip.getDouble("start")
                    if (time <= start + FakeEngineBridge.MIN || time >= clip.end() - FakeEngineBridge.MIN) continue
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
                val ids = command.clipIds()
                var first: String? = null
                for (id in ids.mapNotNull { clip(it) }.sortedByDescending { it.getDouble("start") }) {
                    val copy = JSONObject(id.toString()).put("id", mint("c")).put("start", id.end())
                    clips.put(copy)
                    first = copy.getString("id")
                }
                first
            }
            "moveClips" -> {
                moveClips(session, command)
            }
            "addTrack" -> addTrack(session, top = !command.optBoolean("bottom"))
            "addTransitionClip" -> {
                val start = max(0.0, command.getDouble("start"))
                val length = command.optDouble("duration", 1.0).takeIf { !it.isNaN() } ?: 1.0
                val track = command.optString("trackId").takeIf { !command.isNull("trackId") && it.isNotEmpty() }
                    ?: firstFreeAbove(start, start + length)
                val id = mint("c")
                val pkg = command.getString("transitionId")
                clips.put(JSONObject().put("id", id).put("trackId", track).put("mediaId", "")
                    .put("name", command.optString("name").ifBlank { pkg }).put("kind", "transition")
                    .put("start", start).put("duration", length).put("sourceStart", 0.0).put("volume", 1.0).put("speed", 1.0)
                    .put("videoEffects", JSONArray().put(JSONObject().put("id", pkg).put("params", JSONObject()).put("enabled", true))))
                id
            }
            "removeClips" -> {
                val ids = command.clipIds().toSet()
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
                val length = max(FakeEngineBridge.MIN, clip.getDouble("duration") * old / speed)
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
                    val cut = delta.coerceIn(-clip.getDouble("sourceStart") / clip.getDouble("speed"), clip.getDouble("duration") - FakeEngineBridge.MIN)
                    clip.put("sourceStart", clip.getDouble("sourceStart") + cut * clip.getDouble("speed"))
                        .put("duration", clip.getDouble("duration") - cut)
                    if (ripple) shift(clip.getString("trackId"), end, -cut, except = clip.getString("id"))
                    else clip.put("start", clip.getDouble("start") + cut)
                } else {
                    clip.put("duration", max(FakeEngineBridge.MIN, clip.getDouble("duration") + delta))
                    if (ripple) shift(clip.getString("trackId"), end, clip.end() - end, except = clip.getString("id"))
                }
                null
            }
            "updateClip" -> updateClip(session, command)
            "setClipTransform" -> setClipTransform(session, command)
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
                val trackId = command.getString("trackId")
                val look = command.optJSONObject("look")
                val lane = tracks.objectsList().firstOrNull { it.optString("id") == trackId }
                if (lane != null) {
                    if (look == null) lane.remove("captions") else lane.put("captions", JSONObject(look.toString()))
                    if (look != null) {
                        for (clip in clipList().filter { it.optString("trackId") == trackId && it.optString("kind") == "text" }) {
                            val old = clip.optJSONObject("text") ?: JSONObject()
                            val style = JSONObject(look.optJSONObject("style")?.toString() ?: "{}")
                                .put("content", old.optString("content")).apply { old.optJSONArray("runs")?.let { put("runs", it) } }
                            clip.put("text", style).put("offsetY", look.optDouble("offsetY", 0.0))
                        }
                    }
                }
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

    private fun applyBatch(session: Session, command: JSONObject): String? {
        var last: String? = null
        for (inner in command.getJSONArray("commands").objectsList()) last = applyCommand(session, inner) ?: last
        return last
    }

    private fun updateClip(session: Session, command: JSONObject): String? = with(session) {
        val clip = clip(command.getString("clipId")) ?: return null
        val patch = command.getJSONObject("patch")
        if (patch.has("volume")) clip.put("volume", max(0.0, patch.getDouble("volume")))
        if (patch.has("name")) clip.put("name", patch.getString("name"))
        if (patch.has("opacity")) clip.put("opacity", patch.getDouble("opacity").coerceIn(0.0, 1.0))
        updateClipFlags(clip, patch)
        updateClipEffects(clip, patch)
        updateClipTransition(clip, patch)
        updateClipText(clip, patch)
        null
    }

    private fun updateClipFlags(clip: JSONObject, patch: JSONObject) {
        for (flag in listOf("muted", "flipH", "flipV")) if (patch.has(flag)) clip.put(flag, patch.getBoolean(flag))
        for (fade in listOf("fadeIn", "fadeOut")) if (patch.has(fade)) clip.put(fade, max(0.0, patch.getDouble(fade)))
    }

    private fun updateClipEffects(clip: JSONObject, patch: JSONObject) {
        for (chain in listOf("videoEffects", "filters")) {
            if (patch.has(chain)) clip.put(chain, patch.optJSONArray(chain) ?: JSONArray())
        }
    }

    private fun updateClipTransition(clip: JSONObject, patch: JSONObject) {
        if (!patch.has("transitionIn")) return
        val transition = patch.optJSONObject("transitionIn")
        if (transition == null) clip.remove("transitionIn") else clip.put("transitionIn", transition)
    }

    private fun updateClipText(clip: JSONObject, patch: JSONObject) {
        if (!patch.has("text")) return
        val text = patch.optJSONObject("text")
        if (text == null) clip.remove("text") else {
            clip.put("text", text).put("name", text.optString("content").lineSequence().firstOrNull().orEmpty())
        }
    }

    private fun moveClips(session: Session, command: JSONObject): String? = with(session) {
        val known = tracks.objectsList().map { it.getString("id") }.toSet()
        val moves = command.getJSONArray("moves").objectsList()
        val moving = moves.map { it.getString("clipId") }.toSet()
        for (move in moves) {
            val clip = clip(move.getString("clipId")) ?: continue
            val track = move.optString("trackId").takeIf { it in known } ?: clip.getString("trackId")
            val start = nearestFreeStart(clip, move.getDouble("start"), track, moving)
            clip.put("start", start).put("trackId", track)
        }
        null
    }

    private fun Session.nearestFreeStart(clip: JSONObject, wanted: Double, track: String, moving: Set<String>): Double {
        var start = max(0.0, wanted)
        val length = clip.getDouble("duration")
        // Onto another clip is never where a move lands: the nearest gap that holds it.
        val others = clipList().filter { it.getString("trackId") == track && it.getString("id") !in moving }
            .map { it.getDouble("start") to it.end() }.sortedBy { it.first }
        fun fits(at: Double) = others.none { (s, e) -> s < at + length - 1e-6 && e > at + 1e-6 }
        if (fits(start)) return start
        val candidates = listOf(0.0) + others.map { it.second } + others.map { it.first - length }
        start = candidates.filter { it >= 0 && fits(it) }.minByOrNull { kotlin.math.abs(it - start) } ?: clip.getDouble("start")
        return start
    }

    private fun setClipTransform(session: Session, command: JSONObject): String? = with(session) {
        val clip = clip(command.getString("clipId")) ?: return null
        fun take(key: String) = command.optDouble(key).takeIf { command.has(key) && !command.isNull(key) && !it.isNaN() }
        take("scale")?.let { clip.put("scale", it.coerceIn(0.05, 8.0)) }
        take("offsetX")?.let { clip.put("offsetX", it.coerceIn(-3.0, 3.0)) }
        take("offsetY")?.let { clip.put("offsetY", it.coerceIn(-3.0, 3.0)) }
        take("rotation")?.let {
            var rotation = it % 360.0
            if (rotation > 180) rotation -= 360
            if (rotation <= -180) rotation += 360
            clip.put("rotation", rotation)
        }
        null
    }

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

    internal fun import(session: Session, file: String) = mediaImporter.import(session, file)

}
