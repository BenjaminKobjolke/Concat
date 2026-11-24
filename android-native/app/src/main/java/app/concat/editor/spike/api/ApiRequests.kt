// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.api

import org.json.JSONObject

/** The frame size and rate used to create or change a project's timeline. */
data class ProjectVideo(val width: Int, val height: Int, val rateNum: Long, val rateDen: Long = 1)

/** The source span and output size requested for a timeline filmstrip. */
data class FilmstripRequest(val file: String, val from: Double, val to: Double, val count: Int, val height: Int)

/** The frame and output file requested from `preview.frame`. */
data class PreviewFrameRequest(val path: String, val time: Double, val output: String, val width: Int, val height: Int)

/** Codec settings and destination for one export job. */
data class ExportRequest(val path: String, val output: String, val width: Int, val height: Int, val crf: Int, val preset: String)

/** Caption segments and their shared lane placement. */
data class CaptionsPlacementRequest(
    val path: String,
    val clipId: String,
    val segments: List<CaptionSegment>,
    val placement: String,
    val size: String,
)

/** Values that define one clip transform. */
data class ClipTransform(val scale: Double, val offsetX: Double, val offsetY: Double, val rotation: Double)

/** Values for placing one transition on the timeline. */
data class TransitionClipRequest(
    val transitionId: String,
    val name: String,
    val start: Double,
    val duration: Double? = null,
    val trackId: String? = null,
)

/** The editable text style fields, with an optional base style to preserve untouched fields. */
data class TextStyleRequest(
    val content: String,
    val fontSize: Double,
    val color: String,
    val base: JSONObject? = null,
    val fontWeight: Double? = null,
)

/** The text, voice, model and speed for one speech job. */
data class SpeechRequest(val path: String, val text: String, val model: String, val voice: Int, val speed: Double)
