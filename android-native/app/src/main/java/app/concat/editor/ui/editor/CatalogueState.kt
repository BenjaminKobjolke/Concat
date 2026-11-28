// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.PackageInfo
import app.concat.editor.concat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogueUi(val packages: List<PackageInfo> = emptyList(), val loaded: Boolean = false, val error: String? = null)

/** Every package the build knows (`catalogue.list`), read once per editor. */
class CatalogueViewModel(application: Application) : AndroidViewModel(application) {
    private val api = application.concat.api
    private val _ui = MutableStateFlow(CatalogueUi())
    val ui: StateFlow<CatalogueUi> = _ui

    fun load() {
        if (_ui.value.loaded && _ui.value.error == null) return
        viewModelScope.launch {
            try {
                val packages = api.catalogue()
                _ui.update { CatalogueUi(packages, loaded = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _ui.update { it.copy(loaded = true, error = error.message ?: "The catalogue would not load.") }
            }
        }
    }

    fun find(id: String): PackageInfo? = _ui.value.packages.firstOrNull { it.id == id }
}

