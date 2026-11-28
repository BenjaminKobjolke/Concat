// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.concat.editor.api.ClipKind
import app.concat.editor.api.Commands
import app.concat.editor.api.ProjectView
import app.concat.editor.api.ShapeCard
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun EditorViewModel.copyIn(uri: Uri): File {
    val resolver = getApplication<Application>().contentResolver
    var name = "picked-${System.currentTimeMillis()}"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
    }
    // Each pick its own file: two picks of one name are two files.
    val target = File(app.mediaDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
    val input = resolver.openInputStream(uri) ?: throw IOException("The provider would not open $uri")
    input.use { stream -> target.outputStream().use { stream.copyTo(it, 1 shl 20) } }
    return target
}
