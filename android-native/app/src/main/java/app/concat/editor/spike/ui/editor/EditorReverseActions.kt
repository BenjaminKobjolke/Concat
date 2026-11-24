// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.concat.editor.spike.api.JobTracker
import app.concat.editor.spike.api.errorMessage
import app.concat.editor.spike.api.wasCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class EditorReverseActions(private val model: EditorViewModel) {
    private val jobs = JobTracker("reverse", ::onEvent)

    init {
        model.viewModelScope.launch { model.api.rawEvents.collect(jobs::offer) }
    }

    fun freeze() {
        val clip = model.selectedClip() ?: return
        val at = model.ui.value.position
        val edge = 1.0 / (model.ui.value.view?.fps ?: 30.0)
        if (at <= clip.start + edge || at >= clip.end - edge) {
            model.say("Move the playhead inside the clip to freeze it.")
            return
        }
        model.change("Freeze") {
            val view = model.api.clipsFreeze(model.path, clip.id, at)
            view.clips.firstOrNull { it.trackId == clip.trackId && kotlin.math.abs(it.start - at) < 1e-6 }
                ?.let { still -> model._ui.update { it.copy(selected = still.id) } }
            view
        }
    }

    fun replace(uri: Uri?) {
        val clip = model.selectedClip() ?: return
        if (uri == null) return
        model.change("Replace") {
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) { model.copyPickedFile(uri) }
            model.api.clipsReplace(model.path, clip.id, file.absolutePath)
        }
    }

    fun reverse() {
        val clip = model.selectedClip() ?: return
        if (model.ui.value.reversing != null) return model.say("A reverse is already running.")
        model._ui.update { it.copy(reversing = clip.id to 0f) }
        jobs.expect()
        model.viewModelScope.launch {
            try {
                jobs.start(model.api.clipsReverse(model.path, clip.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                jobs.stop()
                model._ui.update { it.copy(reversing = null) }
                model.say(error.message ?: "The reverse did not start.")
            }
        }
    }

    fun cancelReverse() {
        val job = jobs.job ?: return
        model.viewModelScope.launch {
            try {
                model.api.reverseCancel(job)
            } catch (error: Exception) {
                Log.w(EditorViewModel.TAG, "reverse cancel failed", error)
            }
        }
    }

    private fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "reverse.progress" -> model._ui.update { ui ->
                ui.copy(reversing = ui.reversing?.copy(second = event.optDouble("fraction", 0.0).toFloat()))
            }
            "reverse.done" -> {
                jobs.stop()
                model._ui.update { it.copy(reversing = null) }
                model.change("Reverse") { model.api.clipsUseReversed(model.path, event) }
            }
            "reverse.failed" -> {
                jobs.stop()
                model._ui.update { it.copy(reversing = null) }
                if (!event.wasCancelled()) model.say(event.errorMessage())
            }
        }
    }
}

fun EditorViewModel.freeze() = reverseActions.freeze()
fun EditorViewModel.replace(uri: Uri?) = reverseActions.replace(uri)
fun EditorViewModel.reverse() = reverseActions.reverse()
fun EditorViewModel.cancelReverse() = reverseActions.cancelReverse()
