// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.engine

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import org.json.JSONObject

internal class FakeMediaImporter(private val mintId: (String) -> String) {
    fun import(session: Session, file: String): String? {
        if (session.media.objectsList().any { it.getString("path") == file }) return null
        val source = File(file)
        if (!source.exists()) throw Refusal("failed", "$file does not exist")
        val name = source.name
        val kind = mediaKind(name)
        val (probedKind, duration) = if (kind == "image") kind to null else probe(file, kind)
        val id = mintId("m")
        session.media.put(JSONObject().put("id", id).put("path", file).put("name", name).put("kind", probedKind)
            .put("duration", duration ?: JSONObject.NULL).put("hasAudio", probedKind != "image"))
        return id
    }

    private fun mediaKind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXTENSIONS -> "image"
        in AUDIO_EXTENSIONS -> "audio"
        else -> "video"
    }

    private fun probe(file: String, fallbackKind: String): Pair<String, Double?> = try {
        MediaMetadataRetriever().run {
            setDataSource(file)
            val duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0)
            val kind = if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null &&
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            ) "audio" else fallbackKind
            release()
            kind to duration
        }
    } catch (failure: Exception) {
        Log.w("concat-fake", "could not probe $file", failure)
        fallbackKind to null
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac")
    }
}
