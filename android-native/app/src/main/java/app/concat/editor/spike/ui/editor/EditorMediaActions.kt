// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.concat.editor.spike.api.Commands
import app.concat.editor.spike.api.ProjectView
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun EditorViewModel.importEditorMedia(uris: List<Uri>, audio: Boolean) {
    if (uris.isEmpty()) return
    change("Import") {
        var view: ProjectView? = null
        val ids = mutableListOf<String>()
        for (uri in uris) {
            val file = withContext(Dispatchers.IO) { copyPickedFile(uri) }
            val after = api.import(path, file.absolutePath)
            view = after
            val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
            if (id != null) ids += id
        }
        if (ids.isEmpty()) return@change view
        val start = if (audio) ui.value.position else view?.pictureEnd ?: 0.0
        apply(Commands.addClipsInSequence(ids, start))
    }
}

internal fun EditorViewModel.importEditorOverlays(uris: List<Uri>) {
    if (uris.isEmpty()) return
    change("Overlay") {
        var view: ProjectView? = null
        for (uri in uris) {
            val file = withContext(Dispatchers.IO) { copyPickedFile(uri) }
            val after = api.import(path, file.absolutePath)
            val id = after.createdId ?: after.media.firstOrNull { it.path == file.absolutePath || File(it.path).name == file.name }?.id
                ?: continue
            val lane = apply(Commands.addTrack(bottom = false))
            val trackId = lane.createdId ?: lane.tracks.last().id
            view = apply(Commands.addClip(id, trackId, ui.value.position, ripple = false))
            view.createdId?.let { created -> _ui.update { it.copy(selected = created) } }
        }
        view
    }
}

private fun EditorViewModel.copyPickedFile(uri: Uri): File {
    val resolver = getApplication<Application>().contentResolver
    var name = "picked-${System.currentTimeMillis()}"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
    }
    val target = File(app.mediaDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
    val input = resolver.openInputStream(uri) ?: throw IOException("The provider would not open $uri")
    input.use { stream -> target.outputStream().use { stream.copyTo(it, 1 shl 20) } }
    return target
}
