// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.util.Log
import app.concat.editor.spike.export.ExportPlan
import app.concat.editor.spike.export.ExportPreset

/** Saves the open project before starting its export. */
fun EditorViewModel.export(preset: ExportPreset) {
    val view = ui.value.view ?: return
    pause()
    launchInViewModelScope {
        try {
            api.save(path)
        } catch (error: Exception) {
            Log.w(EditorViewModel.TAG, "save before export failed", error)
        }
        exports.start(ExportPlan(path, view.name.ifBlank { "Concat" }, view.width, view.height, preset))
            .onFailure { say(it.message ?: "The export did not start.") }
    }
}
