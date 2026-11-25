// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.runtime.Composable
import app.concat.editor.api.ProjectView

internal data class EditorModalContext(
    val model: EditorViewModel,
    val view: ProjectView?,
    val catalogue: CatalogueViewModel,
    val shelf: Shelf?,
    val catalogueAdjust: Boolean,
    val exporting: Boolean,
    val renaming: Boolean,
    val onCloseShelf: () -> Unit,
    val onCloseExport: () -> Unit,
    val onCloseRename: () -> Unit,
)

@Composable
internal fun EditorModals(context: EditorModalContext) {
    context.shelf?.let { shelf ->
        CatalogueSheet(shelf, context.catalogue, context.model, context.catalogueAdjust, onDismiss = context.onCloseShelf)
    }
    if (context.exporting) ExportSheet(context.model, onDismiss = context.onCloseExport)
    if (context.renaming && context.view != null) {
        RenameDialog(context.view.name, onDismiss = context.onCloseRename) {
            context.model.rename(it)
            context.onCloseRename()
        }
    }
}
