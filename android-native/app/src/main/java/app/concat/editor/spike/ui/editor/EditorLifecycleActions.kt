// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.util.Log
import androidx.lifecycle.viewModelScope
import app.concat.editor.spike.concat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Persists project state and releases the preview at the editor's lifecycle boundary. */
internal class EditorLifecycleActions(
    private val model: EditorViewModel,
    private val appScope: CoroutineScope,
) {
    private var closed = false

    fun save() {
        if (closed) return
        appScope.launch {
            try {
                model.api.save(model.path)
            } catch (error: Exception) {
                Log.w(EditorViewModel.TAG, "save failed", error)
            }
        }
    }

    fun close(done: () -> Unit) {
        if (closed) return done()
        closed = true
        val shown = model.preview
        model.preview = null
        model.viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    shown?.pause()
                    shown?.release()
                    writeCard()
                    model.api.close(model.path, save = true)
                } catch (error: Exception) {
                    Log.w(EditorViewModel.TAG, "close failed", error)
                }
            }
            done()
        }
    }

    private suspend fun writeCard() {
        val view = model.ui.value.view ?: return
        val folder = File(model.path)
        try {
            File(folder, EditorViewModel.CARD).writeText(
                JSONObject().put("duration", view.duration).put("clips", view.clips.size)
                    .put("savedAt", System.currentTimeMillis()).toString(),
            )
        } catch (error: Exception) {
            Log.w(EditorViewModel.TAG, "no card for ${model.path}", error)
        }
        if (view.clips.isEmpty()) {
            File(folder, EditorViewModel.THUMBNAIL).delete()
            return
        }
        val width = if (view.height >= view.width) 270 else 480
        val height = (width * view.height.toDouble() / view.width.coerceAtLeast(1)).toInt()
        try {
            model.api.previewFrameTo(
                model.path,
                (view.duration * 0.25).coerceAtMost(2.0),
                File(folder, EditorViewModel.THUMBNAIL).absolutePath,
                width,
                height,
            )
        } catch (error: Exception) {
            Log.w(EditorViewModel.TAG, "no thumbnail for ${model.path}: ${error.message}")
        }
    }

    fun onCleared() {
        val shown = model.preview
        model.preview = null
        if (shown != null) appScope.launch(Dispatchers.IO) { shown.release() }
    }
}
