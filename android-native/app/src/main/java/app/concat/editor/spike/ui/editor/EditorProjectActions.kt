// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.net.Uri
import app.concat.editor.spike.api.Commands
import app.concat.editor.spike.api.ShapeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

fun EditorViewModel.setCoverFrame() {
    val at = ui.value.position
    change("Cover") { apply(Commands.setCover(time = at)) }
}

fun EditorViewModel.setCoverPicture(uri: Uri?) {
    if (uri == null) return
    change("Cover") {
        val file = withContext(Dispatchers.IO) { copyPickedFile(uri) }
        apply(Commands.setCover(picture = file.absolutePath))
    }
}

fun EditorViewModel.resetCover() {
    if (ui.value.view?.cover == null) return
    change("Cover") { apply(Commands.setCover()) }
}

fun EditorViewModel.addSticker(card: ShapeCard, name: String) {
    change("Sticker") {
        val view = apply(Commands.addShapeClip(ui.value.position, card.style, name))
        view.createdId?.let { id -> _ui.update { it.copy(selected = id) } }
        view
    }
}

