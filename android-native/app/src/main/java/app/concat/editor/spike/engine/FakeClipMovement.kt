// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import kotlin.math.max
import org.json.JSONObject

private data class ClipMoveTarget(val track: String, val moving: Set<String>)

/** Moves clips to the nearest free span on their requested lanes. */
internal fun moveFakeClips(session: Session, command: JSONObject): String? {
    val known = session.tracks.objectsList().map { it.getString("id") }.toSet()
    val moves = command.getJSONArray("moves").objectsList()
    val moving = moves.map { it.getString("clipId") }.toSet()
    for (move in moves) {
        val clip = session.clips.objectsList().firstOrNull { it.optString("id") == move.getString("clipId") } ?: continue
        val track = move.optString("trackId").takeIf { it in known } ?: clip.getString("trackId")
        val start = nearestFreeStart(session, clip, move.getDouble("start"), ClipMoveTarget(track, moving))
        clip.put("start", start).put("trackId", track)
    }
    return null
}

private fun nearestFreeStart(session: Session, clip: JSONObject, wanted: Double, target: ClipMoveTarget): Double {
    var start = max(0.0, wanted)
    val length = clip.getDouble("duration")
    // Onto another clip is never where a move lands: the nearest gap that holds it.
    val others = session.clips.objectsList().filter { it.getString("trackId") == target.track && it.getString("id") !in target.moving }
        .map { it.getDouble("start") to (it.getDouble("start") + it.getDouble("duration")) }.sortedBy { it.first }
    fun fits(at: Double) = others.none { (s, e) -> s < at + length - 1e-6 && e > at + 1e-6 }
    if (fits(start)) return start
    val candidates = listOf(0.0) + others.map { it.second } + others.map { it.first - length }
    start = candidates.filter { it >= 0 && fits(it) }.minByOrNull { kotlin.math.abs(it - start) } ?: clip.getDouble("start")
    return start
}
